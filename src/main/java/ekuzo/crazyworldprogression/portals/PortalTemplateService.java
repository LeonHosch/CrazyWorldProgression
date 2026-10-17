package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.cleanup.TickBudget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

/** Captures, persists, queries, places, and clears CWP-owned dimension templates. */
public final class PortalTemplateService {

    // Prevent instantiation of the stateless template facade.
    private PortalTemplateService() {
    }

    /** Capture every built block in this pocket's discovered chunks, without corner selection. */
    public static TemplateInfo captureAll(MinecraftServer server, ServerLevel source, Identifier id,
                                          BlockPos preferredSpawn, String author) {
        PocketEditorService.BuildScan build = PocketEditorService.scanBuild(server, source, id, preferredSpawn);
        return capture(server, source, id, build.first(), build.last(), build.spawn(), author);
    }

    // Persist the automatically discovered build and its supported entry block.
    private static TemplateInfo capture(MinecraftServer server, ServerLevel source, Identifier id,
                                       BlockPos first, BlockPos second, BlockPos spawn, String author) {
        Identifier editor = PocketEditorService.dimension(server, id);
        if (editor == null || !editor.equals(source.dimension().identifier())) {
            throw new IllegalArgumentException("Create this pocket and enter its build dimension before capture");
        }
        BlockPos minimum = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
        BlockPos maximum = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        Vec3i size = new Vec3i(maximum.getX() - minimum.getX() + 1, maximum.getY() - minimum.getY() + 1,
                maximum.getZ() - minimum.getZ() + 1);
        StructureTemplate structure = server.getStructureManager().getOrCreate(id);
        structure.setAuthor(author);
        // Pocket templates contain builds. Capturing loose item entities would replay dropped
        // doors and other pickups every time a new instance is reconstructed.
        // Air is left out: instances are built into emptied slots, so it would only cost saving, loading and
        // placing time (most of a large pocket's volume is air).
        structure.fillFromWorld(source, minimum, size, false, List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR));
        if (!server.getStructureManager().save(id)) {
            throw new IllegalStateException("Minecraft could not save structure template " + id);
        }
        PortalTemplateData.PortalTemplate metadata = new PortalTemplateData.PortalTemplate(id,
                size.getX(), size.getY(), size.getZ(), spawn.getX() - minimum.getX(),
                spawn.getY() - minimum.getY(), spawn.getZ() - minimum.getZ(), author);
        PortalTemplateData.get(server).put(metadata);
        PocketEditorService.setEntry(server, id, spawn);
        PocketEditorService.setCaptureOrigin(server, id, minimum);
        PortalService.refreshProjectedPortals(server);
        PocketEditorService.clearAutoSave(server, id);
        return view(metadata);
    }

    // Return all CWP-hosted templates in deterministic identifier order.
    public static List<TemplateInfo> templates(MinecraftServer server) {
        return PortalTemplateData.get(server).values().values().stream()
                .sorted(Comparator.comparing(template -> template.id().toString()))
                .map(PortalTemplateService::view).toList();
    }

    // Resolve one CWP-hosted template by identifier.
    public static TemplateInfo template(MinecraftServer server, Identifier id) {
        PortalTemplateData.PortalTemplate metadata = PortalTemplateData.get(server).values().get(id.toString());
        return metadata == null ? null : view(metadata);
    }

    // Remove an unused captured pocket from template metadata and structure storage.
    public static boolean delete(MinecraftServer server, Identifier id) throws IOException {
        if (PortalService.portals(server).stream().anyMatch(portal ->
                !portal.isExit() && portal.templateId().equals(id))
                || PortalService.activeInstances(server).stream().anyMatch(instance -> instance.templateId().equals(id))) {
            throw new IllegalStateException("Remove linked portals and active instances before deleting this pocket");
        }
        if (PortalTemplateData.get(server).values().get(id.toString()) == null) return false;
        Path generated = server.getWorldPath(LevelResource.GENERATED_DIR).toAbsolutePath().normalize();
        Path structure = generated.resolve(id.getNamespace()).resolve("structure")
                .resolve(id.getPath() + ".nbt").normalize();
        if (!structure.startsWith(generated)) throw new IOException("Unsafe pocket structure path");
        Files.deleteIfExists(structure);
        Files.deleteIfExists(generated.resolve(id.getNamespace()).resolve("structure")
                .resolve(id.getPath() + ".snbt").normalize());
        server.getStructureManager().remove(id);
        PortalTemplateData.get(server).remove(id);
        return true;
    }

    /**
     * Places a captured pocket into an instance slot over several ticks (shared InstanceBuilds budget) and runs
     * done once every block is in place.
     */
    static void reconstructThen(MinecraftServer server, ServerLevel destination, Identifier id, BlockPos origin,
                                Runnable done) {
        PortalTemplateData.PortalTemplate metadata = requireMetadata(server, id);
        StructureTemplate structure = server.getStructureManager().get(id)
                .orElseThrow(() -> new IllegalStateException("Missing structure NBT for portal template " + id));
        if (structure.getSize().getX() != metadata.sizeX() || structure.getSize().getY() != metadata.sizeY()
                || structure.getSize().getZ() != metadata.sizeZ()) {
            throw new IllegalStateException("Template metadata size no longer matches structure NBT for " + id);
        }
        var palettes = ((ekuzo.crazyworldprogression.mixin.StructureTemplateAccessor) structure).cwp$palettes();
        List<StructureTemplate.StructureBlockInfo> blocks = palettes.isEmpty() ? List.of() : palettes.getFirst().blocks();
        // Biomes are recoloured chunk by chunk as the build completes them, instead of all at the end.
        var plains = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var sampler = destination.getChunkSource().randomState().sampler();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet coloured = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        InstanceBuilds.start(server, destination.dimension().identifier().toString(), destination, origin, blocks,
                chunk -> {
                    if (coloured.add(chunk.getPos().pack())) chunk.fillBiomesFromNoise((x, y, z, climate) -> plains, sampler);
                }, () -> {
                    finishReconstruct(server, destination, metadata, origin, coloured);
                    done.run();
                });
    }

    /** Biome recolouring and pickup cleanup once the blocks of a template are in place. */
    private static void finishReconstruct(MinecraftServer server, ServerLevel destination,
                                          PortalTemplateData.PortalTemplate metadata, BlockPos origin,
                                          it.unimi.dsi.fastutil.longs.LongSet coloured) {
        var plains = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var sampler = destination.getChunkSource().randomState().sampler();
        for (int chunkX = origin.getX() >> 4; chunkX <= (origin.getX() + metadata.sizeX() - 1) >> 4; chunkX++) {
            for (int chunkZ = origin.getZ() >> 4; chunkZ <= (origin.getZ() + metadata.sizeZ() - 1) >> 4; chunkZ++) {
                if (coloured.contains(ChunkPos.pack(chunkX, chunkZ))) continue;
                var chunk = destination.getChunk(chunkX, chunkZ);
                chunk.fillBiomesFromNoise((x, y, z, climate) -> plains, sampler);
                chunk.markUnsaved();
            }
        }
        AABB bounds = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + metadata.sizeX(), origin.getY() + metadata.sizeY(),
                origin.getZ() + metadata.sizeZ());
        destination.getEntities((Entity) null, bounds, entity -> entity instanceof ItemEntity)
                .forEach(Entity::discard);
    }

    // Place one captured structure into an empty registered instance slot.
    static void reconstruct(MinecraftServer server, ServerLevel destination, Identifier id, BlockPos origin) {
        PortalTemplateData.PortalTemplate metadata = requireMetadata(server, id);
        StructureTemplate structure = server.getStructureManager().get(id)
                .orElseThrow(() -> new IllegalStateException("Missing structure NBT for portal template " + id));
        if (structure.getSize().getX() != metadata.sizeX() || structure.getSize().getY() != metadata.sizeY()
                || structure.getSize().getZ() != metadata.sizeZ()) {
            throw new IllegalStateException("Template metadata size no longer matches structure NBT for " + id);
        }
        // The captured build already contains complete block states and their supports. Minecraft's
        // default structure finalization runs neighbor-shape updates across the whole template,
        // which can break attached blocks and drop items while reconstruction is still in progress.
        StructurePlaceSettings settings = new StructurePlaceSettings().setKnownShape(true)
                .setIgnoreEntities(true);
        // UPDATE_KNOWN_SHAPE also skips Level.setBlock's implicit shape updates. UPDATE_CLIENTS
        // alone still runs them and can break doors or buttons before the template is complete.
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        if (!structure.placeInWorld(destination, origin, origin, settings, destination.getRandom(), flags)) {
            throw new IllegalStateException("Could not reconstruct portal template " + id);
        }
        // Older slot chunks were generated with the void biome, which suppresses rain entirely.
        // Recolor the occupied chunks before players enter; new chunks use plains in the dimension JSON.
        var plains = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var sampler = destination.getChunkSource().randomState().sampler();
        for (int chunkX = origin.getX() >> 4; chunkX <= (origin.getX() + metadata.sizeX() - 1) >> 4; chunkX++) {
            for (int chunkZ = origin.getZ() >> 4; chunkZ <= (origin.getZ() + metadata.sizeZ() - 1) >> 4; chunkZ++) {
                var chunk = destination.getChunk(chunkX, chunkZ);
                chunk.fillBiomesFromNoise((x, y, z, climate) -> plains, sampler);
                chunk.markUnsaved();
            }
        }
        // Remove stale pickups left in a reused slot or created by a failed placement.
        AABB bounds = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + metadata.sizeX(), origin.getY() + metadata.sizeY(),
                origin.getZ() + metadata.sizeZ());
        destination.getEntities((Entity) null, bounds, entity -> entity instanceof ItemEntity)
                .forEach(Entity::discard);
    }

    // Remove non-player entities belonging to an expired instance before block cleanup begins.
    static void discardEntities(ServerLevel level, PortalData.StoredInstance instance) {
        BlockPos minimum = new BlockPos(instance.originX(), instance.originY(), instance.originZ());
        BlockPos maximum = minimum.offset(instance.sizeX() - 1, instance.sizeY() - 1, instance.sizeZ() - 1);
        // The whole slot dimension belongs to the instance, so boats that drifted outside the build go too.
        discardAllEntities(level);
        BoundingBox tickBounds = BoundingBox.fromCorners(minimum, maximum);
        level.getBlockTicks().clearArea(tickBounds);
        level.getFluidTicks().clearArea(tickBounds);
    }

    /**
     * Discard every loaded non-player entity in an instance slot. Entities in unloaded chunks are caught by
     * InstanceEntityLeases when their chunk loads again. Templates are placed without entities, so nothing
     * the build needs is lost.
     */
    public static void discardAllEntities(ServerLevel level) {
        discardEntities(level, false);
    }

    /** Like discardAllEntities, but keeps dropped items and experience so players can pick up their death drops. */
    public static void discardEntitiesExceptDrops(ServerLevel level) {
        discardEntities(level, true);
    }

    private static void discardEntities(ServerLevel level, boolean keepDrops) {
        List<Entity> entities = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Player) continue;
            if (keepDrops && (entity instanceof ItemEntity
                    || entity instanceof net.minecraft.world.entity.ExperienceOrb
                    || RaidSnapshots.isOrb(entity))) continue;
            entities.add(entity);
        }
        entities.forEach(Entity::discard);
    }

    private static final java.util.Map<String, FastBlockWriter> CLEAR_WRITERS = new java.util.HashMap<>();

    /**
     * Clears the next part of an instance volume and returns where to continue, or minus one when done. The
     * volume is worked chunk by chunk, one 16-block section at a time: sections that hold only air are skipped,
     * sections completely inside the volume are swapped for empty ones, and only sections on the edge of the
     * volume are cleared block by block.
     */
    static long clearBatch(ServerLevel level, PortalData.StoredInstance instance, long startIndex) {
        if (instance.sizeX() <= 0 || instance.sizeY() <= 0 || instance.sizeZ() <= 0) return -1L;
        int minX = instance.originX(), minY = instance.originY(), minZ = instance.originZ();
        int maxX = minX + instance.sizeX() - 1, maxY = minY + instance.sizeY() - 1, maxZ = minZ + instance.sizeZ() - 1;
        int firstChunkX = minX >> 4, firstChunkZ = minZ >> 4, firstSectionY = minY >> 4;
        int columnsX = (maxX >> 4) - firstChunkX + 1, columnsZ = (maxZ >> 4) - firstChunkZ + 1;
        int sectionsY = (maxY >> 4) - firstSectionY + 1;
        long units = (long) columnsX * columnsZ * sectionsY;
        // Nobody is inside an instance being deleted, so it is cleared straight in the chunk sections.
        String key = instance.slotDimension() + "|" + instance.createdEpochMillis();
        FastBlockWriter writer = CLEAR_WRITERS.computeIfAbsent(key, ignored -> new FastBlockWriter(level));
        net.minecraft.world.level.block.state.BlockState air = Blocks.AIR.defaultBlockState();
        long index = startIndex;
        // Instance deletion has the lowest priority of all block work.
        long sliceStart = System.nanoTime();
        try (TickBudget.Slice slice = TickBudget.start(level.getServer(), TickBudget.Priority.CLEANUP)) {
            while (index < units && slice.hasTime()) {
                long column = index / sectionsY;
                int sectionY = firstSectionY + (int) (index % sectionsY);
                int chunkX = firstChunkX + (int) (column % columnsX);
                int chunkZ = firstChunkZ + (int) (column / columnsX);
                index++;
                LevelChunk chunk = writer.chunk(chunkX, chunkZ);
                int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
                if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) continue;
                LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section.hasOnlyAir()) continue;
                boolean whole = minX <= chunkX << 4 && maxX >= (chunkX << 4) + 15 && minZ <= chunkZ << 4
                        && maxZ >= (chunkZ << 4) + 15 && minY <= sectionY << 4 && maxY >= (sectionY << 4) + 15;
                // Sections completely inside the volume are swapped for empty ones in one step.
                if (whole && writer.clearSection(chunk, sectionIndex)) continue;
                int x0 = Math.max(minX, chunkX << 4), x1 = Math.min(maxX, (chunkX << 4) + 15);
                int y0 = Math.max(minY, sectionY << 4), y1 = Math.min(maxY, (sectionY << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4), z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        for (int x = x0; x <= x1; x++) {
                            // Reading is much cheaper than writing, and most of a volume is usually air already.
                            if (section.getBlockState(x & 15, y & 15, z & 15).isAir()) continue;
                            writer.place(chunk, section, x, y, z, air, null);
                            if (section.hasOnlyAir()) break;
                        }
            }
        }
        writer.worked(System.nanoTime() - sliceStart);
        if (index < units) return index;
        CLEAR_WRITERS.remove(key);
        writer.finish();
        ekuzo.crazyworldprogression.CrazyWorldProgression.LOGGER.info("Cleared instance {}: {}",
                instance.slotDimension(), writer.stats());
        return -1L;
    }

    /**
     * Empties whole chunk columns (every section, full height) a slice at a time, the same way as instance
     * deletion. Returns where to continue, or minus one when done. Used to reset pocket workshops.
     */
    static long clearColumns(ServerLevel level, String key, long[] columns, long startIndex) {
        int sections = level.getSectionsCount();
        long units = (long) columns.length * sections;
        FastBlockWriter writer = CLEAR_WRITERS.computeIfAbsent(key, ignored -> new FastBlockWriter(level));
        net.minecraft.world.level.block.state.BlockState air = Blocks.AIR.defaultBlockState();
        long index = startIndex;
        long sliceStart = System.nanoTime();
        try (TickBudget.Slice slice = TickBudget.start(level.getServer(), TickBudget.Priority.CLEANUP)) {
            while (index < units && slice.hasTime()) {
                long packed = columns[(int) (index / sections)];
                int sectionIndex = (int) (index % sections);
                index++;
                LevelChunk chunk = writer.chunk(ChunkPos.getX(packed), ChunkPos.getZ(packed));
                LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section.hasOnlyAir() || writer.clearSection(chunk, sectionIndex)) continue;
                int baseX = ChunkPos.getX(packed) << 4, baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                int baseZ = ChunkPos.getZ(packed) << 4;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++)
                            if (!section.getBlockState(x, y, z).isAir())
                                writer.place(chunk, section, baseX + x, baseY + y, baseZ + z, air, null);
            }
        }
        writer.worked(System.nanoTime() - sliceStart);
        if (index < units) return index;
        CLEAR_WRITERS.remove(key);
        writer.finish();
        ekuzo.crazyworldprogression.CrazyWorldProgression.LOGGER.info("Cleared {}: {}", key, writer.stats());
        return -1L;
    }

    // Resolve internal metadata or fail before any instance mutation begins.
    private static PortalTemplateData.PortalTemplate requireMetadata(MinecraftServer server, Identifier id) {
        PortalTemplateData.PortalTemplate metadata = PortalTemplateData.get(server).values().get(id.toString());
        if (metadata == null) throw new IllegalArgumentException("Unknown CWP portal template: " + id);
        return metadata;
    }

    // Convert internal SavedData state into a stable public API view.
    private static TemplateInfo view(PortalTemplateData.PortalTemplate metadata) {
        return new TemplateInfo(metadata.id(), metadata.sizeX(), metadata.sizeY(), metadata.sizeZ(),
                metadata.spawnX(), metadata.spawnY(), metadata.spawnZ(), metadata.author(), metadata.volume());
    }

    /** Public immutable metadata returned to integrations and diagnostics. */
    public record TemplateInfo(Identifier id, int sizeX, int sizeY, int sizeZ,
                               int spawnX, int spawnY, int spawnZ, String author, long volume) {
    }
}
