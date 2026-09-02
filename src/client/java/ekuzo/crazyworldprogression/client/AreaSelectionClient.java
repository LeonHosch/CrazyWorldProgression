package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.selection.AreaSelectionNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionResult;

/** Client prediction for CWP area selection so chosen blocks never crack, place, or open locally. */
public final class AreaSelectionClient {
    private static volatile boolean active;

    // Prevent construction of the static client state holder.
    private AreaSelectionClient() {
    }

    // Receive selection mode and reserve both block mouse buttons while it is active.
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(AreaSelectionNetworking.SelectionModePayload.TYPE,
                (payload, context) -> active = payload.active());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> active = false);
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!active || !(player instanceof LocalPlayer)) return InteractionResult.PASS;
            ClientPlayNetworking.send(new AreaSelectionNetworking.SelectionPointPayload(
                    pos.getX(), pos.getY(), pos.getZ(), true));
            return InteractionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!active || !(player instanceof LocalPlayer)) return InteractionResult.PASS;
            var pos = hit.getBlockPos();
            ClientPlayNetworking.send(new AreaSelectionNetworking.SelectionPointPayload(
                    pos.getX(), pos.getY(), pos.getZ(), false));
            return InteractionResult.FAIL;
        });
    }
}
