package ekuzo.crazyworldprogression.buildings;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.selection.AreaSelectionService;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import ekuzo.crazyworldprogression.portals.PortalRegistry;

/** Capture once, then place or clear copies in a world or pocket instance. */
public final class BuildingService {
    public static final long MAX_VOLUME = 131_072L;
    public static final Identifier SELECTION_OWNER = CrazyWorldProgression.id("building_capture");
    private static final Map<MinecraftServer, Map<Identifier, ScheduledClear>> SCHEDULED_CLEARS = new WeakHashMap<>();
    private BuildingService() { }

    public record BuildingInfo(Identifier id, int sizeX, int sizeY, int sizeZ, String author) { }

    public static List<BuildingInfo> buildings(MinecraftServer server) {
        return BuildingData.get(server).values().values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(BuildingService::view).toList();
    }

    public static BuildingInfo building(MinecraftServer server, Identifier id) {
        BuildingData.Building value = BuildingData.get(server).values().get(id.toString());
        return value == null ? null : view(value);
    }

    public static BuildingInfo capture(ServerPlayer player, Identifier id) {
        return capture(player, id, false);
    }

    /** replace: overwrite an existing building's blocks with the current selection (its ID stays). */
    public static BuildingInfo capture(ServerPlayer player, Identifier id, boolean replace) {
        if (!replace && building(player.level().getServer(), id) != null)
            throw new IllegalArgumentException("Building already exists: " + id);
        if (replace && building(player.level().getServer(), id) == null)
            throw new IllegalArgumentException("Unknown building: " + id);
        AreaSelectionService.Selection selection = AreaSelectionService.requireComplete(player);
        if (!SELECTION_OWNER.equals(selection.owner()) ||
                !selection.dimension().equals(player.level().dimension().identifier()))
            throw new IllegalStateException("Select both building corners in this world first");
        if (selection.volume() > MAX_VOLUME)
            throw new IllegalArgumentException("Building exceeds " + MAX_VOLUME + " blocks");
        MinecraftServer server = player.level().getServer();
        BuildingData.Building value = new BuildingData.Building(id, selection.sizeX(),
                selection.sizeY(), selection.sizeZ(), player.getName().getString());
        Identifier structureId = structureId(id);
        StructureTemplate template = server.getStructureManager().getOrCreate(structureId);
        template.setAuthor(value.author());
        template.fillFromWorld((ServerLevel) player.level(), selection.minimum(),
                new Vec3i(value.sizeX(), value.sizeY(), value.sizeZ()), false, List.of());
        if (!server.getStructureManager().save(structureId))
            throw new IllegalStateException("Could not save building " + id);
        BuildingData.get(server).put(value);
        AreaSelectionService.clear(player);
        return view(value);
    }

    public static boolean delete(MinecraftServer server, Identifier id) throws IOException {
        if (building(server, id) == null) return false;
        if (MobForge.SCRIPTS.all().stream().anyMatch(script -> script.steps().stream()
                .anyMatch(step -> step instanceof MobScript.BuildingStep building
                        && building.buildId().equals(id))))
            throw new IllegalStateException("Remove this building from scripts before deleting it");
        Identifier structureId = structureId(id);
        Path generated = server.getWorldPath(LevelResource.GENERATED_DIR).toAbsolutePath().normalize();
        Path structure = generated.resolve(structureId.getNamespace()).resolve("structure")
                .resolve(structureId.getPath() + ".nbt").normalize();
        if (!structure.startsWith(generated)) throw new IOException("Unsafe building path");
        Files.deleteIfExists(structure);
        server.getStructureManager().remove(structureId);
        return BuildingData.get(server).remove(id);
    }

    /**
     * Places a saved building a slice at a time within the script time budget; returns the next block index,
     * or -1 when the building is complete.
     */
    public static long spawnBatch(ServerLevel level, Identifier id, BlockPos origin, long start) {
        BuildingInfo info = building(level.getServer(), id);
        if (info == null) throw new IllegalArgumentException("Unknown building " + id);
        StructureTemplate template = level.getServer().getStructureManager().get(structureId(id))
                .orElseThrow(() -> new IllegalStateException("Missing saved blocks for building " + id));
        var palettes = ((ekuzo.crazyworldprogression.mixin.StructureTemplateAccessor) template).cwp$palettes();
        List<StructureTemplate.StructureBlockInfo> blocks = palettes.isEmpty() ? List.of() : palettes.getFirst().blocks();
        int index = (int) start;
        try (var slice = ekuzo.crazyworldprogression.cleanup.TickBudget.start(level.getServer(), ekuzo.crazyworldprogression.cleanup.TickBudget.Priority.SCRIPT)) {
            int done = 0;
            while (index < blocks.size() && slice.more(done++)) {
                ekuzo.crazyworldprogression.portals.InstanceBuilds.placeOne(level, origin, blocks.get(index));
                index++;
            }
        }
        if (index < blocks.size()) return index;
        if (PortalRegistry.instanceSlots().contains(level.dimension().identifier()))
            BuildingData.get(level.getServer()).addPlacement(level.dimension().identifier(),
                    new BuildingData.Placement(id, origin.asLong(), info.sizeX(), info.sizeY(), info.sizeZ()));
        return -1L;
    }

    public static void spawn(ServerLevel level, Identifier id, BlockPos origin) {
        BuildingInfo info = building(level.getServer(), id);
        if (info == null) throw new IllegalArgumentException("Unknown building " + id);
        StructureTemplate template = level.getServer().getStructureManager().get(structureId(id))
                .orElseThrow(() -> new IllegalStateException("Missing saved blocks for building " + id));
        if (template.getSize().getX() != info.sizeX() || template.getSize().getY() != info.sizeY()
                || template.getSize().getZ() != info.sizeZ())
            throw new IllegalStateException("Building size does not match saved blocks for " + id);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        if (!template.placeInWorld(level, origin, origin, new StructurePlaceSettings()
                .setKnownShape(true).setIgnoreEntities(true), level.getRandom(), flags))
            throw new IllegalStateException("Could not spawn building " + id);
        if (PortalRegistry.instanceSlots().contains(level.dimension().identifier()))
            BuildingData.get(level.getServer()).addPlacement(level.dimension().identifier(),
                    new BuildingData.Placement(id, origin.asLong(), info.sizeX(), info.sizeY(), info.sizeZ()));
    }

    /** Clear at most one server-tick slice; return the next index or -1 when finished. */
    public static long clearBatch(ServerLevel level, Identifier id, BlockPos origin, long start) {
        BuildingInfo info = building(level.getServer(), id);
        if (info == null) throw new IllegalArgumentException("Unknown building " + id);
        long next = clearRangeBatch(level, origin, info.sizeX(), info.sizeY(), info.sizeZ(), start, ekuzo.crazyworldprogression.cleanup.TickBudget.Priority.SCRIPT);
        if (next < 0) BuildingData.get(level.getServer()).removePlacement(level.dimension().identifier(), origin.asLong());
        return next;
    }

    /** Clear an old placement at the spawn origin before replacing it; the caller resumes next tick. */
    public static long clearPreviousBatch(ServerLevel level, BlockPos origin, long start) {
        if (!PortalRegistry.instanceSlots().contains(level.dimension().identifier())) return -1L;
        BuildingData data = BuildingData.get(level.getServer());
        for (BuildingData.Placement placement : List.copyOf(data.placements(level.dimension().identifier()))) {
            if (placement.origin() != origin.asLong()) continue;
            long next = clearRangeBatch(level, origin, placement.sizeX(), placement.sizeY(), placement.sizeZ(),
                    start, ekuzo.crazyworldprogression.cleanup.TickBudget.Priority.SCRIPT);
            if (next >= 0) return next;
            data.removePlacement(level.dimension().identifier(), placement.origin());
            return data.placements(level.dimension().identifier()).stream()
                    .anyMatch(other -> other.origin() == origin.asLong()) ? 0L : -1L;
        }
        return -1L;
    }

    /** Clear at most one slice of one recorded building in a reusable instance slot. */
    public static long clearInstanceBatch(ServerLevel level, long start, ekuzo.crazyworldprogression.cleanup.TickBudget.Priority priority) {
        BuildingData data = BuildingData.get(level.getServer());
        for (BuildingData.Placement placement : List.copyOf(data.placements(level.dimension().identifier()))) {
            BlockPos origin = BlockPos.of(placement.origin());
            long next = clearRangeBatch(level, origin, placement.sizeX(), placement.sizeY(),
                    placement.sizeZ(), start, priority);
            if (next >= 0) return next;
            data.removePlacement(level.dimension().identifier(), placement.origin());
            return data.placements(level.dimension().identifier()).isEmpty() ? -1L : 0L;
        }
        return -1L;
    }

    /** Queue cleanup and run the continuation only after every recorded building is gone. */
    public static void clearInstanceThen(ServerLevel level, Runnable continuation) {
        MinecraftServer server = level.getServer();
        Identifier dimension = level.dimension().identifier();
        if (BuildingData.get(server).placements(dimension).isEmpty()) {
            continuation.run();
            return;
        }
        Map<Identifier, ScheduledClear> jobs = SCHEDULED_CLEARS.computeIfAbsent(server, ignored -> new HashMap<>());
        ScheduledClear existing = jobs.get(dimension);
        if (existing != null) throw new IllegalStateException("Building cleanup already pending in " + dimension);
        jobs.put(dimension, new ScheduledClear(level, 0L, continuation));
    }

    /** Use one shared slice before portal cleanup; return whether this tick's budget was consumed. */
    public static boolean tickScheduledClears(MinecraftServer server) {
        Map<Identifier, ScheduledClear> jobs = SCHEDULED_CLEARS.get(server);
        if (jobs == null || jobs.isEmpty()) return false;
        var entry = jobs.entrySet().iterator().next();
        ScheduledClear job = entry.getValue();
        // Scheduled clears belong to new instances and wipe resets, so they share instance creation's priority.
        long next = clearInstanceBatch(job.level(), job.nextIndex(), ekuzo.crazyworldprogression.cleanup.TickBudget.Priority.CREATION);
        if (next < 0L) {
            jobs.remove(entry.getKey());
            try { job.continuation().run(); }
            catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.error("Could not complete building cleanup in {}",
                        entry.getKey(), exception);
            }
        } else jobs.put(entry.getKey(), new ScheduledClear(job.level(), next, job.continuation()));
        return true;
    }

    public static boolean cleanupPending(ServerLevel level) {
        Map<Identifier, ScheduledClear> jobs = SCHEDULED_CLEARS.get(level.getServer());
        return jobs != null && jobs.containsKey(level.dimension().identifier());
    }

    public static boolean hasInstanceBuildings(ServerLevel level) {
        return !BuildingData.get(level.getServer()).placements(level.dimension().identifier()).isEmpty();
    }

    public static void clearScheduledState(MinecraftServer server) { SCHEDULED_CLEARS.remove(server); }

    private record ScheduledClear(ServerLevel level, long nextIndex, Runnable continuation) { }

    private static long clearRangeBatch(ServerLevel level, BlockPos origin, int sizeX, int sizeY,
                                        int sizeZ, long start, ekuzo.crazyworldprogression.cleanup.TickBudget.Priority priority) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        long volume = (long) sizeX * sizeY * sizeZ;
        long layer = (long) sizeX * sizeZ;
        long index = start;
        try (var slice = ekuzo.crazyworldprogression.cleanup.TickBudget.start(level.getServer(), priority)) {
            int done = 0;
            while (index < volume && slice.more(done++)) {
                int y = (int) (index / layer);
                int z = (int) ((index % layer) / sizeX);
                int x = (int) (index % sizeX);
                cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                if (!level.getBlockState(cursor).isAir()) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), flags);
                index++;
            }
        }
        return index >= volume ? -1 : index;
    }

    private static Identifier structureId(Identifier id) {
        return Identifier.fromNamespaceAndPath(id.getNamespace(), "cwp_buildings/" + id.getPath());
    }
    private static BuildingInfo view(BuildingData.Building value) {
        return new BuildingInfo(value.id(), value.sizeX(), value.sizeY(), value.sizeZ(), value.author());
    }
}
