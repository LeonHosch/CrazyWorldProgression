package ekuzo.crazyworldprogression.client.map;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.client.WaypointClient;
import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreaNetworking;
import ekuzo.crazyworldprogression.portals.PortalNetworking;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.Displayable;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.event.EntityRadarUpdateEvent;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Optional JourneyMap integration, entirely on the client (entrypoint "journeymap"; only loaded when JourneyMap is
 * installed). It adds to JourneyMap's maps:
 * <ul>
 *   <li>the bonus areas the player has discovered, as golden circles with their names;</li>
 *   <li>the pocket and exit portals near which the player has been, as markers with their names (coordinate
 *       portals stay hidden);</li>
 *   <li>fog over everything a content mod marks as not mappable (MapData.setFogProvider);</li>
 *   <li>the landmarks a content mod provides (MapData.setLandmarkProvider, for example boss encounters) the player
 *       has been near, as their outline and icon with name and detail; greyed out while inactive;</li>
 *   <li>"Navigate here" in the fullscreen map's right-click menu, which points CWP's coordinate guide there.</li>
 * </ul>
 * Pocket instances and pocket editors reuse their dimensions, so after the player leaves one (or disconnects inside
 * one) its JourneyMap data is deleted from the client ({@link #clearDimension}); the next pocket in that slot starts
 * with an empty map.
 * Mobs are never shown on its radar; only players are.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapIntegration implements IClientPlugin {
    private static final String MOD_ID = CrazyWorldProgression.MOD_ID;
    private static final int ZONE_COLOR = 0xF0C95A;
    private static final int FOG_COLOR = 0x0E0B16;
    private static final int LANDMARK_COLOR = 0xB04AE0;
    private static final int INACTIVE_COLOR = 0x8A8A8A;
    private static final Identifier PORTAL_ICON = CrazyWorldProgression.id("textures/map/portal.png");
    private IClientAPI api;
    private String shown = "";
    private String landmarksShown = "";
    private final List<Displayable> landmarkOverlays = new ArrayList<>();
    private ResourceKey<Level> lastDimension;
    private ResourceKey<Level> pendingClear;
    private int clearDelay;

    @Override
    public String getModId() { return MOD_ID; }

    @Override
    public void initialize(IClientAPI api) {
        this.api = api;
        // Only players on the radar: every other entity is hidden.
        ClientEventRegistry.ENTITY_RADAR_UPDATE_EVENT.subscribe(MOD_ID, event -> {
            if (event.getType() == EntityRadarUpdateEvent.EntityType.MOB) event.cancel();
        });
        FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(MOD_ID, event -> {
            ResourceKey<Level> dimension = event.getFullscreen().getUiState().dimension;
            event.getPopupMenu().addMenuItem("Navigate here", pos -> WaypointClient.set(dimension.identifier(),
                    pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::refresh);
        ClientTickEvents.END_CLIENT_TICK.register(this::watchInstances);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (lastDimension != null && isInstance(lastDimension)) clearDimension(client, lastDimension);
            lastDimension = null;
            pendingClear = null;
        });
    }

    /** Twice a second: redraws the overlays when zones, discoveries, the fog or the dimension changed. */
    private void refresh(Minecraft client) {
        if (client.level == null || client.player == null || client.player.tickCount % 10 != 0) return;
        ResourceKey<Level> dimension = client.level.dimension();
        MapData.Fog fog = MapData.fog(dimension);
        String state = MapData.version() + "|" + dimension.identifier() + "|" + fog + "|" + MapData.portals().size();
        if (state.equals(shown)) {
            refreshLandmarks(dimension);
            return;
        }
        shown = state;
        api.removeAll(MOD_ID);
        landmarkOverlays.clear();
        landmarksShown = "";
        refreshLandmarks(dimension);
        try {
            for (BonusAreaNetworking.Zone zone : MapData.zones())
                if (MapData.discovered(zone)) api.show(zoneOverlay(zone));
            for (PortalNetworking.PortalSnapshot portal : MapData.portals())
                if (!Objects.equals(portal.kind(), "coordinates") && !Objects.equals(portal.kind(), "preview") && MapData.discovered(portal))
                    api.show(portalMarker(portal));
            if (fog != null) api.show(fogOverlay(dimension, fog));
        } catch (Exception exception) {
            CrazyWorldProgression.LOGGER.warn("Could not show map overlays: {}", exception.toString());
        }
    }

    /** Redraws only the landmarks when one changed (their detail line may count down every second). */
    private void refreshLandmarks(ResourceKey<Level> dimension) {
        List<MapData.Landmark> landmarks = MapData.landmarks().stream()
                .filter(landmark -> landmark.dimension().equals(dimension.identifier().toString()) && MapData.discovered(landmark))
                .toList();
        String state = landmarks.toString();
        if (state.equals(landmarksShown)) return;
        landmarksShown = state;
        landmarkOverlays.forEach(api::remove);
        landmarkOverlays.clear();
        try {
            for (MapData.Landmark landmark : landmarks) {
                for (Displayable overlay : landmarkOverlays(dimension, landmark)) {
                    api.show(overlay);
                    landmarkOverlays.add(overlay);
                }
            }
        } catch (Exception exception) {
            CrazyWorldProgression.LOGGER.warn("Could not show map landmarks: {}", exception.toString());
        }
    }

    /** A landmark's outline (circle or rectangle) and its icon with name and detail, grey while inactive. */
    private static List<Displayable> landmarkOverlays(ResourceKey<Level> dimension, MapData.Landmark landmark) {
        int color = landmark.active() ? LANDMARK_COLOR : INACTIVE_COLOR;
        ShapeProperties shape = new ShapeProperties().setStrokeColor(color).setStrokeOpacity(0.9F).setStrokeWidth(2.0F)
                .setFillColor(color).setFillOpacity(landmark.active() ? 0.12F : 0.08F);
        MapPolygon outline = landmark.round() ? circle(landmark.x(), landmark.z(), landmark.sizeX(), 72) : new MapPolygon(
                BlockPos.containing(landmark.x() - landmark.sizeX() / 2, 64, landmark.z() - landmark.sizeZ() / 2),
                BlockPos.containing(landmark.x() + landmark.sizeX() / 2, 64, landmark.z() - landmark.sizeZ() / 2),
                BlockPos.containing(landmark.x() + landmark.sizeX() / 2, 64, landmark.z() + landmark.sizeZ() / 2),
                BlockPos.containing(landmark.x() - landmark.sizeX() / 2, 64, landmark.z() + landmark.sizeZ() / 2));
        PolygonOverlay area = new PolygonOverlay(MOD_ID, dimension, shape, outline);
        area.setOverlayGroupName("Boss encounters").setTitle(landmark.name()).setDisplayOrder(150);
        // Grey and faded while inactive (the icon's own colors stay, only darkened).
        MapImage icon = new MapImage(landmark.icon(), 0, 0, landmark.iconSize(), landmark.iconSize(), landmark.active() ? 0xFFFFFF : 0x6A6A6A,
                landmark.active() ? 1.0F : 0.6F).setDisplayWidth(24).setDisplayHeight(24).centerAnchors();
        MarkerOverlay marker = new MarkerOverlay(MOD_ID, BlockPos.containing(landmark.x(), 64, landmark.z()), icon);
        String label = landmark.detail() == null || landmark.detail().isBlank() ? landmark.name()
                : landmark.name() + " - " + landmark.detail();
        marker.setDimension(dimension);
        marker.setOverlayGroupName("Boss encounters").setLabel(label).setTitle(label)
                .setTextProperties(new TextProperties().setColor(color).setOffsetY(16).setBackgroundOpacity(0.4F))
                .setDisplayOrder(210);
        return List.of(area, marker);
    }

    /** Pocket instances and pocket editors: dimensions that are reused for different pockets. */
    private static boolean isInstance(ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
        return id.getNamespace().equals(MOD_ID) && (id.getPath().startsWith("portal_instance_") || id.getPath().startsWith("pocket_editor_"));
    }

    /** Notices leaving an instance dimension and clears its map two seconds later (after JourneyMap saved it). */
    private void watchInstances(Minecraft client) {
        if (pendingClear != null && --clearDelay <= 0) {
            ResourceKey<Level> dimension = pendingClear;
            pendingClear = null;
            if (client.level == null || client.level.dimension() != dimension) clearDimension(client, dimension);
        }
        if (client.level == null) return;
        ResourceKey<Level> now = client.level.dimension();
        if (lastDimension != null && now != lastDimension && isInstance(lastDimension)) {
            pendingClear = lastDimension;
            clearDelay = 40;
        }
        lastDimension = now;
    }

    /**
     * Deletes JourneyMap's stored map of {@code dimension} on this client. JourneyMap's API cannot do this, so it uses
     * JourneyMap internals through reflection: if they change in a JourneyMap update, the cleanup simply does nothing.
     */
    static void clearDimension(Minecraft client, ResourceKey<Level> dimension) {
        try {
            Class<?> cacheClass = Class.forName("journeymap.client.model.region.RegionImageCache");
            Object cache = cacheClass.getField("INSTANCE").get(null);
            cacheClass.getMethod("flushToDisk", boolean.class).invoke(cache, false);
            cacheClass.getMethod("awaitPendingWrites", long.class).invoke(cache, 3_000L);
            cacheClass.getMethod("clear").invoke(cache);
            Class<?> dataCache = Class.forName("journeymap.client.data.DataCache");
            dataCache.getMethod("invalidateChunkMDCache").invoke(dataCache.getField("INSTANCE").get(null));
            Class<?> files = Class.forName("journeymap.client.io.FileHandler");
            java.io.File worldDir = (java.io.File) files.getMethod("getJMWorldDir", Minecraft.class).invoke(null, client);
            if (worldDir == null) return;
            java.nio.file.Path dimPath = (java.nio.file.Path) files.getMethod("getDimPath", java.io.File.class, ResourceKey.class)
                    .invoke(null, worldDir, dimension);
            if (dimPath == null || !java.nio.file.Files.isDirectory(dimPath) || !dimPath.startsWith(worldDir.toPath())) return;
            try (var walk = java.nio.file.Files.walk(dimPath)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
            CrazyWorldProgression.LOGGER.info("Cleared the JourneyMap map of {}", dimension.identifier());
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException | LinkageError exception) {
            CrazyWorldProgression.LOGGER.warn("Could not clear the JourneyMap map of {}: {}", dimension.identifier(), exception.toString());
        }
    }

    private static PolygonOverlay zoneOverlay(BonusAreaNetworking.Zone zone) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(zone.dimension()));
        ShapeProperties shape = new ShapeProperties().setStrokeColor(ZONE_COLOR).setStrokeOpacity(0.9F).setStrokeWidth(2.0F)
                .setFillColor(ZONE_COLOR).setFillOpacity(0.12F);
        PolygonOverlay overlay = new PolygonOverlay(MOD_ID, dimension, shape, circle(zone.x(), zone.z(), zone.radius(), 72));
        overlay.setOverlayGroupName("Divine fields").setLabel(zone.name()).setTitle(zone.name())
                .setTextProperties(new TextProperties().setColor(ZONE_COLOR).setScale(1.5F).setBackgroundOpacity(0.4F))
                .setDisplayOrder(100);
        return overlay;
    }

    private static MarkerOverlay portalMarker(PortalNetworking.PortalSnapshot portal) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(portal.dimension()));
        MapImage icon = new MapImage(PORTAL_ICON, 16, 16).setColor(portal.colorRgb()).centerAnchors();
        MarkerOverlay marker = new MarkerOverlay(MOD_ID, BlockPos.containing(portal.centerX(), portal.centerY(), portal.centerZ()), icon);
        String name = portal.name() == null || portal.name().isBlank() ? "Portal" : portal.name();
        marker.setDimension(dimension);
        marker.setOverlayGroupName("Portals").setLabel(name).setTitle(name)
                .setTextProperties(new TextProperties().setColor(portal.colorRgb()).setOffsetY(12).setBackgroundOpacity(0.4F))
                .setDisplayOrder(200);
        return marker;
    }

    /** Opaque fog over everything outside the mappable circle. */
    private static PolygonOverlay fogOverlay(ResourceKey<Level> dimension, MapData.Fog fog) {
        double reach = fog.radius() + 30_000;
        MapPolygon outer = new MapPolygon(
                BlockPos.containing(fog.centerX() - reach, 64, fog.centerZ() - reach),
                BlockPos.containing(fog.centerX() + reach, 64, fog.centerZ() - reach),
                BlockPos.containing(fog.centerX() + reach, 64, fog.centerZ() + reach),
                BlockPos.containing(fog.centerX() - reach, 64, fog.centerZ() + reach));
        ShapeProperties shape = new ShapeProperties().setFillColor(FOG_COLOR).setFillOpacity(1.0F)
                .setStrokeColor(0x7A4FB8).setStrokeOpacity(0.9F).setStrokeWidth(3.0F);
        PolygonOverlay overlay = new PolygonOverlay(MOD_ID, dimension, shape, outer,
                List.of(circle(fog.centerX(), fog.centerZ(), fog.radius(), 160)));
        overlay.setOverlayGroupName("Veil").setDisplayOrder(50);
        return overlay;
    }

    private static MapPolygon circle(double x, double z, double radius, int points) {
        List<BlockPos> ring = new ArrayList<>(points);
        for (int k = 0; k < points; k++) {
            double angle = k * Math.PI * 2 / points;
            ring.add(BlockPos.containing(x + Math.cos(angle) * radius, 64, z + Math.sin(angle) * radius));
        }
        return new MapPolygon(ring);
    }
}
