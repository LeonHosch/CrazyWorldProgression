package ekuzo.crazyworldprogression;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Sending CWP's own packets to a player. */
public final class CwpPayloads {
    private CwpPayloads() { }

    /**
     * Sends the packet only when the player's game listens for it. A game without CWP, or with that part of CWP
     * switched off in its config (see CwpModule), would otherwise be disconnected.
     */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }
}
