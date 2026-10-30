package ekuzo.crazyworldprogression.creative;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.accessory.Accessories;
import ekuzo.crazyworldprogression.accessory.BuffFood;
import ekuzo.crazyworldprogression.ammo.AmmoPouch;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The accessory, buff food and ammo slots in the creative inventory. Vanilla only sends creative changes of its own
 * 45 inventory slots, so the creative screen sends these slots' contents itself; the server takes them only from a
 * player in creative and only what the slot accepts.
 */
public final class CreativeSlots {
    private CreativeSlots() { }

    public static boolean isExtraSlot(Slot slot) {
        return Accessories.isSlot(slot) || BuffFood.isSlot(slot) || AmmoPouch.isSlot(slot);
    }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(SetPayload.TYPE, SetPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetPayload.TYPE, (payload, context) -> set(context.player(),
                payload.slot(), payload.stack()));
    }

    private static void set(ServerPlayer player, int index, ItemStack stack) {
        if (!player.isCreative() || index < 0 || index >= player.inventoryMenu.slots.size()) return;
        Slot slot = player.inventoryMenu.slots.get(index);
        if (!isExtraSlot(slot)) return;
        if (!stack.isEmpty() && (!slot.mayPlace(stack) || stack.getCount() > slot.getMaxStackSize(stack))) {
            player.inventoryMenu.sendAllDataToRemote();
            return;
        }
        slot.set(stack.copy());
        slot.setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    /** Client to server: the content of one extra inventory-menu slot, set in the creative inventory. */
    public record SetPayload(int slot, ItemStack stack) implements CustomPacketPayload {
        public static final Type<SetPayload> TYPE = new Type<>(CrazyWorldProgression.id("creative_extra_slot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetPayload::slot, ItemStack.OPTIONAL_STREAM_CODEC, SetPayload::stack,
                SetPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
