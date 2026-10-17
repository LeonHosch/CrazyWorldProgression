package ekuzo.crazyworldprogression.loot;

import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
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

    private static LootPoolData.Entry item(String target, int chance) {
        return new LootPoolData.Entry("item", Identifier.parse("minecraft:" + target), chance, 1, 1, 1);
    }

    private static Map<String, LootPoolData.Pool> catalogue(LootPoolData.Pool... pools) {
        Map<String, LootPoolData.Pool> map = new HashMap<>();
        for (LootPoolData.Pool pool : pools) map.put(pool.id().toString(), pool);
        return map;
    }

    /** Returns the queued values in order, so each test controls every roll. */
    private static LootPoolService.Roller rolls(int... values) {
        Deque<Integer> queue = new ArrayDeque<>();
        for (int value : values) queue.add(value);
        return bound -> {
            int value = queue.removeFirst();
            if (value >= bound) throw new AssertionError("roll " + value + " is outside bound " + bound);
            return value;
        };
    }

    @Test void topLevelEntriesRollIndependentlyAndNestedPoolsPickExactlyOneByShare() {
        var root = new LootPoolData.Pool(id("root"), "Root", "all",
                List.of(item("gold_ingot", 10000), item("diamond", 2500), nested("armor")));
        var armor = new LootPoolData.Pool(id("armor"), "Armor", "one",
                List.of(item("iron_helmet", 7500), new LootPoolData.Entry("pool", id("rare"), 2500, 1, 1, 1)));
        var rare = new LootPoolData.Pool(id("rare"), "Rare", "one",
                List.of(item("netherite_helmet", 5000), item("netherite_boots", 5000)));
        var pools = catalogue(root, armor, rare);
        // gold hits, diamond misses (2500 is not below 2500), armor hits; armor share roll 7500 lands in rare,
        // rare roll 6000 lands in the second half.
        var rewards = LootPoolService.pick(root.id(), pools, rolls(0, 2500, 2499, 7500, 6000));
        assertEquals(List.of("minecraft:gold_ingot", "minecraft:netherite_boots"),
                rewards.stream().map(entry -> entry.target().toString()).toList());
        // armor itself misses its 25% chance, so nothing nested is picked.
        rewards = LootPoolService.pick(root.id(), pools, rolls(9999, 0, 2500));
        assertEquals(List.of("minecraft:gold_ingot", "minecraft:diamond"), rewards.stream().map(entry -> entry.target().toString()).toList());
    }

    @Test void nestedSharesNeedNotTotalHundredPercent() {
        var pool = new LootPoolData.Pool(id("pick"), "Pick", "one", List.of(item("apple", 100), item("bread", 300)));
        var pools = catalogue(pool);
        assertEquals("minecraft:bread", LootPoolService.pick(pool.id(), pools, rolls(100)).getFirst().target().toString());
        assertEquals("minecraft:apple", LootPoolService.pick(pool.id(), pools, rolls(99)).getFirst().target().toString());
        var empty = new LootPoolData.Pool(id("empty"), "Empty", "one", List.of(item("apple", 0)));
        assertEquals(List.of(), LootPoolService.pick(empty.id(), catalogue(empty), rolls()));
    }

    @Test void oldPickOnePoolsKeepTheirOddsAfterMigration() {
        var old = new LootPoolData.Pool(id("old"), "Old", "one", List.of(
                new LootPoolData.Entry("item", Identifier.parse("minecraft:apple"), 10000, 1, 1, 1),
                new LootPoolData.Entry("item", Identifier.parse("minecraft:bread"), 10000, 3, 1, 1)));
        var data = new LootPoolData();
        data.put(old);
        var encoded = LootPoolData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
        encoded.remove("format");
        var restored = LootPoolData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        var entries = restored.values().get(old.id().toString()).entries();
        assertEquals(2500, entries.get(0).chance());
        assertEquals(7500, entries.get(1).chance());
        var current = LootPoolData.CODEC.parse(JsonOps.INSTANCE,
                LootPoolData.CODEC.encodeStart(JsonOps.INSTANCE, restored).getOrThrow()).getOrThrow();
        assertEquals(restored.values(), current.values());
    }

    @Test void deletingAPoolAlsoCollectsNestedPoolsOnlyItUses() {
        var root = new LootPoolData.Pool(id("root"), "Root", "all", List.of(nested("root/nested_1"), nested("shared")));
        var child = new LootPoolData.Pool(id("root/nested_1"), "Nested", "one", List.of(nested("root/nested_1/nested_1")));
        var grandchild = new LootPoolData.Pool(id("root/nested_1/nested_1"), "Deep", "one", List.of());
        var shared = new LootPoolData.Pool(id("shared"), "Shared", "one", List.of());
        var other = new LootPoolData.Pool(id("other"), "Other", "all", List.of(nested("shared")));
        var pools = catalogue(root, child, grandchild, shared, other);
        var removed = new LinkedHashSet<String>();
        LootPoolService.collectOwned(root.id(), pools, removed);
        assertEquals(List.of("cwp:root", "cwp:root/nested_1", "cwp:root/nested_1/nested_1"), List.copyOf(removed));
        assertEquals(Identifier.parse("cwp:root/nested_2"), LootPoolService.nestedId(pools, root.id()));
    }
}
