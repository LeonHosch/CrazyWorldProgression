package ekuzo.crazyworldprogression.portals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/** Captures, persists, queries, places, and clears CWP-owned dimension templates. */
public final class PortalTemplateService {
    public static final long MAX_TEMPLATE_VOLUME = 1_048_576L;

    // Prevent instantiation of the stateless template facade.
    private PortalTemplateService() {
    }

    // Capture an inclusive cuboid and relative player-entry position into world-generated structure storage.
    public static TemplateInfo capture(MinecraftServer server, ServerLevel source, Identifier id,
                                       BlockPos first, BlockPos second, BlockPos spawn, String author) {
        BlockPos minimum = new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
        BlockPos maximum = new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
        Vec3i size = new Vec3i(maximum.getX() - minimum.getX() + 1, maximum.getY() - minimum.getY() + 1,
                maximum.getZ() - minimum.getZ() + 1);
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume > MAX_TEMPLATE_VOLUME) {
            throw new IllegalArgumentException("Template contains " + volume + " blocks; maximum is "
                    + MAX_TEMPLATE_VOLUME);
        }
        if (spawn.getX() < minimum.getX() || spawn.getX() > maximum.getX()
                || spawn.getY() < minimum.getY() || spawn.getY() > maximum.getY()
                || spawn.getZ() < minimum.getZ() || spawn.getZ() > maximum.getZ()) {
            throw new IllegalArgumentException("Template spawn must be inside the captured cuboid");
        }
        StructureTemplate structure = server.getStructureManager().getOrCreate(id);
        structure.setAuthor(author);
        structure.fillFromWorld(source, minimum, size, true, List.of());
        if (!server.getStructureManager().save(id)) {
            throw new IllegalStateException("Minecraft could not save structure template " + id);
        }
        PortalTemplateData.PortalTemplate metadata = new PortalTemplateData.PortalTemplate(id,
                size.getX(), size.getY(), size.getZ(), spawn.getX() - minimum.getX(),
                spawn.getY() - minimum.getY(), spawn.getZ() - minimum.getZ(), author);
        PortalTemplateData.get(server).put(metadata);
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

    // Place one captured structure into an empty registered instance slot.
    static void reconstruct(MinecraftServer server, ServerLevel destination, Identifier id, BlockPos origin) {
        PortalTemplateData.PortalTemplate metadata = requireMetadata(server, id);
        StructureTemplate structure = server.getStructureManager().get(id)
                .orElseThrow(() -> new IllegalStateException("Missing structure NBT for portal template " + id));
        if (structure.getSize().getX() != metadata.sizeX() || structure.getSize().getY() != metadata.sizeY()
                || structure.getSize().getZ() != metadata.sizeZ()) {
            throw new IllegalStateException("Template metadata size no longer matches structure NBT for " + id);
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true);
        if (!structure.placeInWorld(destination, origin, origin, settings, destination.getRandom(), 3)) {
            throw new IllegalStateException("Could not reconstruct portal template " + id);
        }
    }

    // Remove non-player entities belonging to an expired instance before block cleanup begins.
    static void discardEntities(ServerLevel level, PortalData.StoredInstance instance) {
        BlockPos minimum = new BlockPos(instance.originX(), instance.originY(), instance.originZ());
        BlockPos maximum = minimum.offset(instance.sizeX() - 1, instance.sizeY() - 1, instance.sizeZ() - 1);
        AABB bounds = new AABB(minimum.getX(), minimum.getY(), minimum.getZ(),
                maximum.getX() + 1.0, maximum.getY() + 1.0, maximum.getZ() + 1.0);
        level.getEntities((Entity) null, bounds, entity -> !(entity instanceof Player)).forEach(Entity::discard);
        BoundingBox tickBounds = BoundingBox.fromCorners(minimum, maximum);
        level.getBlockTicks().clearArea(tickBounds);
        level.getFluidTicks().clearArea(tickBounds);
    }

    // Clear a bounded linear slice and return the next index, or minus one when the template volume is empty.
    static long clearBatch(ServerLevel level, PortalData.StoredInstance instance, long startIndex, int budget) {
        long volume = (long) instance.sizeX() * instance.sizeY() * instance.sizeZ();
        long end = Math.min(volume, startIndex + budget);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        long layerSize = (long) instance.sizeX() * instance.sizeZ();
        for (long index = startIndex; index < end; index++) {
            int y = (int) (index / layerSize);
            long withinLayer = index % layerSize;
            int z = (int) (withinLayer / instance.sizeX());
            int x = (int) (withinLayer % instance.sizeX());
            cursor.set(instance.originX() + x, instance.originY() + y, instance.originZ() + z);
            if (!level.getBlockState(cursor).isAir()) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
        }
        return end >= volume ? -1L : end;
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
