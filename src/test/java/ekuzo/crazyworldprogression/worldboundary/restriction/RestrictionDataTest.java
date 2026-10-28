package ekuzo.crazyworldprogression.worldboundary.restriction;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestrictionDataTest {
    @Test
    void restrictionsAndBoundaryThresholdSurviveCodecRoundTrip() {
        Restriction restriction = new Restriction(Identifier.parse("crazy-world-progression:test"),
                Identifier.parse("minecraft:overworld"), Restriction.Shape.SPHERE,
                0, 64, 0, 8, 8, 8, Restriction.BoundaryMode.KEEP_OUT,
                List.of(new Restriction.RestrictionState(0, Restriction.Collision.SOLID,
                        Restriction.Visibility.OPAQUE, Restriction.BlockInteraction.AUTOMATIC)));
        JsonObject saved = new JsonObject();
        JsonObject entries = new JsonObject();
        entries.add(restriction.id().toString(), Restriction.CODEC.encodeStart(JsonOps.INSTANCE, restriction).getOrThrow());
        saved.add("restrictions", entries);
        RestrictionData restored = RestrictionData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(restriction, restored.values().get(restriction.id().toString()));
        JsonObject current = RestrictionData.CODEC.encodeStart(JsonOps.INSTANCE, restored)
                .getOrThrow().getAsJsonObject();
        assertTrue(current.has("restrictions"));
        assertTrue(current.toString().contains("minimum_boundary_level"));
    }
}
