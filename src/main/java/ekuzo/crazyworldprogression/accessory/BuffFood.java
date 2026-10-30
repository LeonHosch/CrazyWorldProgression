package ekuzo.crazyworldprogression.accessory;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;

import java.util.HashSet;
import java.util.Set;

/**
 * The buff food slot, in the inventory below the crafting result. It takes a stack of buff food: food whose consume
 * effects apply a good status effect that was added to it (a dish given buffs through its components), or a modded
 * food with such effects. Vanilla foods with their own effects (golden apples and the like) do not fit. Once none of
 * that food's good effects is active on the player any more, one item is eaten from the slot at once: its consume
 * effects apply, but it restores no hunger or saturation. On death the slot drops with the inventory; on a raid wipe
 * it goes into the orb.
 */
public final class BuffFood {
    /** Position in the vanilla inventory screen: below the crafting result. */
    public static final int SLOT_X = 154, SLOT_Y = 58;
    /** How often the slot is checked, in ticks. */
    private static final int CHECK_TICKS = 10;
    public static final AttachmentType<ItemStack> STORAGE = AttachmentRegistry.create(
            CrazyWorldProgression.id("buff_food"), builder -> builder.persistent(ItemStack.OPTIONAL_CODEC).copyOnDeath()
                    .initializer(() -> ItemStack.EMPTY));

    private BuffFood() { }

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(BuffFood::tick);
    }

    /**
     * Buff food: its consume effects include a beneficial status effect, and they are not just a vanilla item's own
     * (a golden apple as it comes does not fit; the same item with buffs added would).
     */
    public static boolean fits(ItemStack stack) {
        if (stack.isEmpty() || buffs(stack).isEmpty()) return false;
        boolean vanilla = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft");
        return !vanilla || !java.util.Objects.equals(stack.get(DataComponents.CONSUMABLE),
                stack.getItem().components().get(DataComponents.CONSUMABLE));
    }

    /** The good effects eating this food gives. */
    public static Set<Holder<MobEffect>> buffs(ItemStack stack) {
        Set<Holder<MobEffect>> buffs = new HashSet<>();
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) return buffs;
        for (ConsumeEffect effect : consumable.onConsumeEffects())
            if (effect instanceof ApplyStatusEffectsConsumeEffect apply)
                for (var instance : apply.effects()) if (instance.getEffect().value().isBeneficial()) buffs.add(instance.getEffect());
        return buffs;
    }

    public static ItemStack get(Player player) { return player.getAttachedOrCreate(STORAGE); }

    public static void set(Player player, ItemStack stack) { player.setAttached(STORAGE, stack.isEmpty() ? ItemStack.EMPTY : stack); }

    public static void clear(Player player) { set(player, ItemStack.EMPTY); }

    /** Death without keep-inventory: the slot drops like the rest of the inventory. */
    public static void dropAll(ServerPlayer player) {
        ItemStack stack = get(player);
        clear(player);
        if (!stack.isEmpty()) player.drop(stack, true, false);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            ItemStack stack = get(player);
            if (stack.isEmpty()) continue;
            Set<Holder<MobEffect>> buffs = buffs(stack);
            if (buffs.isEmpty() || buffs.stream().anyMatch(player::hasEffect)) continue;
            eat(player, stack);
        }
    }

    /** Eats one item from the slot: its consume effects apply, without hunger or saturation. */
    static void eat(ServerPlayer player, ItemStack stack) {
        ItemStack eaten = stack.copyWithCount(1);
        ItemStack rest = stack.copy();
        rest.shrink(1);
        set(player, rest);
        Consumable consumable = eaten.get(DataComponents.CONSUMABLE);
        if (consumable != null) for (ConsumeEffect effect : consumable.onConsumeEffects()) effect.apply(player.level(), eaten, player);
        var remainder = eaten.get(DataComponents.USE_REMAINDER);
        if (remainder != null) player.getInventory().placeItemBackInInventory(remainder.convertInto().create());
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_BURP,
                SoundSource.PLAYERS, 0.5F, player.getRandom().nextFloat() * 0.1F + 0.9F);
        if (player.containerMenu != null) player.containerMenu.broadcastChanges();
    }

    /** The slot as a one-slot container, for the inventory menu. */
    public static Container container(Player player) { return new FoodContainer(player); }

    public static Slot slot(Player player, Container container) { return new FoodSlot(container, player); }

    public static boolean isSlot(Slot slot) { return slot instanceof FoodSlot; }

    private static final class FoodContainer implements Container {
        private final Player player;

        private FoodContainer(Player player) { this.player = player; }

        @Override public int getContainerSize() { return 1; }

        @Override public boolean isEmpty() { return get(player).isEmpty(); }

        @Override public ItemStack getItem(int slot) { return get(player); }

        @Override public ItemStack removeItem(int slot, int amount) {
            ItemStack stored = get(player).copy();
            ItemStack removed = stored.split(amount);
            set(player, stored);
            return removed;
        }

        @Override public ItemStack removeItemNoUpdate(int slot) {
            ItemStack removed = get(player);
            clear(player);
            return removed;
        }

        @Override public void setItem(int slot, ItemStack stack) { set(player, stack); }

        @Override public void setChanged() { }

        @Override public boolean stillValid(Player user) { return user == player; }

        @Override public void clearContent() { clear(player); }
    }

    private static final class FoodSlot extends Slot {
        private final Player player;

        private FoodSlot(Container container, Player player) {
            super(container, 0, SLOT_X, SLOT_Y);
            this.player = player;
        }

        @Override public boolean mayPlace(ItemStack stack) { return fits(stack); }

        /** The creative inventory lays slots out on its own and has no place for this one. */
        @Override public boolean isActive() { return true; }
    }
}
