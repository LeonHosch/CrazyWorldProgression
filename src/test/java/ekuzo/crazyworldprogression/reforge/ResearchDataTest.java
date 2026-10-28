package ekuzo.crazyworldprogression.reforge;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchDataTest {
    @Test void playerAttachmentPayloadSurvivesSaveAndReload() {
        ResearchData.Player original = new ResearchData.Player(
                Map.of("minecraft:sharpness", 12, "minecraft:mending", 4), "minecraft:mending");
        var encoded = ResearchData.Player.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        assertEquals(original, ResearchData.Player.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
}
