package ekuzo.crazyworldprogression.respawn;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells a player's own client that they came back without a respawn: after reviving themselves in a raid by walking
 * into their marker. The client plays the return sound for that player alone ({@code RespawnFeedbackClient}, which
 * plays it on its own the moment a respawn after death arrives, and skips the death screen).
 */
public final class RespawnFeedback {
    private RespawnFeedback() { }

    public record ReturnedPayload() implements CustomPacketPayload {
        public static final Type<ReturnedPayload> TYPE = new Type<>(CrazyWorldProgression.id("returned"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReturnedPayload> CODEC = StreamCodec.unit(new ReturnedPayload());
        @Override public Type<ReturnedPayload> type() { return TYPE; }
    }

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(ReturnedPayload.TYPE, ReturnedPayload.CODEC);
    }

    /** The player came back to life without respawning (a respawn after death is noticed by the client itself). */
    public static void returned(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, ReturnedPayload.TYPE)) ekuzo.crazyworldprogression.CwpPayloads.send(player, new ReturnedPayload());
    }
}
