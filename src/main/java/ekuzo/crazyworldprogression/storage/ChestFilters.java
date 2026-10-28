package ekuzo.crazyworldprogression.storage;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.List;
import java.util.Set;

/**
 * Chest filters: a chest can be limited to some item categories (ItemCategories); then players, hoppers and quicksort
 * can only put items of those categories into it (ChestFilterMixin). A chest without a filter takes everything, and
 * both halves of a double chest share one filter.
 */
public final class ChestFilters {
    /** The allowed categories; no filter means every item type. */
    public static final AttachmentType<List<String>> FILTER = AttachmentRegistry.create(CrazyWorldProgression.id("chest_filter"),
            builder -> builder.persistent(Codec.STRING.listOf()));

    private ChestFilters() { }

    /** The chest's allowed categories, or null when it takes everything. */
    public static Set<String> filter(BlockEntity chest) {
        List<String> filter = chest == null ? null : chest.getAttached(FILTER);
        return filter == null ? null : Set.copyOf(filter);
    }

    public static boolean allows(BlockEntity chest, ItemStack stack) {
        Set<String> filter = filter(chest);
        return filter == null || ItemCategories.in(stack, filter);
    }

    /** Sets the filter of a chest and its other half (null or every category: no filter). */
    static void set(Level level, BlockPos pos, List<String> categories) {
        boolean all = categories == null || categories.containsAll(ItemCategories.tabs().stream().map(ItemCategories::id).toList());
        for (BlockPos at : halves(level, pos)) {
            if (!(level.getBlockEntity(at) instanceof ChestBlockEntity chest)) continue;
            if (all) chest.removeAttached(FILTER);
            else chest.setAttached(FILTER, List.copyOf(categories));
            chest.setChanged();
        }
    }

    static List<BlockPos> halves(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            return List.of(pos, ChestBlock.getConnectedBlockPos(pos, state));
        return List.of(pos);
    }
}
