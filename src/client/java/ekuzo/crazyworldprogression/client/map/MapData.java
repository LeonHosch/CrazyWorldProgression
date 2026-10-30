package ekuzo.crazyworldprogression.client.map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.client.PortalClientRenderer;
import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreaNetworking;
import ekuzo.crazyworldprogression.portals.PortalNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Client-side map data for map mods (no map mod classes here): the divine zones the server sent, which zones and
 * portals this player has discovered (remembered locally per server/world in
 * {@code config/crazy-world-progression/map-discoveries.json}), and the map fog a content mod provides.
 *
 * <p>A zone counts as discovered once the player stood in it; a portal once the player came within
 * {@link #PORTAL_DISCOVERY_RANGE} blocks (so they will have seen and mapped that area); a landmark once the player
 * came within that range of its edge.
 */
public final class MapData {
    public static final double PORTAL_DISCOVERY_RANGE = 96.0;
    private static final Gson GSON = new Gson();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("crazy-world-progression/map-discoveries.json");
    private static volatile List<BonusAreaNetworking.Zone> zones = List.of();
    private static Map<String, Set<String>> discoveries;
    private static Function<ResourceKey<Level>, Fog> fogProvider = dimension -> null;
    private static Supplier<List<Landmark>> landmarkProvider = List::of;
    private static int version;

    /** Everything outside a circle around (centerX, centerZ) is fogged over on the map. */
    public record Fog(double centerX, double centerZ, double radius) { }

    /**
     * A place a content mod marks on maps (a boss encounter, for example): its footprint ({@code round}: a circle of
     * radius {@code sizeX}; otherwise a {@code sizeX} by {@code sizeZ} rectangle), a square icon texture of
     * {@code iconSize} pixels, and a name and detail line.
     * An inactive landmark is drawn greyed out. The provider is asked again every refresh, so {@code detail} may count
     * down.
     */
    public record Landmark(String id, String dimension, boolean round, double x, double z, double sizeX, double sizeZ,
                           String name, Identifier icon, int iconSize, boolean active, String detail) { }

    private MapData() { }

    public static void initialize() {
        // Bonus areas come with the world boundary part.
        if (ekuzo.crazyworldprogression.config.CwpModule.WORLD_BOUNDARY.enabled())
            ClientPlayNetworking.registerGlobalReceiver(BonusAreaNetworking.Payload.TYPE, (payload, context) -> {
                zones = BonusAreaNetworking.decode(payload.json());
                version++;
            });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            zones = List.of();
            version++;
        });
        ClientTickEvents.END_CLIENT_TICK.register(MapData::discover);
    }

    /**
     * Sets where the map is fogged over, per dimension (null: no fog). A content mod calls this in its client
     * initializer, for example to fog everything beyond a border players have not unlocked yet.
     */
    public static void setFogProvider(Function<ResourceKey<Level>, Fog> provider) { fogProvider = provider; }

    public static Fog fog(ResourceKey<Level> dimension) { return fogProvider.apply(dimension); }

    /** Sets where a content mod's landmarks come from (called from its client initializer). */
    public static void setLandmarkProvider(Supplier<List<Landmark>> provider) { landmarkProvider = provider; }

    public static List<Landmark> landmarks() {
        try { return landmarkProvider.get(); }
        catch (RuntimeException exception) { return List.of(); }
    }

    public static boolean discovered(Landmark landmark) { return known().contains("landmark:" + landmark.id()); }

    public static List<BonusAreaNetworking.Zone> zones() { return zones; }

    public static List<PortalNetworking.PortalSnapshot> portals() { return PortalClientRenderer.placedPortals(); }

    /** Changes whenever zones arrive or something new is discovered, so a map can redraw. */
    public static int version() { return version; }

    public static boolean discovered(BonusAreaNetworking.Zone zone) { return known().contains(zoneKey(zone)); }

    public static boolean discovered(PortalNetworking.PortalSnapshot portal) { return known().contains("portal:" + portal.id()); }

    private static String zoneKey(BonusAreaNetworking.Zone zone) {
        return "zone:" + zone.dimension() + "|" + (zone.id().isEmpty() ? "spawn" : zone.id());
    }

    /** Once a second: marks the zones the player stands in and the portals they are near as discovered. */
    private static void discover(Minecraft client) {
        if (client.player == null || client.level == null || client.player.tickCount % 20 != 0) return;
        String dimension = client.level.dimension().identifier().toString();
        double x = client.player.getX(), z = client.player.getZ();
        Set<String> known = known();
        boolean changed = false;
        for (BonusAreaNetworking.Zone zone : zones) {
            if (!zone.dimension().equals(dimension)) continue;
            double dx = x - zone.x(), dz = z - zone.z();
            if (dx * dx + dz * dz <= zone.radius() * zone.radius()) changed |= known.add(zoneKey(zone));
        }
        for (PortalNetworking.PortalSnapshot portal : portals()) {
            if (!portal.dimension().equals(dimension)) continue;
            double dx = x - portal.centerX(), dz = z - portal.centerZ();
            if (dx * dx + dz * dz <= PORTAL_DISCOVERY_RANGE * PORTAL_DISCOVERY_RANGE) changed |= known.add("portal:" + portal.id());
        }
        for (Landmark landmark : landmarks()) {
            if (!landmark.dimension().equals(dimension)) continue;
            double dx = x - landmark.x(), dz = z - landmark.z();
            double reach = PORTAL_DISCOVERY_RANGE + Math.max(landmark.sizeX(), landmark.sizeZ());
            if (dx * dx + dz * dz <= reach * reach) changed |= known.add("landmark:" + landmark.id());
        }
        if (changed) {
            version++;
            save();
        }
    }

    /** The discoveries of the current server or singleplayer world. */
    private static Set<String> known() {
        if (discoveries == null) load();
        return discoveries.computeIfAbsent(worldKey(), ignored -> new HashSet<>());
    }

    private static String worldKey() {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() != null)
            return "singleplayer:" + client.getSingleplayerServer().getWorldData().getLevelName();
        var server = client.getCurrentServer();
        return server != null ? "server:" + server.ip : "unknown";
    }

    private static void load() {
        discoveries = new HashMap<>();
        try {
            if (Files.exists(FILE)) {
                Map<String, Set<String>> loaded = GSON.fromJson(Files.readString(FILE), new TypeToken<Map<String, Set<String>>>() { }.getType());
                if (loaded != null) loaded.forEach((key, values) -> discoveries.put(key, new HashSet<>(values)));
            }
        } catch (IOException | RuntimeException exception) {
            discoveries = new HashMap<>();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(discoveries));
        } catch (IOException ignored) {
            // Discoveries are a convenience; a failed write only means they are found again next time.
        }
    }
}
