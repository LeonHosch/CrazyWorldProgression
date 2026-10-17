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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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
        int next;

        Job(ServerLevel level, BlockPos origin, List<StructureTemplate.StructureBlockInfo> blocks, Runnable done) {
            this.level = level; this.origin = origin; this.blocks = blocks; this.done = done;
            this.writer = new FastBlockWriter(level);
        }

        void placeNext() {
            StructureTemplate.StructureBlockInfo info = blocks.get(next++);
            writer.place(origin.getX() + info.pos().getX(), origin.getY() + info.pos().getY(),
                    origin.getZ() + info.pos().getZ(), info.state(), info.nbt());
        }
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STOPPED.register(JOBS::remove);
    }

    /** Queues a build for a slot; a newer build for the same slot replaces an older one. */
    static void start(MinecraftServer server, String key, ServerLevel level, BlockPos origin,
                      List<StructureTemplate.StructureBlockInfo> blocks, Runnable done) {
        JOBS.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(key, new Job(level, origin, blocks, done));
    }

    static void cancel(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        if (jobs != null) jobs.remove(key);
    }

    public static boolean building(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        return jobs != null && jobs.containsKey(key);
    }

    /** 0-99 while building, 100 when not building. */
    public static int percent(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        Job job = jobs == null ? null : jobs.get(key);
        return job == null ? 100 : percent(job.next, job.blocks.size());
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
                    if (job.next >= job.blocks.size()) continue;
                    int end = Math.min(job.blocks.size(), job.next + TURN);
                    try {
                        while (job.next < end && slice.more(done++)) job.placeNext();
                    } catch (RuntimeException exception) {
                        CrazyWorldProgression.LOGGER.error("Building instance {} failed", entry.getKey(), exception);
                        job.next = job.blocks.size();
                    }
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
