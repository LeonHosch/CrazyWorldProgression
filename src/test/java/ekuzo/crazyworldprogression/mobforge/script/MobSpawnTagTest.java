package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobSpawnTagTest {
    private final MobScriptCode code = new MobScriptCode();

    @Test void mobSpawnAcceptsATemplateOrAMobIdAndOldNamesStillLoad() {
        MobScript current = code.decode("""
                script tags
                mob_spawn(template="raid_zombie", location={"x":0,"y":64,"z":0}, count=2, tag="adds")
                mob_spawn(mob_id="minecraft:zombie", location={"x":1,"y":64,"z":1}, tag="boss")
                mob_phase(mob="boss", phase="enraged")
                """);
        MobScript legacy = code.decode("""
                script tags
                spawn(template="raid_zombie", location={"x":0,"y":64,"z":0}, count=2, tag="adds")
                spawn_mobs(mob_id="minecraft:zombie", location={"x":1,"y":64,"z":1}, tag="boss")
                set_phase(mob="boss", phase="enraged")
                """);
        assertEquals(current, legacy);
        assertEquals("adds", ((MobScript.SpawnStep) current.steps().get(0)).tag());
        assertEquals("boss", ((MobScript.SpawnMobsStep) current.steps().get(1)).tag());
        String written = code.encode(legacy);
        assertTrue(written.contains("mob_spawn(") && written.contains("mob_phase("), written);
        assertFalse(written.contains("spawn_mobs") || written.contains("set_phase") || written.contains("\nspawn("), written);
        assertEquals(current, code.decode(written));
    }

    @Test void mobSpawnNeedsExactlyOneOfTemplateOrMobId() {
        assertThrows(IllegalArgumentException.class, () -> code.decode("""
                script both
                mob_spawn(template="raid_zombie", mob_id="minecraft:zombie", location={"x":0,"y":64,"z":0})
                """));
        assertThrows(IllegalArgumentException.class, () -> code.decode("""
                script neither
                mob_spawn(location={"x":0,"y":64,"z":0})
                """));
        assertThrows(IllegalArgumentException.class, () -> code.decode("""
                script bad_tag
                mob_spawn(mob_id="minecraft:zombie", location={"x":0,"y":64,"z":0}, tag="Boss!")
                """));
    }

    @Test void aTagGroupIsDeadOnlyWhenEveryTaggedMobIsDead() {
        FakeMob first = new FakeMob(20, 20), second = new FakeMob(10, 20);
        TagGroup group = new TagGroup();
        group.add(first);
        group.add(second);
        Map<String, ScriptEntityHandle> handles = Map.of("adds", group);
        assertFalse(ScriptCondition.evaluate("dead(\"adds\")", handles));
        assertTrue(ScriptCondition.evaluate("health_percent(\"adds\") <= 0.75", handles));
        first.health = 0;
        assertFalse(ScriptCondition.evaluate("dead(\"adds\")", handles));
        second.health = 0;
        assertTrue(ScriptCondition.evaluate("dead(\"adds\")", handles));
    }

    @Test void plainTagsAndOldHandleSyntaxBothWork() {
        assertEquals("boss", ScriptCondition.reference("boss"));
        assertEquals("boss", ScriptCondition.reference("${boss}"));
    }

    private static final class FakeMob implements ScriptEntityHandle {
        double health;
        final double max;
        final UUID id = UUID.randomUUID();
        FakeMob(double health, double max) { this.health = health; this.max = max; }
        @Override public UUID uuid() { return id; }
        @Override public Identifier entityId() { return Identifier.parse("minecraft:zombie"); }
        @Override public boolean isAlive() { return health > 0; }
        @Override public double health() { return health; }
        @Override public double maxHealth() { return max; }
        @Override public ServerLevel world() { return null; }
        @Override public Vec3 position() { return Vec3.ZERO; }
        @Override public void teleport(Vec3 position) { }
        @Override public void kill() { health = 0; }
    }
}
