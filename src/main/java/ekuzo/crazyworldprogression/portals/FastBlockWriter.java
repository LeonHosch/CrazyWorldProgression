package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
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
import java.util.function.Consumer;

/**
 * Writes blocks straight into chunk sections, the way world generation fills chunks, instead of going through
 * Level.setBlock for every block. It skips neighbour updates, per-block heightmap updates and per-block change
 * packets. Lighting is queued for the light thread only where light properties change.
 * <p>
 * Callers should write chunk by chunk. Every chunk is held loaded by a short ticket while it is being written
 * and marked for saving as soon as it is touched, so no write is lost when a chunk unloads between ticks. When
 * the writer moves on to another chunk, the previous one is completed: heightmaps are rebuilt and players
 * already in it get the finished chunk.
 */
final class FastBlockWriter {
    private static final EnumSet<Heightmap.Types> HEIGHTMAPS = EnumSet.of(Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    /** Keeps chunks being written loaded; refreshed on every touch and expires 5 seconds after the last one. */
    private static TicketType ticket;
    private final ServerLevel level;
    private final LevelLightEngine light;
    private final LongOpenHashSet touched = new LongOpenHashSet();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final Consumer<LevelChunk> chunkDone;
    private LevelChunk chunk;
    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;
    private int chunkTick = Integer.MIN_VALUE;

    FastBlockWriter(ServerLevel level) {
        this(level, null);
    }

    /** @param chunkDone runs for each chunk once the writer has finished it (may run again if it is revisited) */
    FastBlockWriter(ServerLevel level, Consumer<LevelChunk> chunkDone) {
        this.level = level;
        this.light = level.getChunkSource().getLightEngine();
        this.chunkDone = chunkDone;
    }

    static void initialize() {
        if (ticket == null) ticket = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(CrazyWorldProgression.MOD_ID, "block_work"),
                new TicketType(100L, TicketType.FLAG_LOADING));
    }

    /** The chunk at these chunk coordinates, loaded, held by a ticket and marked for saving. */
    LevelChunk chunk(int cx, int cz) {
        int tick = level.getServer().getTickCount();
        if (chunk != null && cx == chunkX && cz == chunkZ && tick == chunkTick) return chunk;
        boolean sameChunk = chunk != null && cx == chunkX && cz == chunkZ;
        if (chunk != null && !sameChunk) complete(tick == chunkTick ? chunk : level.getChunk(chunkX, chunkZ));
        ChunkPos pos = new ChunkPos(cx, cz);
        if (ticket != null) level.getChunkSource().addTicketWithRadius(ticket, pos, 0);
        // A chunk can unload between ticks, so it is fetched again in every tick instead of kept.
        chunk = level.getChunk(cx, cz);
        chunk.markUnsaved();
        chunkX = cx;
        chunkZ = cz;
        chunkTick = tick;
        touched.add(ChunkPos.pack(cx, cz));
        return chunk;
    }

    void place(int x, int y, int z, BlockState state, CompoundTag nbt) {
        LevelChunk chunk = chunk(x >> 4, z >> 4);
        int sectionIndex = chunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return;
        place(chunk, chunk.getSection(sectionIndex), x, y, z, state, nbt);
    }

    /** Places into a section the caller already has from {@link #chunk(int, int)}. */
    void place(LevelChunk chunk, LevelChunkSection section, int x, int y, int z, BlockState state, CompoundTag nbt) {
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

    /** Heightmaps, the chunk callback, and the finished chunk for anyone already in it. */
    private void complete(LevelChunk finished) {
        touched.remove(finished.getPos().pack());
        Heightmap.primeHeightmaps(finished, HEIGHTMAPS);
        finished.markUnsaved();
        if (chunkDone != null) chunkDone.accept(finished);
        var players = level.getChunkSource().chunkMap.getPlayers(finished.getPos(), false);
        if (players.isEmpty()) return;
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(finished, light, null, null);
        for (ServerPlayer player : players) player.connection.send(packet);
    }

    /** Completes every chunk that is not completed yet. */
    void finish() {
        long[] remaining = touched.toLongArray();
        for (long key : remaining) complete(level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key)));
        touched.clear();
        chunk = null;
    }
}
