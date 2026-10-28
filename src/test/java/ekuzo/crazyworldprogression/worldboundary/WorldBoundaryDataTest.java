package ekuzo.crazyworldprogression.worldboundary;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBoundaryDataTest {
    @Test
    void leaderRecordSurvivesCodecRoundTrip() {
        UUID leader = UUID.randomUUID();
        JsonObject saved = new JsonObject();
        saved.addProperty("leader", leader.toString());
        WorldLeaderData restored = WorldLeaderData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(leader, restored.leader().orElseThrow());
        JsonObject current = WorldLeaderData.CODEC.encodeStart(JsonOps.INSTANCE, restored)
                .getOrThrow().getAsJsonObject();
        assertEquals(leader.toString(), current.get("leader").getAsString());
    }

    @Test
    void boundaryRadiiSurviveCodecRoundTrip() {
        JsonObject radii = new JsonObject();
        JsonObject pair = new JsonObject();
        pair.addProperty("inner", 100);
        pair.addProperty("outer", 150);
        radii.add("minecraft:overworld|2", pair);
        JsonObject old = new JsonObject();
        old.add("radii", radii);
        BoundaryRadiusData migrated = BoundaryRadiusData.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertEquals(new AdvancedWorldBoundary.Radii(100, 150),
                migrated.values().get("minecraft:overworld|2"));
        assertTrue(BoundaryRadiusData.CODEC.encodeStart(JsonOps.INSTANCE, migrated)
                .getOrThrow().getAsJsonObject().has("radii"));
    }
}
