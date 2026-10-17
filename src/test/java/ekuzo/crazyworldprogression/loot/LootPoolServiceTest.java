package ekuzo.crazyworldprogression.loot;

import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LootPoolServiceTest {
    private static Identifier id(String value) { return Identifier.parse("cwp:" + value); }
    private static LootPoolData.Entry nested(String target) {
        return new LootPoolData.Entry("pool", id(target), 2500, 1, 1, 1);
    }

    @Test void nestedPoolsMayNestAgainButCannotCycleOrReferenceMissingPools() {
        var root = new LootPoolData.Pool(id("root"), "Root", "all", List.of(nested("armor")));
        var armor = new LootPoolData.Pool(id("armor"), "Armor", "one", List.of(nested("rare")));
        var rare = new LootPoolData.Pool(id("rare"), "Rare", "one", List.of());
        assertDoesNotThrow(() -> LootPoolService.validateCatalogue(Map.of(
                root.id().toString(), root, armor.id().toString(), armor, rare.id().toString(), rare)));
        assertThrows(IllegalArgumentException.class, () -> LootPoolService.validateCatalogue(Map.of(
                root.id().toString(), root, armor.id().toString(), armor)));
        var cycle = new LootPoolData.Pool(id("rare"), "Rare", "one", List.of(nested("root")));
        assertThrows(IllegalArgumentException.class, () -> LootPoolService.validateCatalogue(Map.of(
                root.id().toString(), root, armor.id().toString(), armor, cycle.id().toString(), cycle)));
    }

    @Test void savedPoolsKeepNestedReferencesAndRollSettings() {
        var root = new LootPoolData.Pool(id("root"), "Raid rewards", "all",
                List.of(new LootPoolData.Entry("pool", id("armor"), 2500, 1, 1, 1),
                        new LootPoolData.Entry("item", Identifier.parse("minecraft:diamond_sword"),
                                100, 1, 1, 1, true)));
        var armor = new LootPoolData.Pool(id("armor"), "Armor", "one", List.of());
        var data = new LootPoolData();
        data.put(root);
        data.put(armor);
        var encoded = LootPoolData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var restored = LootPoolData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(data.values(), restored.values());
        assertEquals(true, restored.values().get(root.id().toString()).entries().get(1).showInChat());
        var oldEntry = LootPoolData.Entry.CODEC.encodeStart(JsonOps.INSTANCE, root.entries().get(1))
                .getOrThrow().getAsJsonObject();
        oldEntry.remove("show_in_chat");
        assertEquals(false, LootPoolData.Entry.CODEC.parse(JsonOps.INSTANCE, oldEntry)
                .getOrThrow().showInChat());
    }
}
