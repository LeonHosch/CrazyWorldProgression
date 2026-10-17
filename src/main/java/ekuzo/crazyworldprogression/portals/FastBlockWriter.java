package ekuzo.crazyworldprogression.portals;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.EnumSet;

/**
 * Writes blocks straight into chunk sections, the way world generation fills chunks, instead of going through
 * Level.setBlock for every block. It skips neighbour updates, per-block heightmap updates and per-block change
 * packets. Lighting is queued for the light thread only where light properties change, heightmaps are rebuilt
 * once per touched chunk in {@link #finish()}, and players already in those chunks get the finished chunks.
 */
final class FastBlockWriter {
    private static final EnumSet<Heightmap.Types> HEIGHTMAPS = EnumSet.of(Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    private final ServerLevel level;
    private final LevelLightEngine light;
    private final LongOpenHashSet touched = new LongOpenHashSet();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private LevelChunk chunk;
    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;

    FastBlockWriter(ServerLevel level) {
        this.level = level;
        this.light = level.getChunkSource().getLightEngine();
    }

    void place(int x, int y, int z, BlockState state, CompoundTag nbt) {
        int cx = x >> 4, cz = z >> 4;
        if (chunk == null || cx != chunkX || cz != chunkZ) {
            chunk = level.getChunk(cx, cz);
            chunkX = cx;
            chunkZ = cz;
            touched.add(ChunkPos.pack(cx, cz));
        }
        int sectionIndex = chunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return;
        LevelChunkSection section = chunk.getSection(sectionIndex);
        boolean wasEmpty = section.hasOnlyAir();
        BlockState old = section.setBlockState(x & 15, y & 15, z & 15, state, false);
        if (old == state && nbt == null) return;
        cursor.set(x, y, z);
        if (wasEmpty != section.hasOnlyAir()) light.updateSectionStatus(SectionPos.of(cursor), section.hasOnlyAir());
        if (LightEngine.hasDifferentLightProperties(old, state)) light.checkBlock(cursor);
        if (old.hasBlockEntity() && old != state) chunk.removeBlockEntity(cursor);
        if (state.hasBlockEntity()) {
            BlockEntity blockEntity = chunk.getBlockEntity(cursor.immutable(), LevelChunk.EntityCreationType.IMMEDIATE);
            if (blockEntity != null && nbt != null) {
                CompoundTag data = nbt.copy();
                if (blockEntity instanceof RandomizableContainer) data.putLong("LootTableSeed", level.getRandom().nextLong());
                blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
            }
        }
        if (old != state && (PoiTypes.forState(old).isPresent() || PoiTypes.forState(state).isPresent()))
            level.updatePOIOnBlockStateChange(cursor.immutable(), old, state);
    }

    /** Heightmaps, saving and sending the finished chunks to anyone already in them. */
    void finish() {
        for (LongIterator iterator = touched.iterator(); iterator.hasNext(); ) {
            long key = iterator.nextLong();
            LevelChunk finished = level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            Heightmap.primeHeightmaps(finished, HEIGHTMAPS);
            finished.markUnsaved();
            var players = level.getChunkSource().chunkMap.getPlayers(finished.getPos(), false);
            if (players.isEmpty()) continue;
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(finished, light, null, null);
            for (ServerPlayer player : players) player.connection.send(packet);
        }
        touched.clear();
        chunk = null;
    }
}
