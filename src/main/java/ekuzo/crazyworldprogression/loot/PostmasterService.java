package ekuzo.crazyworldprogression.loot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Mailbox storage and delivery for loot that does not fit in player inventory. */
public final class PostmasterService {
    public static final int SLOTS = 20;
    private static final Map<MinecraftServer, Map<UUID, MailContainer>> OPEN = new WeakHashMap<>();

    private PostmasterService() { }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STOPPED.register(OPEN::remove);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Map<UUID, MailContainer> active = OPEN.get(server);
            if (active != null) active.remove(handler.getPlayer().getUUID());
        });
    }

    public static void open(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        MailContainer mail = new MailContainer(server, player.getUUID());
        OPEN.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(), mail);
        int used = occupied(mail.contents());
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new PostmasterMenu(id, inventory, mail),
                Component.literal("Postmaster (" + used + "/" + SLOTS + ")")));
        if (used > SLOTS / 2) player.sendSystemMessage(Component.literal(
                "Your postmaster is more than half full (" + used + "/" + SLOTS + " slots)."));
    }

    static void closed(Player player, Container storage) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Map<UUID, MailContainer> active = OPEN.get(serverPlayer.level().getServer());
        if (active != null && active.get(serverPlayer.getUUID()) == storage)
            active.remove(serverPlayer.getUUID());
    }

    public static DepositResult deposit(ServerPlayer player, ItemStack incoming) {
        if (incoming.isEmpty()) return new DepositResult(0, 0, occupied(slots(player)));
        MinecraftServer server = player.level().getServer();
        MailContainer active = OPEN.computeIfAbsent(server, ignored -> new HashMap<>()).get(player.getUUID());
        List<ItemStack> slots = active == null ? PostmasterData.get(server).slots(player.getUUID())
                : active.contents();
        int before = incoming.getCount();
        ItemStack remainder = insert(slots, incoming);
        int stored = before - remainder.getCount();
        if (stored > 0) {
            if (active == null) PostmasterData.get(server).save(player.getUUID(), slots);
            else active.replace(slots);
        }
        return new DepositResult(stored, remainder.getCount(), occupied(slots));
    }

    public static int usedSlots(ServerPlayer player) { return occupied(slots(player)); }

    private static List<ItemStack> slots(ServerPlayer player) {
        MailContainer active = OPEN.computeIfAbsent(player.level().getServer(), ignored -> new HashMap<>())
                .get(player.getUUID());
        return active == null ? PostmasterData.get(player.level().getServer()).slots(player.getUUID())
                : active.contents();
    }

    /** Mutates an isolated slot list and returns the part that cannot fit. */
    static ItemStack insert(List<ItemStack> slots, ItemStack incoming) {
        if (slots.size() != SLOTS) throw new IllegalArgumentException("Invalid postmaster size");
        ItemStack remaining = incoming.copy();
        for (int i = 0; i < SLOTS && !remaining.isEmpty(); i++) {
            ItemStack current = slots.get(i);
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, remaining)) continue;
            int room = Math.max(0, current.getMaxStackSize() - current.getCount());
            int moved = Math.min(room, remaining.getCount());
            if (moved == 0) continue;
            ItemStack updated = current.copy();
            updated.grow(moved);
            slots.set(i, updated);
            remaining.shrink(moved);
        }
        for (int i = 0; i < SLOTS && !remaining.isEmpty(); i++) {
            if (!slots.get(i).isEmpty()) continue;
            int moved = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            slots.set(i, remaining.copyWithCount(moved));
            remaining.shrink(moved);
        }
        return remaining;
    }

    private static int occupied(List<ItemStack> slots) {
        return (int) slots.stream().filter(stack -> !stack.isEmpty()).count();
    }

    public record DepositResult(int stored, int overflow, int usedSlots) { }

    private static final class MailContainer extends SimpleContainer {
        private final MinecraftServer server;
        private final UUID playerId;
        private boolean ready;

        private MailContainer(MinecraftServer server, UUID playerId) {
            super(27);
            this.server = server;
            this.playerId = playerId;
            List<ItemStack> saved = PostmasterData.get(server).slots(playerId);
            for (int i = 0; i < SLOTS; i++) super.setItem(i, saved.get(i));
            for (int i = SLOTS; i < 27; i++) super.setItem(i, new ItemStack(Items.STAINED_GLASS_PANE.gray()));
            ready = true;
        }

        private List<ItemStack> contents() {
            List<ItemStack> stacks = new ArrayList<>(SLOTS);
            for (int i = 0; i < SLOTS; i++) stacks.add(getItem(i).copy());
            return stacks;
        }

        private void replace(List<ItemStack> stacks) {
            ready = false;
            for (int i = 0; i < SLOTS; i++) super.setItem(i, stacks.get(i).copy());
            ready = true;
            setChanged();
        }

        @Override public void setChanged() {
            super.setChanged();
            if (ready) PostmasterData.get(server).save(playerId, contents());
        }

        @Override public void setItem(int index, ItemStack stack) {
            if (index < SLOTS) super.setItem(index, stack);
        }

        @Override public ItemStack removeItem(int index, int amount) {
            return index < SLOTS ? super.removeItem(index, amount) : ItemStack.EMPTY;
        }

        @Override public ItemStack removeItemNoUpdate(int index) {
            if (index >= SLOTS) return ItemStack.EMPTY;
            ItemStack removed = super.removeItemNoUpdate(index);
            setChanged();
            return removed;
        }

        @Override public boolean canPlaceItem(int index, ItemStack stack) { return false; }
    }
}
