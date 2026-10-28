package ekuzo.crazyworldprogression.storage;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Item locks, cycled with L on an item in the player's own inventory (none, half, full). A half-locked item can't be
 * dropped, put into a chest or any other container, or quicksorted; a fully locked one also can't be moved from its
 * slot at all. The lock is on the item stack (the item_lock component), so it shows in the tooltip and on the slot.
 */
public final class ItemLocks {
    public static final int HALF = 1, FULL = 2;
    public static final DataComponentType<Integer> LOCK = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            CrazyWorldProgression.id("item_lock"), DataComponentType.<Integer>builder()
                    .persistent(Codec.intRange(HALF, FULL)).networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /** Client to server: cycle the lock of the item in this slot of the open menu. */
    public record CyclePayload(int slot) implements CustomPacketPayload {
        public static final Type<CyclePayload> TYPE = new Type<>(CrazyWorldProgression.id("item_lock_cycle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CyclePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, CyclePayload::slot, CyclePayload::new);
        @Override public Type<CyclePayload> type() { return TYPE; }
    }

    private ItemLocks() { }

    static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(CyclePayload.TYPE, CyclePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CyclePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            AbstractContainerMenu menu = player.containerMenu;
            if (payload.slot() < 0 || payload.slot() >= menu.slots.size()) return;
            Slot slot = menu.slots.get(payload.slot());
            if (!(slot.container instanceof Inventory) || !slot.hasItem()) return;
            ItemStack stack = slot.getItem();
            int next = (lock(stack) + 1) % 3;
            if (next == 0) stack.remove(LOCK);
            else stack.set(LOCK, next);
            slot.setChanged();
            menu.broadcastChanges();
        });
    }

    public static int lock(ItemStack stack) {
        Integer lock = stack.get(LOCK);
        return lock == null ? 0 : lock;
    }

    public static boolean locked(ItemStack stack) { return lock(stack) > 0; }

    /** Whether this click on a menu must be refused because of a locked item (AbstractContainerMenuLockMixin). */
    public static boolean blocks(AbstractContainerMenu menu, int slotId, int button, ContainerInput input, Player player) {
        ItemStack carried = menu.getCarried();
        boolean playerMenu = menu instanceof InventoryMenu;
        // Dropping a carried item outside the window.
        if (slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) return locked(carried);
        if (slotId < 0 || slotId >= menu.slots.size()) return false;
        Slot slot = menu.slots.get(slotId);
        ItemStack inSlot = slot.getItem();
        if (lock(inSlot) == FULL && input != ContainerInput.CLONE) return true;
        boolean ownSlot = slot.container instanceof Inventory;
        return switch (input) {
            case THROW -> locked(inSlot);
            case QUICK_MOVE -> !playerMenu && ownSlot && locked(inSlot);
            case PICKUP -> !ownSlot && locked(carried);
            case QUICK_CRAFT -> !playerMenu && locked(carried);
            case SWAP -> {
                ItemStack hotbar = button >= 0 && button < 9 ? player.getInventory().getItem(button)
                        : button == 40 ? player.getOffhandItem() : ItemStack.EMPTY;
                yield lock(hotbar) == FULL || (!ownSlot && locked(hotbar));
            }
            default -> false;
        };
    }
}
