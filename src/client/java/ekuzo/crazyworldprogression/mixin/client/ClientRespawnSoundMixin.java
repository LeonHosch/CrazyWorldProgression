package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.RespawnFeedbackClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices a respawn after death so the return sound can play. The death is taken from the kill packet: after an
 * instant death (/kill, /damage) the server can respawn the player before the client ever sees its health drop to 0.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientRespawnSoundMixin {
    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"))
    private void crazyworldprogression$rememberDeath(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        // The packet first arrives on the network thread and is re-run on the client thread; only that run counts.
        if (!client.isSameThread() || client.player == null || packet.playerId() != client.player.getId()) return;
        RespawnFeedbackClient.died();
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void crazyworldprogression$playReturn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        RespawnFeedbackClient.respawned();
    }
}
