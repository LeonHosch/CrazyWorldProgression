package ekuzo.crazyworldprogression.ammo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Three ammo slots per player, below the inventory. Each holds one kind of arrow (anything in {@code #minecraft:arrows}),
 * up to {@link #MAX} of it. One slot is selected (the R key cycles through them, AmmoClient); bows and crossbows shoot
 * from it first, then from the other ammo slots, then as in vanilla (PlayerAmmoMixin). On death the ammo drops with the
 * inventory.
 */
public final class AmmoPouch {
    public static final int SLOTS = 3;
    /** Three stacks of 64 per slot. */
    public static final int MAX = 192;
    /** Positions in the vanilla inventory screen: a row below the panel. */
    public static final int SLOT_X = 62, SLOT_Y = 172;

    /** One slot: the arrow (stored with a count of 1, since saved stacks cannot hold more than 99) and how many. */
    private record Stored(ItemStack item, int count) {
        static final Codec<Stored> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.OPTIONAL_CODEC.fieldOf("item").forGetter(Stored::item),
                Codec.INT.optionalFieldOf("count", 0).forGetter(Stored::count)
        ).apply(instance, Stored::new));
    }

    private static final Codec<List<ItemStack>> CODEC = Stored.CODEC.listOf().xmap(
            list -> new ArrayList<>(list.stream().map(stored -> stored.item().isEmpty() || stored.count() <= 0
                    ? ItemStack.EMPTY : stored.item().copyWithCount(stored.count())).toList()),
            stacks -> stacks.stream().map(stack -> new Stored(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1),
                    stack.getCount())).toList());
    public static final AttachmentType<List<ItemStack>> STORAGE = AttachmentRegistry.create(
            CrazyWorldProgression.id("ammo"), builder -> builder.persistent(CODEC).copyOnDeath()
                    .initializer(AmmoPouch::emptySlots));
    public static final AttachmentType<Integer> SELECTED = AttachmentRegistry.create(
            CrazyWorldProgression.id("ammo_selected"), builder -> builder.persistent(Codec.INT).copyOnDeath().initializer(() -> 0));

    /** Client to server: select the next (1) or previous (-1) ammo slot. */
    public record CyclePayload(int direction) implements CustomPacketPayload {
        public static final Type<CyclePayload> TYPE = new Type<>(CrazyWorldProgression.id("ammo_cycle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CyclePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, CyclePayload::direction, CyclePayload::new);
        @Override public Type<CyclePayload> type() { return TYPE; }
    }

    /** Server to client: which ammo slot is selected. */
    public record SelectedPayload(int slot) implements CustomPacketPayload {
        public static final Type<SelectedPayload> TYPE = new Type<>(CrazyWorldProgression.id("ammo_selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectedPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SelectedPayload::slot, SelectedPayload::new);
        @Override public Type<SelectedPayload> type() { return TYPE; }
    }

    private AmmoPouch() { }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> slots = new ArrayList<>();
        for (int index = 0; index < SLOTS; index++) slots.add(ItemStack.EMPTY);
        return slots;
    }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(CyclePayload.TYPE, CyclePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SelectedPayload.TYPE, SelectedPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CyclePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            select(player, Math.floorMod(selected(player) + Integer.signum(payload.direction()), SLOTS));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.player));
    }

    public static boolean fits(ItemStack stack) { return stack.is(ItemTags.ARROWS); }

    public static int selected(Player player) { return Math.floorMod(player.getAttachedOrCreate(SELECTED), SLOTS); }

    public static void select(ServerPlayer player, int slot) {
        player.setAttached(SELECTED, slot);
        sync(player);
    }

    private static void sync(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, SelectedPayload.TYPE))
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new SelectedPayload(selected(player)));
    }

    /** The stored stacks (live: shrinking one uses the ammo up). */
    private static List<ItemStack> storage(Player player) {
        List<ItemStack> stored = player.getAttachedOrCreate(STORAGE);
        if (stored.size() < SLOTS || !(stored instanceof ArrayList<ItemStack>)) {
            List<ItemStack> fixed = new ArrayList<>(stored);
            while (fixed.size() < SLOTS) fixed.add(ItemStack.EMPTY);
            player.setAttached(STORAGE, fixed);
            return fixed;
        }
        return stored;
    }

    /**
     * The ammo a bow or crossbow uses: the selected slot if it holds something the weapon shoots, then the other slots;
     * null to look in the inventory as vanilla does.
     */
    public static ItemStack projectileFor(Player player, ItemStack weapon) {
        if (!(weapon.getItem() instanceof ProjectileWeaponItem projectileWeapon)) return null;
        var supported = projectileWeapon.getAllSupportedProjectiles();
        List<ItemStack> stored = storage(player);
        int first = selected(player);
        for (int offset = 0; offset < SLOTS; offset++) {
            ItemStack stack = stored.get((first + offset) % SLOTS);
            if (!stack.isEmpty() && supported.test(stack)) return stack;
        }
        return null;
    }

    /** Death without keep-inventory: the ammo drops like the rest of the inventory. */
    public static void dropAll(ServerPlayer player) {
        List<ItemStack> stored = storage(player);
        for (int index = 0; index < SLOTS; index++) {
            ItemStack stack = stored.get(index);
            while (!stack.isEmpty()) player.drop(stack.split(stack.getMaxStackSize()), true, false);
            stored.set(index, ItemStack.EMPTY);
        }
    }

    /**
     * Puts arrows from {@code stack} into the ammo slots: first a slot with the same arrows, then an empty one.
     * Returns whether anything moved.
     */
    public static boolean insert(Container container, ItemStack stack) {
        if (!fits(stack)) return false;
        boolean moved = false;
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++)
            for (int index = 0; index < SLOTS && !stack.isEmpty(); index++) {
                ItemStack slot = container.getItem(index);
                boolean same = !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack);
                if (pass == 0 ? !same : !slot.isEmpty()) continue;
                int room = MAX - slot.getCount();
                if (room <= 0) continue;
                int amount = Math.min(room, stack.getCount());
                container.setItem(index, stack.copyWithCount(slot.getCount() + amount));
                stack.shrink(amount);
                moved = true;
            }
        return moved;
    }

    public static boolean isSlot(Slot slot) { return slot instanceof AmmoSlot; }

    /** The player's ammo slots as a container, for the inventory menu. */
    public static Container container(Player player) { return new AmmoContainer(player); }

    public static Slot slot(Player player, Container container, int index) {
        return new AmmoSlot(container, index, SLOT_X + index * 18, SLOT_Y, player);
    }

    private static final class AmmoContainer implements Container {
        private final Player player;

        private AmmoContainer(Player player) { this.player = player; }

        @Override public int getContainerSize() { return SLOTS; }

        @Override public boolean isEmpty() { return storage(player).stream().allMatch(ItemStack::isEmpty); }

        @Override public ItemStack getItem(int slot) { return storage(player).get(slot); }

        /** Taking from a slot takes at most one normal stack. */
        @Override public ItemStack removeItem(int slot, int amount) {
            List<ItemStack> stored = storage(player);
            ItemStack stack = stored.get(slot);
            ItemStack removed = stack.split(Math.min(amount, stack.getMaxStackSize()));
            if (stack.isEmpty()) stored.set(slot, ItemStack.EMPTY);
            return removed;
        }

        @Override public ItemStack removeItemNoUpdate(int slot) {
            List<ItemStack> stored = storage(player);
            ItemStack removed = stored.get(slot);
            stored.set(slot, ItemStack.EMPTY);
            return removed;
        }

        @Override public void setItem(int slot, ItemStack stack) { storage(player).set(slot, stack); }

        @Override public int getMaxStackSize() { return MAX; }

        @Override public void setChanged() { }

        @Override public boolean stillValid(Player user) { return user == player; }

        @Override public void clearContent() { player.setAttached(STORAGE, emptySlots()); }
    }

    private static final class AmmoSlot extends Slot {
        private final Player player;

        private AmmoSlot(Container container, int index, int x, int y, Player player) {
            super(container, index, x, y);
            this.player = player;
        }

        @Override public boolean mayPlace(ItemStack stack) { return fits(stack); }

        @Override public int getMaxStackSize() { return MAX; }

        @Override public int getMaxStackSize(ItemStack stack) { return MAX; }

        /** The creative inventory lays slots out on its own and has no place for these. */
        @Override public boolean isActive() { return !player.isCreative(); }
    }
}
