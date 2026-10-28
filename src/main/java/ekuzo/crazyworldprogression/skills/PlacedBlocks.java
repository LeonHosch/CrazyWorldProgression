package ekuzo.crazyworldprogression.skills;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ores and crop fruits that players placed themselves, so breaking them again gives no skill XP (no silk-touch or
 * place-and-break farming). Only blocks that would give XP are recorded, per dimension.
 */
final class PlacedBlocks extends SavedData {
    static final Codec<PlacedBlocks> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf()).optionalFieldOf("placed", Map.of())
                    .forGetter(data -> {
                        Map<String, List<Long>> out = new HashMap<>();
                        data.placed.forEach((dimension, set) -> out.put(dimension, List.copyOf(set)));
                        return out;
                    })
    ).apply(instance, PlacedBlocks::new));
    private static final SavedDataType<PlacedBlocks> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("skill_placed_blocks"), PlacedBlocks::new, CODEC, null);
    private final Map<String, Set<Long>> placed = new HashMap<>();

    private PlacedBlocks() { }

    private PlacedBlocks(Map<String, List<Long>> placed) {
        placed.forEach((dimension, list) -> this.placed.put(dimension, new HashSet<>(list)));
    }

    static PlacedBlocks get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    void add(ServerLevel level, BlockPos pos) {
        if (placed.computeIfAbsent(level.dimension().identifier().toString(), ignored -> new HashSet<>()).add(pos.asLong()))
            setDirty();
    }

    /** Whether the block was placed by a player (keeps the mark). */
    boolean contains(ServerLevel level, BlockPos pos) {
        Set<Long> set = placed.get(level.dimension().identifier().toString());
        return set != null && set.contains(pos.asLong());
    }

    /** Removes the mark; true when the block had been placed by a player. */
    boolean remove(ServerLevel level, BlockPos pos) {
        Set<Long> set = placed.get(level.dimension().identifier().toString());
        if (set == null || !set.remove(pos.asLong())) return false;
        setDirty();
        return true;
    }
}
