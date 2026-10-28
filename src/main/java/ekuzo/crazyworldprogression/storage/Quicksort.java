package ekuzo.crazyworldprogression.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.social.ChestLocks;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Quicksort: a player picks item categories, a mode and switches it on; then once a second the items of those
 * categories in their main inventory (never the hotbar, worn or special slots, nor locked items, ItemLocks) go into the chests they may open within {@link #RADIUS} blocks
 * whose filter shares a category with them. Stack mode only adds to chests that already hold that item; fill mode
 * puts every item its filter allows into it, but never into chests without a filter. Items fly into the chest and its
 * lid opens without a sound (StorageNetworking).
 */
public final class Quicksort {
    public static final double RADIUS = 5.0;

    /** A player's quicksort: categories, fill mode (or stack only), and whether it runs. */
    public record Settings(List<String> categories, boolean fill, boolean on) {
        static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().optionalFieldOf("categories", List.of()).forGetter(Settings::categories),
                Codec.BOOL.optionalFieldOf("fill", false).forGetter(Settings::fill),
                Codec.BOOL.optionalFieldOf("on", false).forGetter(Settings::on)
        ).apply(instance, Settings::new));
        static final Settings DEFAULT = new Settings(List.of(), false, false);
    }

    public static final AttachmentType<Settings> SETTINGS = AttachmentRegistry.create(CrazyWorldProgression.id("quicksort"),
            builder -> builder.persistent(Settings.CODEC).copyOnDeath().initializer(() -> Settings.DEFAULT));

    private Quicksort() { }

    static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Settings settings = player.getAttachedOrCreate(SETTINGS);
                if (settings.on() && !settings.categories().isEmpty() && !player.isSpectator()) run(player, settings);
            }
        });
    }

    /** One pass for one player. */
    static void run(ServerPlayer player, Settings settings) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Set<String> wanted = Set.copyOf(settings.categories());
        BlockPos center = player.blockPosition();
        int reach = (int) Math.ceil(RADIUS);
        Set<BlockPos> done = new HashSet<>();
        int moved = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-reach, -reach, -reach), center.offset(reach, reach, reach))) {
            if (pos.distToCenterSqr(player.getX(), player.getY() + 1, player.getZ()) > RADIUS * RADIUS) continue;
            if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest) || done.contains(pos)) continue;
            BlockPos immutable = pos.immutable();
            done.addAll(ChestFilters.halves(level, immutable));
            if (!ChestLocks.canAccess(player, level, immutable)) continue;
            Set<String> filter = ChestFilters.filter(chest);
            if (settings.fill() && filter == null) continue;
            if (filter != null && filter.stream().noneMatch(wanted::contains)) continue;
            Container container = ChestBlock.getContainer((ChestBlock) chest.getBlockState().getBlock(), chest.getBlockState(), level, immutable, true);
            if (container == null) continue;
            List<ItemStack> put = new ArrayList<>();
            for (int slot = 9; slot < 36; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty() || ItemLocks.locked(stack) || !ItemCategories.in(stack, wanted) || !ChestFilters.allows(chest, stack)) continue;
                if (!settings.fill() && !contains(container, stack)) continue;
                // Copied first: a stack that goes in whole is empty afterwards, and an empty copy can't be sent.
                ItemStack original = stack.copy();
                insert(container, stack);
                if (stack.getCount() < original.getCount()) put.add(original.copyWithCount(original.getCount() - stack.getCount()));
            }
            if (put.isEmpty()) continue;
            moved += put.stream().mapToInt(ItemStack::getCount).sum();
            container.setChanged();
            StorageNetworking.showSort(player, level, immutable, put);
        }
        if (moved > 0) {
            player.containerMenu.broadcastChanges();
            player.sendOverlayMessage(Component.literal("Quicksort: " + moved + " item(s) put away").withStyle(ChatFormatting.GREEN));
        }
    }

    static boolean contains(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize(); slot++)
            if (ItemStack.isSameItemSameComponents(container.getItem(slot), stack)) return true;
        return false;
    }

    /** Onto matching stacks first, then into empty slots; what does not fit stays in {@code stack}. */
    static void insert(Container container, ItemStack stack) {
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++)
            for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                ItemStack there = container.getItem(slot);
                if (!container.canPlaceItem(slot, stack)) continue;
                if (pass == 0 && !there.isEmpty() && ItemStack.isSameItemSameComponents(there, stack)) {
                    int room = Math.min(there.getMaxStackSize(), container.getMaxStackSize()) - there.getCount();
                    int amount = Math.min(room, stack.getCount());
                    if (amount > 0) {
                        there.grow(amount);
                        stack.shrink(amount);
                    }
                } else if (pass == 1 && there.isEmpty()) {
                    container.setItem(slot, stack.split(Math.min(stack.getCount(), stack.getMaxStackSize())));
                }
            }
    }
}
