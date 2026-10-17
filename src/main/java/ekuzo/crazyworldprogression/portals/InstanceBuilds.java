package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
 * Places pocket templates into instance slots a slice at a time. All builds together place at most
 * BLOCKS_PER_TICK blocks per server tick, split evenly between the builds that are running.
 */
public final class InstanceBuilds {
    /** Shared by every instance being built at the same time. */
    public static final int BLOCKS_PER_TICK = 100_000;
    private static final Map<MinecraftServer, Map<String, Job>> JOBS = new WeakHashMap<>();

    private InstanceBuilds() { }

    private static final class Job {
        final ServerLevel level;
        final BlockPos origin;
        final List<StructureTemplate.StructureBlockInfo> blocks;
        final Runnable done;
        int next;

        Job(ServerLevel level, BlockPos origin, List<StructureTemplate.StructureBlockInfo> blocks, Runnable done) {
            this.level = level; this.origin = origin; this.blocks = blocks; this.done = done;
        }
    }

    public static void initialize() {
        ServerTickEvents.START_SERVER_TICK.register(InstanceBuilds::tick);
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

    /** 0-100 while building, 100 when not building. */
    public static int percent(MinecraftServer server, String key) {
        Map<String, Job> jobs = JOBS.get(server);
        Job job = jobs == null ? null : jobs.get(key);
        return job == null ? 100 : percent(job.next, job.blocks.size());
    }

    static int percent(int placed, int total) {
        return total <= 0 ? 100 : (int) Math.min(99, (long) placed * 100L / total);
    }

    /**
     * Splits the tick's block budget between builds that still need blocks. Leftover from a build that needs
     * less than its share goes to the others, so the whole budget is used whenever enough work is queued.
     */
    static int[] split(int budget, int[] remaining) {
        int[] shares = new int[remaining.length];
        int left = budget;
        int open = 0;
        for (int need : remaining) if (need > 0) open++;
        while (left > 0 && open > 0) {
            int share = Math.max(1, left / open);
            int handed = 0;
            open = 0;
            for (int index = 0; index < remaining.length && handed < left; index++) {
                int need = remaining[index] - shares[index];
                if (need <= 0) continue;
                int give = Math.min(Math.min(share, need), left - handed);
                shares[index] += give;
                handed += give;
                if (remaining[index] - shares[index] > 0) open++;
            }
            if (handed == 0) break;
            left -= handed;
        }
        return shares;
    }

    private static void tick(MinecraftServer server) {
        Map<String, Job> jobs = JOBS.get(server);
        if (jobs == null || jobs.isEmpty()) return;
        List<Map.Entry<String, Job>> running = new ArrayList<>(jobs.entrySet());
        int[] remaining = new int[running.size()];
        for (int index = 0; index < running.size(); index++) {
            Job job = running.get(index).getValue();
            remaining[index] = job.blocks.size() - job.next;
        }
        int[] shares = split(BLOCKS_PER_TICK, remaining);
        for (int index = 0; index < running.size(); index++) {
            String key = running.get(index).getKey();
            Job job = running.get(index).getValue();
            try {
                place(job, shares[index]);
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.error("Building instance {} failed", key, exception);
                jobs.remove(key);
                continue;
            }
            if (job.next >= job.blocks.size() && jobs.get(key) == job) {
                jobs.remove(key);
                try {
                    job.done.run();
                } catch (RuntimeException exception) {
                    CrazyWorldProgression.LOGGER.error("Finishing instance {} failed", key, exception);
                }
            }
        }
    }

    private static void place(Job job, int count) {
        // Same flags as the previous one-shot placement: no neighbour shape updates and no drops while the
        // template is still incomplete, so doors and attached blocks survive.
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        int end = Math.min(job.blocks.size(), job.next + count);
        for (; job.next < end; job.next++) {
            StructureTemplate.StructureBlockInfo info = job.blocks.get(job.next);
            BlockPos pos = job.origin.offset(info.pos());
            CompoundTag nbt = info.nbt();
            if (nbt != null) job.level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 820);
            if (!job.level.setBlock(pos, info.state(), flags) || nbt == null) continue;
            BlockEntity blockEntity = job.level.getBlockEntity(pos);
            if (blockEntity == null) continue;
            CompoundTag data = nbt.copy();
            if (blockEntity instanceof RandomizableContainer) data.putLong("LootTableSeed", job.level.getRandom().nextLong());
            blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, job.level.registryAccess(), data));
        }
    }
}
