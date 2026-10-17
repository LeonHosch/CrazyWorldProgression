package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PocketScriptTranslationTest {
    @Test void movesSpawnAndTargetWithoutChangingOtherSteps() {
        MobTemplate.Ai ai = new MobTemplate.Ai("target_position", true,
                new MobTemplate.Position(12, 70, 14), 3, null, 64, Map.of("custom", "kept"));
        MobScript.SpawnStep spawn = new MobScript.SpawnStep("guard",
                new MobScript.Position(10, 65, 11), MobScript.Offset.NONE, 2, ai);
        MobScript.SleepStep sleep = new MobScript.SleepStep(20);
        MobScript result = PocketEditorService.translate(new MobScript("wave", List.of(spawn, sleep)),
                new BlockPos(-8, 4, 6));

        MobScript.SpawnStep moved = (MobScript.SpawnStep) result.steps().getFirst();
        assertEquals(new MobScript.Position(2, 69, 17), moved.location());
        assertEquals(new MobTemplate.Position(4, 74, 20), moved.aiOverride().target());
        assertEquals(Map.of("custom", "kept"), moved.aiOverride().settings());
        assertSame(sleep, result.steps().get(1));
    }

    @Test void movesRaidSensorsAndPortalLocationsIntoTheInstance() {
        MobScript script = new MobScript("raid", List.of(
                new MobScript.EventBoxStep("plate", new MobScript.Position(10, 64, 10),
                        new MobScript.Position(11, 66, 11), "players", "", "entered", "inside"),
                new MobScript.RedstoneStep("switch", new MobScript.Position(12, 65, 12)),
                new MobScript.PortalLocationStep(Identifier.parse("cwp:gate"), "add", "arena",
                        new MobScript.Position(13, 65, 13))));
        MobScript moved = PocketEditorService.translate(script, new BlockPos(100, 4, -10));
        assertEquals(new MobScript.Position(110, 68, 0),
                ((MobScript.EventBoxStep) moved.steps().getFirst()).min());
        assertEquals("entered", ((MobScript.EventBoxStep) moved.steps().getFirst()).playerVariable());
        assertEquals("inside", ((MobScript.EventBoxStep) moved.steps().getFirst()).countVariable());
        assertEquals(new MobScript.Position(112, 69, 2),
                ((MobScript.RedstoneStep) moved.steps().get(1)).position());
        assertEquals(new MobScript.Position(113, 69, 3),
                ((MobScript.PortalLocationStep) moved.steps().get(2)).position());
    }

    @Test void movesDirectPowerChecksBuildingsAndEffectsIntoTheInstance() {
        MobScript script = new MobScript("arena", List.of(
                new MobScript.IfStep("powered(10, 64, 12) and kills('minecraft:zombie') >= 2"),
                new MobScript.BuildingStep(Identifier.parse("cwp:wall"), "spawn",
                        new MobScript.Position(11, 64, 13)),
                new MobScript.ExplosionStep(new MobScript.Position(12, 65, 14), 2),
                new MobScript.SoundStep(Identifier.parse("minecraft:block.note_block.pling"),
                        new MobScript.Position(13, 66, 15)),
                new MobScript.EndIfStep()));
        MobScript moved = PocketEditorService.translate(script, new BlockPos(100, 4, -10));
        assertEquals("powered(110, 68, 2) and kills('minecraft:zombie') >= 2",
                ((MobScript.IfStep) moved.steps().getFirst()).condition());
        assertEquals(new MobScript.Position(111, 68, 3),
                ((MobScript.BuildingStep) moved.steps().get(1)).location());
        assertEquals(new MobScript.Position(112, 69, 4),
                ((MobScript.ExplosionStep) moved.steps().get(2)).location());
        assertEquals(new MobScript.Position(113, 70, 5),
                ((MobScript.SoundStep) moved.steps().get(3)).location());
    }
}
