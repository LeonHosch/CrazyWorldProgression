package ekuzo.crazyworldprogression.loot;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FishingLootCodecTest {
    @Test
    void legacyRulesGetWeightAndNewRulesKeepIt() {
        JsonObject legacy = new JsonObject();
        legacy.addProperty("id", "cwp:old");
        legacy.addProperty("pool", "cwp:fish");
        assertEquals(100, FishingLootData.Rule.CODEC.parse(JsonOps.INSTANCE, legacy)
                .result().orElseThrow().weight());
        FishingLootData.Rule rule = new FishingLootData.Rule(Identifier.parse("cwp:new"),
                Identifier.parse("cwp:fish"), "minecraft:river", "", "any", "night", 10);
        var encoded = FishingLootData.Rule.CODEC.encodeStart(JsonOps.INSTANCE, rule).result().orElseThrow();
        assertEquals(rule, FishingLootData.Rule.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow());
    }

    @Test
    void optionalItemXpAndLevelSurviveSerialization() {
        LootPoolData.Entry entry = new LootPoolData.Entry("item", Identifier.parse("minecraft:cod"),
                10000, 1, 1, 3, false, 5, 12);
        var encoded = LootPoolData.Entry.CODEC.encodeStart(JsonOps.INSTANCE, entry).result().orElseThrow();
        assertEquals(entry, LootPoolData.Entry.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow());
        JsonObject legacy = new JsonObject();
        legacy.addProperty("kind", "item");
        legacy.addProperty("target", "minecraft:cod");
        LootPoolData.Entry oldEntry = LootPoolData.Entry.CODEC.parse(JsonOps.INSTANCE, legacy)
                .result().orElseThrow();
        assertEquals(0, oldEntry.xpPerItem());
        assertEquals(0, oldEntry.level());
    }

    @Test
    void legacyRulesShareOneLocationAndEmptyLocationPersists() {
        FishingLootData.Rule first = new FishingLootData.Rule(Identifier.parse("cwp:first"),
                Identifier.parse("cwp:fish"), "minecraft:river", "", "any", "any");
        FishingLootData.Rule second = new FishingLootData.Rule(Identifier.parse("cwp:second"),
                Identifier.parse("cwp:rare_fish"), "minecraft:river", "", "any", "night");
        JsonArray rules = new JsonArray();
        rules.add(FishingLootData.Rule.CODEC.encodeStart(JsonOps.INSTANCE, first).result().orElseThrow());
        rules.add(FishingLootData.Rule.CODEC.encodeStart(JsonOps.INSTANCE, second).result().orElseThrow());
        JsonObject oldSave = new JsonObject();
        oldSave.add("rules", rules);
        FishingLootData data = FishingLootData.CODEC.parse(JsonOps.INSTANCE, oldSave).result().orElseThrow();
        assertEquals(java.util.List.of("minecraft:river"), data.locations());
        data.remove(first.id());
        data.remove(second.id());
        assertEquals(java.util.List.of("minecraft:river"), data.locations());
        var encoded = FishingLootData.CODEC.encodeStart(JsonOps.INSTANCE, data).result().orElseThrow();
        FishingLootData reloaded = FishingLootData.CODEC.parse(JsonOps.INSTANCE, encoded)
                .result().orElseThrow();
        assertEquals(java.util.List.of("minecraft:river"), reloaded.locations());
        reloaded.removeLocation("minecraft:river");
        assertEquals(java.util.List.of(), reloaded.locations());
    }
}
