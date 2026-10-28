package ekuzo.crazyworldprogression.titles;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AchievementTitleJsonTest {
    @Test void customTitleMetadataKeepsVanillaAdvancementLoadable() throws Exception {
        Path path = Path.of("../EchelonCore/src/main/resources/data/echelon-core/advancement/mysteries/crowned.json");
        var json = JsonParser.parseString(Files.readString(path));
        assertEquals("echelon-core:crowned", json.getAsJsonObject().getAsJsonObject("cwp_title")
                .get("id").getAsString());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertTrue(Advancement.CODEC.parse(JsonOps.INSTANCE, json).result().isPresent(),
                "Minecraft must still accept an advancement with title metadata");
    }
}
