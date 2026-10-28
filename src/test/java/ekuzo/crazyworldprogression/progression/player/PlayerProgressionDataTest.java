package ekuzo.crazyworldprogression.progression.player;

import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProgressionDataTest {
    private static final UUID ONLINE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Identifier CURRENCY = Identifier.parse("other-game:coins");
    private static final Identifier LEDGER = Identifier.parse("other-game:reward");

    @Test void offlineAndOnlinePlayersSurviveOneWorldSaveWithoutCrossingRecords() {
        PlayerProgressionData data = new PlayerProgressionData();
        data.setBalance(ONLINE, CURRENCY, 14);
        data.setBalance(OFFLINE, CURRENCY, 23);
        data.unlockSkill(ONLINE, "other-game:skill_a");
        data.addClaim(OFFLINE, LEDGER, "first");

        var encoded = PlayerProgressionData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        PlayerProgressionData restored = PlayerProgressionData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(14, restored.balance(ONLINE, CURRENCY));
        assertEquals(23, restored.balance(OFFLINE, CURRENCY));
        assertTrue(restored.isSkillUnlocked(ONLINE, "other-game:skill_a"));
        assertFalse(restored.isSkillUnlocked(OFFLINE, "other-game:skill_a"));
        assertTrue(restored.hasClaim(OFFLINE, LEDGER, "first"));
        assertFalse(restored.hasClaim(ONLINE, LEDGER, "first"));
    }

}
