package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.storage.ItemLocks;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A fully locked item in the main hand or offhand isn't swapped with F (ItemLocks). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class OffhandSwapLockMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void cwp$lockedSwap(ServerboundPlayerActionPacket packet, CallbackInfo info) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) return;
        if (ItemLocks.lock(player.getMainHandItem()) == ItemLocks.FULL || ItemLocks.lock(player.getOffhandItem()) == ItemLocks.FULL) {
            info.cancel();
            player.inventoryMenu.sendAllDataToRemote();
        }
    }
}
