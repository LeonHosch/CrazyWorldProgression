package ekuzo.crazyworldprogression.loot;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FishingLootServiceTest {
    @Test
    void allConditionsWithinARuleMustMatch() {
        FishingLootData.Rule riverStormNight = new FishingLootData.Rule(id("cwp:river_storm_night"),
                id("cwp:rare_fish"), "minecraft:river", "minecraft:water", "thunder", "night");
        FishingLootData.Rule anyWater = new FishingLootData.Rule(id("cwp:water"),
                id("cwp:common_fish"), "", "#minecraft:water", "any", "any");
        FishingLootData.Rule lava = new FishingLootData.Rule(id("cwp:lava"),
                id("cwp:lava_fish"), "", "minecraft:lava", "any", "any");
        List<FishingLootData.Rule> rules = List.of(riverStormNight, anyWater, lava);

        assertEquals(List.of(riverStormNight, anyWater), FishingLootService.matching(rules,
                id("minecraft:river"), ignored -> false, id("minecraft:water"),
                tag -> tag.equals(id("minecraft:water")), "thunder", "night"));
        assertEquals(List.of(anyWater), FishingLootService.matching(rules,
                id("minecraft:river"), ignored -> false, id("minecraft:water"),
                tag -> tag.equals(id("minecraft:water")), "rain", "night"));
        assertEquals(List.of(lava), FishingLootService.matching(rules,
                id("minecraft:desert"), ignored -> false, id("minecraft:lava"),
                ignored -> false, "clear", "day"));
    }

    @Test
    void matchingRulesetsUseRelativeWeights() {
        FishingLootData.Rule general = new FishingLootData.Rule(id("cwp:general"),
                id("cwp:common_fish"), "minecraft:dark_forest", "minecraft:water", "any", "any", 90);
        FishingLootData.Rule night = new FishingLootData.Rule(id("cwp:night"),
                id("cwp:night_fish"), "minecraft:dark_forest", "minecraft:water", "any", "night", 10);
        List<FishingLootData.Rule> matching = FishingLootService.matching(List.of(general, night),
                id("minecraft:dark_forest"), ignored -> false, id("minecraft:water"),
                ignored -> false, "clear", "night");
        assertEquals(general, FishingLootService.choose(matching, bound -> 89));
        assertEquals(night, FishingLootService.choose(matching, bound -> 90));
        assertEquals(night, FishingLootService.choose(matching, bound -> 99));
        assertEquals(general, FishingLootService.choose(List.of(general), bound -> 89));
    }

    @Test
    void pocketIdMatchesBobberPocketRatherThanPlayerBiome() {
        FishingLootData.Rule pocket = new FishingLootData.Rule(id("cwp:pocket_fishing"),
                id("cwp:pocket_fish"), "cwp:deep_cave", "minecraft:water", "any", "night");
        List<FishingLootData.Rule> rules = List.of(pocket);
        assertEquals(rules, FishingLootService.matching(rules, id("minecraft:plains"),
                ignored -> false, id("cwp:deep_cave"), id("minecraft:water"),
                ignored -> false, "clear", "night"));
        assertEquals(List.of(), FishingLootService.matching(rules, id("minecraft:plains"),
                ignored -> false, null, id("minecraft:water"),
                ignored -> false, "clear", "night"));
        assertEquals(List.of(), FishingLootService.matching(rules, id("minecraft:plains"),
                ignored -> false, id("cwp:deep_cave"), id("minecraft:water"),
                ignored -> false, "clear", "day"));

        FishingLootData.Rule biome = new FishingLootData.Rule(id("cwp:biome"),
                id("cwp:biome_fish"), "minecraft:plains", "minecraft:water", "any", "any");
        assertEquals(List.of(pocket), FishingLootService.matching(List.of(biome, pocket),
                id("minecraft:plains"), ignored -> false, id("cwp:deep_cave"),
                id("minecraft:water"), ignored -> false, "clear", "night"));
    }

    private static Identifier id(String value) { return Identifier.parse(value); }
}
