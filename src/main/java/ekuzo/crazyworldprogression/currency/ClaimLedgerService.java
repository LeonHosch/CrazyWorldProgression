package ekuzo.crazyworldprogression.currency;

import ekuzo.crazyworldprogression.progression.player.PlayerProgressionData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/** Optional generic claim ledger for currencies awarded by unique game events. */
public final class ClaimLedgerService {
    // Prevent instantiation of the claim-ledger facade.
    private ClaimLedgerService() {
    }

    // Record one unique claim when neither the identifier nor the configured lifetime limit blocks it.
    public static ClaimResult claim(MinecraftServer server, UUID playerUuid, Identifier ledgerId,
                                    Identifier claimId, int maximumClaims) {
        if (maximumClaims < 0) throw new IllegalArgumentException("Maximum claims must not be negative");
        PlayerProgressionData data = PlayerProgressionData.get(server);
        if (data.hasClaim(playerUuid, ledgerId, claimId.toString())) return ClaimResult.ALREADY_CLAIMED;
        if (data.claimCount(playerUuid, ledgerId) >= maximumClaims) return ClaimResult.CLAIM_LIMIT_REACHED;
        data.addClaim(playerUuid, ledgerId, claimId.toString());
        return ClaimResult.CLAIMED;
    }

    public enum ClaimResult { CLAIMED, ALREADY_CLAIMED, CLAIM_LIMIT_REACHED }
}
