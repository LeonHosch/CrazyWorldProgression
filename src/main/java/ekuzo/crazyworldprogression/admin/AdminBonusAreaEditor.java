package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreaData;
import ekuzo.crazyworldprogression.worldboundary.bonus.BonusAreas;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin panel rows for bonus areas: the Bonus area level, the spawn field's radius per level, the bonus areas
 * (outposts) and the field buffs the global skill trees currently give. A content mod puts {@link #entries} on one of
 * its pages and passes actions starting with {@code divine.} to {@link #action}.
 */
public final class AdminBonusAreaEditor {
    private AdminBonusAreaEditor() { }

    /** True for the actions these rows send. */
    public static boolean handles(String actionId) { return actionId.startsWith("divine."); }

    public static List<AdminPanelRegistry.AdminEntry> entries(ServerPlayer player) {
        return entries(player, "Bonus areas", "Bonus area level");
    }

    public static List<AdminPanelRegistry.AdminEntry> entries(ServerPlayer player, String areaName, String levelName) {
        MinecraftServer server = player.level().getServer();
        int level = BonusAreas.level(server);
        double radius = BonusAreas.spawnRadius(server);
        List<AdminPanelRegistry.AdminEntry> entries = new ArrayList<>();
        entries.add(AdminPanelRegistry.AdminEntry.editor(areaName, levelName + " " + level,
                "Raised by global skill-tree nodes; it is not set here.", List.of(), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor(areaName, radius > 0 ? "Spawn field radius " + fmt(radius) : "No spawn field",
                BonusAreas.contains(player) ? "You are inside a bonus area." : "You are outside the bonus areas.",
                List.of(), List.of()));

        Map<Integer, Double> radii = BonusAreas.levelRadii(server);
        entries.add(AdminPanelRegistry.AdminEntry.editor(areaName + " at spawn", "Set a radius",
                "Radius around the world spawn at one area level. Levels without a radius keep the one below.",
                List.of(new AdminPanelRegistry.AdminField("divine_level", levelName, "0", Integer.toString(level), 10, List.of()),
                        new AdminPanelRegistry.AdminField("divine_radius", "Radius", "100",
                                radius > 0 ? fmt(radius) : "100", 12, List.of())),
                List.of(new AdminPanelRegistry.AdminAction("divine.set_radius", "Save", false))));
        radii.forEach((radiusLevel, value) -> entries.add(AdminPanelRegistry.AdminEntry.editor(areaName + " at spawn",
                "Level " + radiusLevel + (radiusLevel == level ? " (current)" : ""), "radius " + fmt(value), List.of(),
                List.of(new AdminPanelRegistry.AdminAction("divine.remove_radius|" + radiusLevel, "Delete", true)))));

        entries.add(AdminPanelRegistry.AdminEntry.editor(areaName + " outposts", "Add a spot",
                "An outpost field with its own center and radius. The center defaults to your position; saving an existing name replaces it.",
                List.of(new AdminPanelRegistry.AdminField("spot_name", "Name", "outpost", "", 32, List.of()),
                        new AdminPanelRegistry.AdminField("spot_dimension", "World", "minecraft:overworld",
                                player.level().dimension().identifier().toString(), 128,
                                server.levelKeys().stream().map(key -> key.identifier().toString()).sorted().toList()),
                        new AdminPanelRegistry.AdminField("spot_x", "X", "0", fmt(Math.floor(player.getX())), 16, List.of()),
                        new AdminPanelRegistry.AdminField("spot_z", "Z", "0", fmt(Math.floor(player.getZ())), 16, List.of()),
                        new AdminPanelRegistry.AdminField("spot_radius", "Radius", "48", "48", 12, List.of())),
                List.of(new AdminPanelRegistry.AdminAction("divine.set_spot", "Save", false))));
        Map<String, BonusAreaData.Spot> spots = BonusAreas.spots(server);
        if (spots.isEmpty()) entries.add(AdminPanelRegistry.AdminEntry.editor(areaName + " outposts", "No spots", "Only the spawn field is protected.",
                List.of(), List.of()));
        spots.forEach((name, spot) -> entries.add(AdminPanelRegistry.AdminEntry.editor(areaName + " outposts", name,
                spot.dimension() + " · x " + fmt(spot.x()) + " z " + fmt(spot.z()) + " · radius " + fmt(spot.radius()), List.of(),
                List.of(new AdminPanelRegistry.AdminAction("divine.teleport|" + name, "Go there", false),
                        new AdminPanelRegistry.AdminAction("divine.remove_spot|" + name, "Delete", true)))));

        // What the global trees give inside the fields right now.
        StringBuilder buffs = new StringBuilder();
        for (String stat : BonusAreas.fieldBuffs().keySet()) {
            double value = SkillStatRegistry.getGlobalTotal(server, stat);
            if (value != 0) buffs.append(buffs.isEmpty() ? "" : " · ")
                    .append(stat.replace("addBonusArea", "").replace("addDivine", "")).append(" ").append(fmt(value));
        }
        double regeneration = BonusAreas.regeneration(server);
        if (regeneration != 0) buffs.append(buffs.isEmpty() ? "" : " · ").append("Regeneration ").append(fmt(regeneration)).append(" / 5 s");
        entries.add(AdminPanelRegistry.AdminEntry.editor(areaName + " bonuses", buffs.isEmpty() ? "No field buffs yet" : "Active field buffs",
                buffs.isEmpty() ? "Global skill-tree nodes configure area bonuses." : buffs.toString(),
                List.of(), List.of()));
        entries.add(AdminPanelRegistry.AdminEntry.editor(areaName + " bonuses", "Inside every field",
                "No natural monster spawns or creeper block damage; configured bonuses apply inside.", List.of(), List.of()));
        return entries;
    }

    public static AdminPanelRegistry.ActionResult action(ServerPlayer player, String actionId, Map<String, String> values) {
        MinecraftServer server = player.level().getServer();
        try {
            if (actionId.equals("divine.set_radius")) {
                int level = Integer.parseInt(values.getOrDefault("divine_level", "").trim());
                double radius = Double.parseDouble(values.getOrDefault("divine_radius", "").trim());
                BonusAreas.setLevelRadius(server, level, radius);
                return AdminPanelRegistry.ActionResult.success("Bonus area level " + level + ": radius " + fmt(radius));
            }
            if (actionId.startsWith("divine.remove_radius|")) {
                int level = Integer.parseInt(actionId.substring(actionId.indexOf('|') + 1));
                return BonusAreas.removeLevelRadius(server, level) ? AdminPanelRegistry.ActionResult.success("Removed the radius of level " + level)
                        : AdminPanelRegistry.ActionResult.failure("No radius for level " + level);
            }
            if (actionId.equals("divine.set_spot")) {
                String name = values.getOrDefault("spot_name", "").trim();
                if (name.isEmpty()) return AdminPanelRegistry.ActionResult.failure("Give the spot a name");
                String dimension = values.getOrDefault("spot_dimension", "").trim();
                if (server.levelKeys().stream().noneMatch(key -> key.identifier().toString().equals(dimension)))
                    return AdminPanelRegistry.ActionResult.failure("Unknown world " + dimension);
                BonusAreas.setSpot(server, name, dimension, Double.parseDouble(values.getOrDefault("spot_x", "").trim()),
                        Double.parseDouble(values.getOrDefault("spot_z", "").trim()),
                        Double.parseDouble(values.getOrDefault("spot_radius", "").trim()));
                return AdminPanelRegistry.ActionResult.success("Saved bonus area " + name.toLowerCase(Locale.ROOT).replace(' ', '_'));
            }
            if (actionId.startsWith("divine.remove_spot|")) {
                String name = actionId.substring(actionId.indexOf('|') + 1);
                return BonusAreas.removeSpot(server, name) ? AdminPanelRegistry.ActionResult.success("Removed bonus area " + name)
                        : AdminPanelRegistry.ActionResult.failure("No bonus area " + name);
            }
            if (actionId.startsWith("divine.teleport|")) {
                BonusAreaData.Spot spot = BonusAreas.spots(server).get(actionId.substring(actionId.indexOf('|') + 1));
                if (spot == null) return AdminPanelRegistry.ActionResult.failure("That spot no longer exists");
                var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.parse(spot.dimension())));
                if (level == null) return AdminPanelRegistry.ActionResult.failure("Unknown world " + spot.dimension());
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        (int) Math.floor(spot.x()), (int) Math.floor(spot.z()));
                player.teleportTo(level, spot.x() + 0.5, y, spot.z() + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
                return AdminPanelRegistry.ActionResult.success("Teleported to the spot's center");
            }
        } catch (NumberFormatException exception) {
            return AdminPanelRegistry.ActionResult.failure("Enter numbers for level, coordinates and radius");
        } catch (IllegalArgumentException exception) {
            return AdminPanelRegistry.ActionResult.failure(exception.getMessage());
        }
        return AdminPanelRegistry.ActionResult.failure("Unknown action " + actionId);
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
