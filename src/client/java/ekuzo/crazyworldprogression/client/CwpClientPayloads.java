package ekuzo.crazyworldprogression.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sending CWP's own packets to the server. */
public final class CwpClientPayloads {
    private CwpClientPayloads() { }

    /**
     * Sends the packet only when the server listens for it: a server without CWP, or with that part of CWP switched
     * off (see CwpModule), does not, and the packet type may not even be known here.
     */
    public static void send(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) ClientPlayNetworking.send(payload);
    }
}
