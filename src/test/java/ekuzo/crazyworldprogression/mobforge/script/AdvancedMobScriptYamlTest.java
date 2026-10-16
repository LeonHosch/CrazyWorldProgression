package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedMobScriptYamlTest {
    @Test void advancedStepsRoundTripWithoutLosingNamespacedIds() {
        String yaml = """
                id: guardian_wave
                steps:
                  - spawn_entity:
                      id: example:guardian
                      at: arena_center
                      save_as: guardian
                  - wait_until:
                      condition: '${guardian.health_percent <= 0.5}'
                  - mob_cast:
                      mob: '${guardian}'
                      ability: example:pulse
                  - set_phase:
                      mob: '${guardian}'
                      phase: enraged
                  - wait_until:
                      condition: '${guardian.phase == enraged}'
                  - trigger:
                      event: example:guardian_enraged
                """;
        MobScriptYaml codec = new MobScriptYaml();
        MobScript script = codec.decode(yaml);
        assertEquals(6, script.steps().size());
        assertEquals(script, codec.decode(codec.encode(script)));
    }

    @Test void rejectsUnsupportedExpressionsAndUnnamespacedMobIds() {
        assertThrows(IllegalArgumentException.class, () -> ScriptCondition.validate("${guardian.class.forName('x')}"));
        assertThrows(IllegalArgumentException.class, () -> ScriptCondition.validate("${guardian.health_percent <= 5}"));
        assertThrows(IllegalArgumentException.class, () -> new MobScriptYaml().decode("""
                id: bad
                steps:
                  - spawn_entity: {id: guardian, at: origin, save_as: guardian}
                """));
    }

    @Test void conditionsUseOnlyExplicitHandleCapabilities() {
        ScriptEntityHandle guardian = new ScriptEntityHandle() {
            @Override public UUID uuid() { return UUID.randomUUID(); }
            @Override public Identifier entityId() { return Identifier.parse("minecraft:zombie"); }
            @Override public boolean isAlive() { return true; }
            @Override public double health() { return 40; }
            @Override public double maxHealth() { return 100; }
            @Override public ServerLevel world() { return null; }
            @Override public Vec3 position() { return Vec3.ZERO; }
            @Override public void teleport(Vec3 position) { throw new UnsupportedOperationException(); }
            @Override public void kill() { throw new UnsupportedOperationException(); }
        };
        assertTrue(ScriptCondition.evaluate("${guardian.health_percent <= 0.5}", Map.of("guardian", guardian)));
        assertFalse(ScriptCondition.evaluate("${guardian.dead}", Map.of("guardian", guardian)));
        assertThrows(IllegalArgumentException.class, () -> ScriptCondition.evaluate("${missing.dead}", Map.of("guardian", guardian)));
    }

    @Test void triggerDispatchesNamespacedEventAndRunId() {
        UUID runId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        ScriptSignals.register(new ScriptSignals.Listener() {
            @Override public void onTrigger(UUID run, Identifier event, ServerLevel world, Map<String, ScriptEntityHandle> handles) {
                if (run.equals(runId) && event.equals(Identifier.parse("example:done"))) calls.incrementAndGet();
            }
        });
        ScriptSignals.trigger(runId, Identifier.parse("example:done"), null, Map.of());
        assertEquals(1, calls.get());
    }
}
