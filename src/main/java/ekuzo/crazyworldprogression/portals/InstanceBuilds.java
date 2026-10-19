package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.cleanup.TickBudget;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * Places pocket templates into instance slots a slice at a time, within the instance-creation share of the
 * adjusting {@link TickBudget}. Several builds take turns in small chunks, so they progress together.
 */
public final class InstanceBuilds {
    /** Blocks a build places before the next build gets its turn. */
    static final int TURN = 1_024;
    private static final Map<MinecraftServer, Map<String, Job>> JOBS = new WeakHashMap<>();

    private InstanceBuilds() { }

    private static final class Job {
        final ServerLevel level;
        final BlockPos origin;
        final List<StructureTemplate.StructureBlockInfo> blocks;
        final Runnable done;
        /** Instance builds write straight into chunk sections, like world generation. */
        final FastBlockWriter writer;
        /** Template blocks grouped chunk by chunk (bottom-up inside a chunk), worked out off the server thread. */
        final CompletableFuture<int[]> order;
        /** Waiting for its template to be read from disk; replaced by the real job once it is. */
        boolean held;
        int next;

        Job(ServerLevel level, BlockPos origin, List<StructureTemplate.StructureBlockInfo> blocks,
            Consumer<LevelChunk> chunkDone, Runnable done) {
            this.level = level; this.origin = origin; this.blocks = blocks; this.done = done;
            this.writer = new FastBlockWriter(level, chunkDone);
            this.order = CompletableFuture.supplyAsync(() -> chunkOrder(blocks.size(),
                    index -> (origin.getX() + blocks.get(index).pos().getX()) >> 4,
                    index -> (origin.getZ() + blocks.get(index).pos().getZ()) >> 4));
        }

        void placeNext() {
            StructureTemplate.StructureBlockInfo info = blocks.get(order.join()[next++]);
            writer.place(origin.getX() + info.pos().getX(), origin.getY() + info.pos().getY(),
                    origin.getZ() + info.pos().getZ(), info.state(), info.nbt());
        }
    }

    /**
     * Indices 0..count-1 grouped by chunk column, keeping their original order inside each column (a stable
     * counting sort). Writing chunk by chunk finishes every chunk in one go instead of revisiting all of them
     * for every layer.
     */
    static int[] chunkOrder(int count, IntUnaryOperator chunkX, IntUnaryOperator chunkZ) {
        int[] order = new int[count];
        if (count == 0) return order;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int index = 0; index < count; index++) {
            int x = chunkX.applyAsInt(index), z = chunkZ.applyAsInt(index);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        int width = maxX - minX + 1;
        int[] start = new int[width * (maxZ - minZ + 1) + 1];
        for (int index = 0; index < count; index++)
            start[(chunkZ.applyAsInt(index) - minZ) * width + chunkX.applyAsInt(index) - minX + 1]++;
        for (int column = 1; column < start.length; column++) start[column] += start[column - 1];
        for (int index = 0; index < count; index++)
            order[start[(chunkZ.applyAsInt(index) - minZ) * width + chunkX.applyAsInt(index) - minX]++] = index;
        return order;
    }

    public static void initialize() {
        FastBlockWriter.initialize();
        ServerLifecycleEvents.SERVER_STOPPED.register(JOBS::remove);
    }

    /** Queues a build for a slot; a newer build for the same slot replaces an older one. */
    static void start(MinecraftServer server, String key, ServerLevel level, BlockPos origin,
                      List<StructureTemplate.StructureBlockInfo> blocks, Consumer<LevelChunk> chunkDone, Runnable done) {
        JOBS.computeIfAbsent(server, ignored -> new LinkedHashMap<>())
                .put(key, new Job(level, origin, blocks, chunkDone, done));
    }

    /** Marks a slot's build as waiting (counts as building, does nothing) until start replaces it. */
    static void hold(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        Job job = jobs == null ? null : jobs.get(key);
        if (job != null) job.held = true;
    }

    static void cancel(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        if (jobs != null) jobs.remove(key);
    }

    /** Running builds and the blocks they still have to place, for the performance view. */
    public static int pending(MinecraftServer server) {
        Map<String, Job> jobs = JOBS.get(server);
        return jobs == null ? 0 : jobs.size();
    }

    public static long remainingBlocks(MinecraftServer server) {
        Map<String, Job> jobs = JOBS.get(server);
        return jobs == null ? 0 : jobs.values().stream().mapToLong(job -> job.blocks.size() - job.next).sum();
    }

    public static boolean building(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        return jobs != null && jobs.containsKey(key);
    }

    /** True while the build still waits for its pocket to be read from disk. */
    public static boolean loading(MinecraftServer server, String key) {
        Job job = job(server, key);
        return job != null && (job.held || !job.order.isDone());
    }

    /** Fraction of blocks placed, 0 to 1 (1 when not building). */
    public static double progress(MinecraftServer server, String key) {
        Job job = job(server, key);
        return job == null || job.blocks.isEmpty() ? 1.0 : Math.min(0.999, (double) job.next / job.blocks.size());
    }

    public static long placed(MinecraftServer server, String key) {
        Job job = job(server, key);
        return job == null ? 0 : job.next;
    }

    public static long total(MinecraftServer server, String key) {
        Job job = job(server, key);
        return job == null ? 0 : job.blocks.size();
    }

    private static Job job(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        return jobs == null ? null : jobs.get(key);
    }

    /** 0-99 while building, 100 when not building. */
    public static int percent(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        Job job = jobs == null ? null : jobs.get(key);
        if (job == null) return 100;
        return job.held ? 0 : percent(job.next, job.blocks.size());
    }

    static int percent(int placed, int total) {
        return total <= 0 ? 100 : (int) Math.min(99, (long) placed * 100L / total);
    }

    /** Called from the portal tick after scripts have had their turn. */
    static void tick(MinecraftServer server) {
        Map<String, Job> jobs = JOBS.get(server);
        if (jobs == null || jobs.isEmpty()) return;
        List<String> finished = new ArrayList<>();
        try (TickBudget.Slice slice = TickBudget.start(server, TickBudget.Priority.CREATION)) {
            boolean progress = true;
            int done = 0;
            while (progress && slice.hasTime()) {
                progress = false;
                for (Map.Entry<String, Job> entry : jobs.entrySet()) {
                    Job job = entry.getValue();
                    if (job.held) continue;
                    if (job.next >= job.blocks.size()) {
                        finished.add(entry.getKey());
                        continue;
                    }
                    // Still sorting the template off-thread; other builds go ahead meanwhile.
                    if (!job.order.isDone()) continue;
                    int end = Math.min(job.blocks.size(), job.next + TURN);
                    long turnStart = System.nanoTime();
                    try {
                        while (job.next < end && slice.more(done++)) job.placeNext();
                    } catch (RuntimeException exception) {
                        CrazyWorldProgression.LOGGER.error("Building instance {} failed", entry.getKey(), exception);
                        job.next = job.blocks.size();
                    }
                    job.writer.worked(System.nanoTime() - turnStart);
                    if (job.next >= job.blocks.size()) finished.add(entry.getKey());
                    else progress = true;
                    if (!slice.hasTime()) break;
                }
            }
        }
        for (String key : finished) {
            Job job = jobs.remove(key);
            if (job == null) continue;
            try {
                job.writer.finish();
                CrazyWorldProgression.LOGGER.info("Built instance {}: {}", key, job.writer.stats());
                job.done.run();
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.error("Finishing instance {} failed", key, exception);
            }
        }
    }

    /**
     * Places one template block like Minecraft's own structure placement, without neighbour shape updates or
     * drops while the build is incomplete, so doors and attached blocks survive. Block entities keep their data.
     */
    public static void placeOne(ServerLevel level, BlockPos origin, StructureTemplate.StructureBlockInfo info) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        BlockPos pos = origin.offset(info.pos());
        CompoundTag nbt = info.nbt();
        if (nbt != null) level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 820);
        if (!level.setBlock(pos, info.state(), flags) || nbt == null) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return;
        CompoundTag data = nbt.copy();
        if (blockEntity instanceof RandomizableContainer) data.putLong("LootTableSeed", level.getRandom().nextLong());
        blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
    }
}
