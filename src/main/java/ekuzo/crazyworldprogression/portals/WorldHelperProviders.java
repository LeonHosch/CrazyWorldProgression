package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.config.CwpModule;
import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas;
import ekuzo.crazyworldprogression.worldboundary.restriction.Restriction;
import ekuzo.crazyworldprogression.worldboundary.restriction.RestrictionService;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** CWP's own movable world helpers: placed portal locations, anomalies (restrictions) and bonus areas. */
public final class WorldHelperProviders {
    private WorldHelperProviders() { }

    public static void register() {
        if (CwpModule.PORTALS.enabled()) WorkshopHelpers.registerProvider("portal", new Portals());
        if (CwpModule.WORLD_BOUNDARY.enabled()) {
            WorkshopHelpers.registerProvider("anomaly", new Anomalies());
            WorkshopHelpers.registerProvider("bonus_area", new BonusAreaSpots());
        }
    }

    private static boolean near(Vec3 center, double radius, double x, double y, double z) {
        return center.distanceToSqr(x, y, z) <= radius * radius;
    }

    /** Every named location of every portal; its ID is "portal_id|location". */
    private static final class Portals implements WorkshopHelpers.Provider {
        @Override public List<WorkshopHelpers.Helper> helpers(ServerLevel level, Vec3 center, double radius) {
            List<WorkshopHelpers.Helper> result = new ArrayList<>();
            Identifier dimension = level.dimension().identifier();
            for (PortalDefinition portal : PortalService.portals(level.getServer())) {
                PortalService.locations(level.getServer(), portal.id()).forEach((name, location) -> {
                    if (!location.dimension().equals(dimension) || !near(center, radius, location.x(), location.y(), location.z()))
                        return;
                    result.add(WorkshopHelpers.Helper.point("portal", portal.effectiveDisplayName()
                                    + " (" + portal.id() + (name.equals("original") ? "" : "/" + name) + ")",
                            portal.id() + "|" + name, location.x(), location.y() - portal.height() / 2, location.z()));
                });
            }
            return result;
        }

        @Override public boolean move(ServerLevel level, String id, int dx, int dy, int dz) {
            int bar = id.lastIndexOf('|');
            Identifier portal = bar < 0 ? null : Identifier.tryParse(id.substring(0, bar));
            if (portal == null) return false;
            var location = PortalService.locations(level.getServer(), portal).get(id.substring(bar + 1));
            if (location == null) return false;
            PortalService.setLocation(level.getServer(), portal, id.substring(bar + 1), location.dimension(),
                    new Vec3(location.x() + dx, location.y() + dy, location.z() + dz));
            return true;
        }
    }

    /** Veil anomalies and other restrictions; boss areas are anomalies too. */
    private static final class Anomalies implements WorkshopHelpers.Provider {
        @Override public List<WorkshopHelpers.Helper> helpers(ServerLevel level, Vec3 center, double radius) {
            List<WorkshopHelpers.Helper> result = new ArrayList<>();
            Identifier dimension = level.dimension().identifier();
            for (Restriction anomaly : RestrictionService.restrictions(level.getServer())) {
                if (!anomaly.dimension().equals(dimension)) continue;
                double reach = radius + Math.max(anomaly.sizeX(), Math.max(anomaly.sizeY(), anomaly.sizeZ()));
                if (!near(center, reach, anomaly.centerX(), anomaly.centerY(), anomaly.centerZ())) continue;
                String name = anomaly.id() + " (" + anomaly.shape().serializedName() + ")";
                String id = anomaly.id().toString();
                result.add(switch (anomaly.shape()) {
                    case SPHERE -> WorkshopHelpers.Helper.cylinder("anomaly", name, id, anomaly.centerX(),
                            anomaly.centerY(), anomaly.centerZ(), anomaly.sizeX(), anomaly.sizeX());
                    case CYLINDER -> WorkshopHelpers.Helper.cylinder("anomaly", name, id, anomaly.centerX(),
                            anomaly.centerY(), anomaly.centerZ(), anomaly.sizeX(), anomaly.sizeY() / 2);
                    case CUBE, PYRAMID -> WorkshopHelpers.Helper.box("anomaly", name, id,
                            anomaly.centerX() - anomaly.sizeX() / 2, anomaly.centerY() - anomaly.sizeY() / 2,
                            anomaly.centerZ() - anomaly.sizeZ() / 2, anomaly.centerX() + anomaly.sizeX() / 2,
                            anomaly.centerY() + anomaly.sizeY() / 2, anomaly.centerZ() + anomaly.sizeZ() / 2);
                });
            }
            return result;
        }

        @Override public boolean move(ServerLevel level, String id, int dx, int dy, int dz) {
            Identifier anomalyId = Identifier.tryParse(id);
            Restriction anomaly = anomalyId == null ? null : RestrictionService.get(level.getServer(), anomalyId);
            if (anomaly == null) return false;
            RestrictionService.update(level.getServer(), anomaly.movedTo(anomaly.dimension(),
                    new Vec3(anomaly.centerX() + dx, anomaly.centerY() + dy, anomaly.centerZ() + dz)));
            return true;
        }
    }

    /** Bonus areas are flat circles (no height): they are drawn on the ground and only move sideways. */
    private static final class BonusAreaSpots implements WorkshopHelpers.Provider {
        @Override public List<WorkshopHelpers.Helper> helpers(ServerLevel level, Vec3 center, double radius) {
            List<WorkshopHelpers.Helper> result = new ArrayList<>();
            String dimension = level.dimension().identifier().toString();
            for (BonusAreas.BonusArea area : BonusAreas.bonusAreas(level.getServer())) {
                if (!area.dimension().equals(dimension)) continue;
                double dx = area.x() - center.x, dz = area.z() - center.z;
                if (dx * dx + dz * dz > (radius + area.radius()) * (radius + area.radius())) continue;
                double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(area.x()),
                        (int) Math.floor(area.z()));
                result.add(WorkshopHelpers.Helper.cylinder("bonus_area", area.name(), area.name(), area.x(), ground,
                        area.z(), area.radius(), 2));
            }
            return result;
        }

        @Override public boolean move(ServerLevel level, String id, int dx, int dy, int dz) {
            if (dx == 0 && dz == 0) return false;
            BonusAreas.BonusArea area = BonusAreas.bonusAreas(level.getServer()).stream()
                    .filter(value -> value.name().equals(id)).findFirst().orElse(null);
            if (area == null) return false;
            BonusAreas.setSpot(level.getServer(), area.name(), area.dimension(), area.x() + dx, area.z() + dz,
                    area.radius());
            return true;
        }
    }
}
