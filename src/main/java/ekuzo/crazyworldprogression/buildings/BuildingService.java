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
import java.util.List;
import ekuzo.crazyworldprogression.portals.PortalRegistry;

/** Capture once, then place or clear copies in a world or pocket instance. */
public final class BuildingService {
    public static final long MAX_VOLUME = 131_072L;
    public static final Identifier SELECTION_OWNER = CrazyWorldProgression.id("building_capture");
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
        if (building(player.level().getServer(), id) != null)
            throw new IllegalArgumentException("Building already exists: " + id);
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

    public static void spawn(ServerLevel level, Identifier id, BlockPos origin) {
        BuildingInfo info = building(level.getServer(), id);
        if (info == null) throw new IllegalArgumentException("Unknown building " + id);
        StructureTemplate template = level.getServer().getStructureManager().get(structureId(id))
                .orElseThrow(() -> new IllegalStateException("Missing saved blocks for building " + id));
        if (template.getSize().getX() != info.sizeX() || template.getSize().getY() != info.sizeY()
                || template.getSize().getZ() != info.sizeZ())
            throw new IllegalStateException("Building size does not match saved blocks for " + id);
        if (PortalRegistry.instanceSlots().contains(level.dimension().identifier())) {
            BuildingData data = BuildingData.get(level.getServer());
            for (BuildingData.Placement previous : List.copyOf(data.placements(level.dimension().identifier()))) {
                if (previous.origin() != origin.asLong()) continue;
                long index = 0;
                do {
                    index = clearRangeBatch(level, origin, previous.sizeX(), previous.sizeY(),
                            previous.sizeZ(), index, 8_192);
                } while (index >= 0);
                data.removePlacement(level.dimension().identifier(), previous.origin());
            }
        }
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        if (!template.placeInWorld(level, origin, origin, new StructurePlaceSettings()
                .setKnownShape(true).setIgnoreEntities(true), level.getRandom(), flags))
            throw new IllegalStateException("Could not spawn building " + id);
        if (PortalRegistry.instanceSlots().contains(level.dimension().identifier()))
            BuildingData.get(level.getServer()).addPlacement(level.dimension().identifier(),
                    new BuildingData.Placement(id, origin.asLong(), info.sizeX(), info.sizeY(), info.sizeZ()));
    }

    /** Clear at most one server-tick slice; return the next index or -1 when finished. */
    public static long clearBatch(ServerLevel level, Identifier id, BlockPos origin, long start, int budget) {
        BuildingInfo info = building(level.getServer(), id);
        if (info == null) throw new IllegalArgumentException("Unknown building " + id);
        long next = clearRangeBatch(level, origin, info.sizeX(), info.sizeY(), info.sizeZ(), start, budget);
        if (next < 0) BuildingData.get(level.getServer()).removePlacement(level.dimension().identifier(), origin.asLong());
        return next;
    }

    /** Clear every recorded copy in this reusable instance slot, including copies outside the pocket bounds. */
    public static void clearInstance(ServerLevel level) {
        BuildingData data = BuildingData.get(level.getServer());
        for (BuildingData.Placement placement : List.copyOf(data.placements(level.dimension().identifier()))) {
            BlockPos origin = BlockPos.of(placement.origin());
            long index = 0;
            do {
                index = clearRangeBatch(level, origin, placement.sizeX(), placement.sizeY(),
                        placement.sizeZ(), index, 8_192);
            } while (index >= 0);
            data.removePlacement(level.dimension().identifier(), placement.origin());
        }
    }

    private static long clearRangeBatch(ServerLevel level, BlockPos origin, int sizeX, int sizeY,
                                        int sizeZ, long start, int budget) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        long volume = (long) sizeX * sizeY * sizeZ;
        long end = Math.min(volume, start + budget);
        long layer = (long) sizeX * sizeZ;
        for (long index = start; index < end; index++) {
            int y = (int) (index / layer);
            int z = (int) ((index % layer) / sizeX);
            int x = (int) (index % sizeX);
            cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (!level.getBlockState(cursor).isAir()) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), flags);
        }
        return end >= volume ? -1 : end;
    }

    private static Identifier structureId(Identifier id) {
        return Identifier.fromNamespaceAndPath(id.getNamespace(), "cwp_buildings/" + id.getPath());
    }
    private static BuildingInfo view(BuildingData.Building value) {
        return new BuildingInfo(value.id(), value.sizeX(), value.sizeY(), value.sizeZ(), value.author());
    }
}
