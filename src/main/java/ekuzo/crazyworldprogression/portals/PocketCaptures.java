package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.cleanup.TickBudget;
import ekuzo.crazyworldprogression.mixin.StructureTemplateAccessor;
import ekuzo.crazyworldprogression.mixin.StructureTemplateManagerAccessor;
import ekuzo.crazyworldprogression.mixin.StructureTemplatePaletteInvoker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.TagValueOutput;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Saves a pocket from its workshop over several ticks instead of in one step:
 * <ol>
 *   <li>scan: find the build's bounds and a safe entry point, one chunk at a time;</li>
 *   <li>collect: read the blocks inside the bounds (air is left out), one section at a time;</li>
 *   <li>write: sort the blocks and write the structure file off the server thread;</li>
 *   <li>finish: on the server thread, put the new template in Minecraft's cache and record its metadata.</li>
 * </ol>
 * Steps 1 and 2 run within instance creation's share of the tick budget. Chunks are loaded in the background
 * ahead of time. Blocks changed while a save runs may or may not be in it; the next automatic save catches them.
 */
public final class PocketCaptures {
    private static final int PREFETCH = 16;
    private static final Map<MinecraftServer, Map<Identifier, Job>> JOBS = new WeakHashMap<>();

    private PocketCaptures() { }

    private enum Phase { SCAN, COLLECT, WRITE }

    private static final class Job {
        final MinecraftServer server;
        final ServerLevel level;
        final Identifier id;
        final String author;
        final Consumer<PortalTemplateService.TemplateInfo> done;
        final Consumer<RuntimeException> failed;
        final long startTick;
        final long startNanos = System.nanoTime();
        final long[] scanChunks;
        final PocketEditorService.BuildScanner scanner;
        Phase phase = Phase.SCAN;
        int scanIndex;
        PocketEditorService.BuildScan bounds;
        long[] columns;
        long unit;
        int sectionsY, firstSectionY;
        final List<StructureTemplate.StructureBlockInfo> full = new ArrayList<>();
        final List<StructureTemplate.StructureBlockInfo> other = new ArrayList<>();
        final List<StructureTemplate.StructureBlockInfo> special = new ArrayList<>();
        CompletableFuture<StructureTemplate> writing;

        Job(MinecraftServer server, ServerLevel level, Identifier id, BlockPos preferred, String author,
            Consumer<PortalTemplateService.TemplateInfo> done, Consumer<RuntimeException> failed) {
            this.server = server; this.level = level; this.id = id; this.author = author;
            this.done = done; this.failed = failed;
            this.startTick = server.getTickCount();
            this.scanChunks = PocketEditorService.scanChunks(server, level, id, preferred).stream()
                    .mapToLong(Long::longValue).toArray();
            this.scanner = new PocketEditorService.BuildScanner(preferred);
        }
    }

    static void initialize() {
        ServerLifecycleEvents.SERVER_STOPPED.register(JOBS::remove);
    }

    public static int count(MinecraftServer server) {
        Map<Identifier, Job> jobs = JOBS.get(server);
        return jobs == null ? 0 : jobs.size();
    }

    public static boolean capturing(MinecraftServer server, Identifier id) {
        Map<Identifier, Job> jobs = JOBS.get(server);
        return jobs != null && jobs.containsKey(id);
    }

    /**
     * Starts saving a pocket from its workshop. done or failed runs on the server thread when it is finished.
     * Checks that can fail right away (wrong dimension, pocket being prepared or already saving) throw here.
     */
    public static void start(MinecraftServer server, ServerLevel level, Identifier id, BlockPos preferred, String author,
                             Consumer<PortalTemplateService.TemplateInfo> done, Consumer<RuntimeException> failed) {
        if (capturing(server, id)) throw new IllegalStateException("This pocket is already being saved");
        if (PocketEditorService.preparing(server, id))
            throw new IllegalStateException("This pocket's workshop is still being prepared");
        Job job = new Job(server, level, id, preferred, author, done, failed);
        JOBS.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(id, job);
    }

    /** Saves in one step, for server shutdown. Returns the result or throws. */
    public static PortalTemplateService.TemplateInfo runNow(MinecraftServer server, ServerLevel level, Identifier id,
                                                           BlockPos preferred, String author) {
        PortalTemplateService.TemplateInfo[] result = new PortalTemplateService.TemplateInfo[1];
        RuntimeException[] error = new RuntimeException[1];
        Job job = new Job(server, level, id, preferred, author, info -> result[0] = info, exception -> error[0] = exception);
        JOBS.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(id, job);
        complete(job);
        if (error[0] != null) throw error[0];
        return result[0];
    }

    /** Finishes every running save in one step, for server shutdown. */
    public static void finishAll(MinecraftServer server) {
        Map<Identifier, Job> jobs = JOBS.get(server);
        if (jobs == null) return;
        for (Job job : List.copyOf(jobs.values())) complete(job);
    }

    private static void complete(Job job) {
        try {
            while (job.phase != Phase.WRITE) step(job, Long.MAX_VALUE, true);
            if (job.writing == null) startWriting(job);
            job.writing.join();
            finish(job);
        } catch (RuntimeException exception) {
            fail(job, exception);
        }
    }

    /** Called from the portal tick, after instance building. */
    static void tick(MinecraftServer server) {
        Map<Identifier, Job> jobs = JOBS.get(server);
        if (jobs == null || jobs.isEmpty()) return;
        for (Job job : List.copyOf(jobs.values())) {
            try {
                if (job.phase == Phase.WRITE) {
                    if (job.writing == null) startWriting(job);
                    if (job.writing.isDone()) {
                        job.writing.join();
                        finish(job);
                    }
                    continue;
                }
                if (!TickBudget.available(server, TickBudget.Priority.CREATION)) continue;
                try (TickBudget.Slice slice = TickBudget.start(server, TickBudget.Priority.CREATION)) {
                    while (job.phase != Phase.WRITE && slice.hasTime()) {
                        if (!step(job, 1, false)) break;
                    }
                }
            } catch (RuntimeException exception) {
                fail(job, exception);
            }
        }
    }

    /** Does up to count units of the current phase. Returns false when it has to wait for a chunk to load. */
    private static boolean step(Job job, long count, boolean blocking) {
        for (long done = 0; done < count && job.phase != Phase.WRITE; done++) {
            if (job.phase == Phase.SCAN) {
                if (job.scanIndex >= job.scanChunks.length) {
                    startCollect(job);
                    continue;
                }
                for (int ahead = job.scanIndex; ahead < Math.min(job.scanChunks.length, job.scanIndex + PREFETCH); ahead++)
                    FastBlockWriter.prefetch(job.level, ChunkPos.getX(job.scanChunks[ahead]), ChunkPos.getZ(job.scanChunks[ahead]));
                long packed = job.scanChunks[job.scanIndex];
                if (!blocking && !FastBlockWriter.ready(job.level, ChunkPos.getX(packed), ChunkPos.getZ(packed))) return false;
                job.scanner.scan(job.level, job.level.getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed)));
                job.scanIndex++;
            } else {
                long units = (long) job.columns.length * job.sectionsY;
                if (job.unit >= units) {
                    job.phase = Phase.WRITE;
                    continue;
                }
                int column = (int) (job.unit / job.sectionsY);
                if (job.unit % job.sectionsY == 0) {
                    for (int ahead = column; ahead < Math.min(job.columns.length, column + PREFETCH); ahead++)
                        FastBlockWriter.prefetch(job.level, ChunkPos.getX(job.columns[ahead]), ChunkPos.getZ(job.columns[ahead]));
                }
                long packed = job.columns[column];
                if (!blocking && !FastBlockWriter.ready(job.level, ChunkPos.getX(packed), ChunkPos.getZ(packed))) return false;
                collectSection(job, job.level.getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed)),
                        job.firstSectionY + (int) (job.unit % job.sectionsY));
                job.unit++;
            }
        }
        return true;
    }

    private static void startCollect(Job job) {
        job.bounds = job.scanner.result();
        BlockPos first = job.bounds.first(), last = job.bounds.last();
        List<Long> columns = new ArrayList<>();
        for (int cz = first.getZ() >> 4; cz <= last.getZ() >> 4; cz++)
            for (int cx = first.getX() >> 4; cx <= last.getX() >> 4; cx++) columns.add(ChunkPos.pack(cx, cz));
        job.columns = columns.stream().mapToLong(Long::longValue).toArray();
        job.firstSectionY = first.getY() >> 4;
        job.sectionsY = (last.getY() >> 4) - job.firstSectionY + 1;
        job.phase = Phase.COLLECT;
    }

    /** Reads the non-air blocks of one section that lie inside the bounds, like Minecraft's own capture. */
    private static void collectSection(Job job, LevelChunk chunk, int sectionY) {
        int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return;
        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section.hasOnlyAir()) return;
        BlockPos first = job.bounds.first(), last = job.bounds.last();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ(), baseY = sectionY << 4;
        int x0 = Math.max(first.getX(), baseX), x1 = Math.min(last.getX(), baseX + 15);
        int y0 = Math.max(first.getY(), baseY), y1 = Math.min(last.getY(), baseY + 15);
        int z0 = Math.max(first.getZ(), baseZ), z1 = Math.min(last.getZ(), baseZ + 15);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                    if (state.isAir()) continue;
                    BlockPos relative = new BlockPos(x - first.getX(), y - first.getY(), z - first.getZ());
                    StructureTemplate.StructureBlockInfo info;
                    BlockEntity blockEntity = state.hasBlockEntity() ? chunk.getBlockEntity(new BlockPos(x, y, z)) : null;
                    if (blockEntity != null) {
                        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(CrazyWorldProgression.LOGGER)) {
                            TagValueOutput output = TagValueOutput.createWithContext(reporter, job.level.registryAccess());
                            blockEntity.saveWithId(output);
                            info = new StructureTemplate.StructureBlockInfo(relative, state, output.buildResult());
                        }
                    } else {
                        info = new StructureTemplate.StructureBlockInfo(relative, state, null);
                    }
                    StructureTemplateAccessor.cwp$addToLists(info, job.full, job.other, job.special);
                }
    }

    /** Sorting the blocks, building the NBT and compressing it to disk all happen off the server thread. */
    private static void startWriting(Job job) {
        BlockPos first = job.bounds.first(), last = job.bounds.last();
        Vec3i size = new Vec3i(last.getX() - first.getX() + 1, last.getY() - first.getY() + 1, last.getZ() - first.getZ() + 1);
        StructureTemplateManager manager = job.server.getStructureManager();
        Path path = manager.worldTemplates().createAndValidatePathToStructure(job.id, StructureTemplateManager.WORLD_STRUCTURE_LISTER);
        List<StructureTemplate.StructureBlockInfo> full = job.full, other = job.other, special = job.special;
        String author = job.author;
        job.writing = CompletableFuture.supplyAsync(() -> {
            StructureTemplate template = new StructureTemplate();
            List<StructureTemplate.StructureBlockInfo> blocks = StructureTemplateAccessor.cwp$buildInfoList(full, other, special);
            ((StructureTemplateAccessor) template).cwp$palettes().add(StructureTemplatePaletteInvoker.cwp$create(blocks));
            ((StructureTemplateAccessor) template).cwp$setSize(size);
            template.setAuthor(author);
            try {
                if (!StructureTemplateManager.save(path, template, false))
                    throw new IllegalStateException("Minecraft could not save structure template " + job.id);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Could not write structure template " + job.id + ": " + exception.getMessage(), exception);
            }
            return template;
        });
    }

    private static void finish(Job job) {
        StructureTemplate template = job.writing.join();
        remove(job);
        if (!PocketEditorService.exists(job.server, job.id)) return;
        // Builds that already started keep the previous version; new ones use this one.
        ((StructureTemplateManagerAccessor) job.server.getStructureManager()).cwp$repository().put(job.id, Optional.of(template));
        BlockPos first = job.bounds.first(), last = job.bounds.last();
        Vec3i size = new Vec3i(last.getX() - first.getX() + 1, last.getY() - first.getY() + 1, last.getZ() - first.getZ() + 1);
        PortalTemplateService.TemplateInfo info = PortalTemplateService.storeCaptured(job.server, job.id, size, first,
                job.bounds.spawn(), job.author);
        PocketEditorService.savedAt(job.server, job.id, job.startTick);
        CrazyWorldProgression.LOGGER.info("Saved pocket {}: {} blocks in {} x {} x {}, {} s", job.id,
                String.format("%,d", job.full.size() + job.other.size() + job.special.size()), size.getX(), size.getY(),
                size.getZ(), String.format("%.1f", (System.nanoTime() - job.startNanos) / 1e9));
        job.done.accept(info);
    }

    private static void fail(Job job, RuntimeException exception) {
        remove(job);
        RuntimeException cause = exception instanceof java.util.concurrent.CompletionException
                && exception.getCause() instanceof RuntimeException runtime ? runtime : exception;
        job.failed.accept(cause);
    }

    private static void remove(Job job) {
        Map<Identifier, Job> jobs = JOBS.get(job.server);
        if (jobs != null) jobs.remove(job.id, job);
    }
}
