package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.portals.WorkshopHelpers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Draws the invisible designer helpers of the workshop the player is in (outlined boxes and areas, markers with
 * labels, arrows toward stored targets). The server only sends them to designers inside a workshop; which categories
 * show and whether labels do is remembered per player in this game.
 */
public final class WorkshopHelperClient {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("crazy-world-progression-workshop-helpers.properties");
    private static final double SHAPE_DISTANCE_SQUARED = 160.0 * 160.0;
    private static final double LABEL_DISTANCE_SQUARED = 40.0 * 40.0;
    private static final Map<String, Integer> COLORS = Map.ofEntries(Map.entry("event_box", 0xFFFFAA00),
            Map.entry("area", 0xFF55FFFF), Map.entry("mob_spawn", 0xFFFF5555), Map.entry("portal_spawn", 0xFFB455F5),
            Map.entry("building_spawn", 0xFF55FF55), Map.entry("spawn_point", 0xFFFFFF55),
            Map.entry("redstone", 0xFFFF2222), Map.entry("replace_blocks", 0xFF5599FF), Map.entry("aura", 0xFFFF55FF),
            Map.entry("effect", 0xFFAAAAAA), Map.entry("portal", 0xFFD07CFF), Map.entry("anomaly", 0xFF7CE0C8),
            Map.entry("bonus_area", 0xFF9CFF7C));
    private static final double PICK_RANGE = 96.0;
    private static KeyMapping pocketKey, scriptKey, menuKey, moveKey, northKey, southKey, westKey, eastKey;
    private static volatile String dimension = "";
    private static volatile List<WorkshopHelpers.Helper> helpers = List.of();
    private static final Set<String> hidden = new LinkedHashSet<>();
    /** Selected helpers by source ("script_id #step" or "pocket"), for move mode. */
    private static final Set<String> selected = new LinkedHashSet<>();
    private static boolean labels = true;
    private static boolean moveMode;
    private static String loadedFor = "";

    private WorkshopHelperClient() { }

    public static void initialize() {
        pocketKey = key("workshop_pocket", GLFW.GLFW_KEY_F6);
        scriptKey = key("workshop_script", GLFW.GLFW_KEY_F7);
        menuKey = key("workshop_helpers", GLFW.GLFW_KEY_F8);
        moveKey = key("workshop_move", GLFW.GLFW_KEY_F9);
        northKey = key("workshop_move_north", GLFW.GLFW_KEY_UP);
        southKey = key("workshop_move_south", GLFW.GLFW_KEY_DOWN);
        westKey = key("workshop_move_west", GLFW.GLFW_KEY_LEFT);
        eastKey = key("workshop_move_east", GLFW.GLFW_KEY_RIGHT);
        ClientPlayNetworking.registerGlobalReceiver(WorkshopHelpers.HelpersPayload.TYPE, (payload, context) -> {
            dimension = payload.dimension();
            helpers = WorkshopHelpers.decode(payload.json());
            selected.retainAll(helpers.stream().map(WorkshopHelpers.Helper::source).toList());
        });
        ClientPlayNetworking.registerGlobalReceiver(WorkshopHelpers.OpenMenuPayload.TYPE,
                (payload, context) -> openMenu(context.client()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            dimension = "";
            helpers = List.of();
            selected.clear();
            moveMode = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pocketKey.consumeClick()) if (client.gui.screen() == null) openEditor(client, "pocket");
            while (scriptKey.consumeClick()) if (client.gui.screen() == null) openEditor(client, "script");
            while (menuKey.consumeClick()) if (client.gui.screen() == null) openMenu(client);
            while (moveKey.consumeClick()) if (client.gui.screen() == null && inWorkshop()) setMoveMode(!moveMode);
            if (!inWorkshop()) selected.clear();
            arrows(client);
            draw(client);
        });
    }

    private static KeyMapping key(String name, int key) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.crazy-world-progression." + name,
                InputConstants.Type.KEYSYM, key, AdminPanelClient.CATEGORY));
    }

    /** True while move mode is on inside a workshop: clicks select helpers instead of doing their normal action. */
    public static boolean moving() { return moveMode && inWorkshop(); }

    static boolean moveMode() { return moveMode; }

    static void setMoveMode(boolean value) {
        moveMode = value;
        if (!value) selected.clear();
        Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable(value
                ? "message.crazy-world-progression.workshop.move_on" : "message.crazy-world-progression.workshop.move_off"),
                false);
    }

    static int selectedCount() { return selected.size(); }

    static void clearSelection() { selected.clear(); }

    /** Left click in move mode: add the looked-at helper to the selection. Always swallows the click. */
    public static void select() {
        String hit = pick(Minecraft.getInstance());
        if (hit != null) selected.add(hit);
        selectionMessage(hit);
    }

    /** Right click in move mode: deselect the looked-at helper, or everything when looking at none. */
    public static void deselect() {
        String hit = pick(Minecraft.getInstance());
        if (hit == null) selected.clear();
        else selected.remove(hit);
        selectionMessage(hit);
    }

    private static void selectionMessage(String hit) {
        Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable(
                "message.crazy-world-progression.workshop.selected", selected.size(), hit == null ? "-" : hit), false);
    }

    /** Arrow keys move the selection: up north, down south, left west, right east; Alt up/down vertical; Shift 5. */
    private static void arrows(Minecraft client) {
        long window = client.getWindow().handle();
        int step = down(window, GLFW.GLFW_KEY_LEFT_SHIFT) || down(window, GLFW.GLFW_KEY_RIGHT_SHIFT) ? 5 : 1;
        boolean alt = down(window, GLFW.GLFW_KEY_LEFT_ALT) || down(window, GLFW.GLFW_KEY_RIGHT_ALT);
        int dx = 0, dy = 0, dz = 0;
        while (northKey.consumeClick()) { if (alt) dy += step; else dz -= step; }
        while (southKey.consumeClick()) { if (alt) dy -= step; else dz += step; }
        while (westKey.consumeClick()) dx -= step;
        while (eastKey.consumeClick()) dx += step;
        if (!moving() || selected.isEmpty() || client.gui.screen() != null || (dx | dy | dz) == 0) return;
        CwpClientPayloads.send(new WorkshopHelpers.MovePayload(List.copyOf(selected), clamp(dx), clamp(dy), clamp(dz)));
    }

    private static int clamp(int value) { return Math.max(-16, Math.min(16, value)); }

    private static boolean down(long window, int key) { return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS; }

    /** The shown helper the crosshair points at (through blocks); one the player stands inside counts last. */
    private static String pick(Minecraft client) {
        if (client.player == null || !inWorkshop()) return null;
        load();
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(client.player.getViewVector(1.0F).scale(PICK_RANGE));
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (WorkshopHelpers.Helper helper : helpers) {
            if (hidden.contains(helper.category())) continue;
            AABB bounds = bounds(helper);
            double distance;
            if (bounds.contains(eye)) distance = PICK_RANGE * PICK_RANGE + bounds.getSize();
            else {
                var hit = bounds.clip(eye, end);
                if (hit.isEmpty()) continue;
                distance = hit.get().distanceToSqr(eye);
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = helper.source();
            }
        }
        return best;
    }

    private static AABB bounds(WorkshopHelpers.Helper helper) {
        return switch (helper.shape()) {
            case "box" -> new AABB(helper.x(), helper.y(), helper.z(), helper.maxX(), helper.maxY(), helper.maxZ());
            case "cylinder" -> {
                double radius = Math.max(0.25, helper.radius());
                yield new AABB(helper.x() - radius, helper.y() - helper.height(), helper.z() - radius,
                        helper.x() + radius, helper.y() + helper.height(), helper.z() + radius);
            }
            default -> new AABB(helper.x() - 0.3, helper.y(), helper.z() - 0.3, helper.x() + 0.3, helper.y() + 1.6,
                    helper.z() + 0.3);
        };
    }

    /** True while the server shows this player a workshop's helpers. */
    public static boolean inWorkshop() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && !dimension.isEmpty()
                && dimension.equals(client.level.dimension().identifier().toString());
    }

    static List<WorkshopHelpers.Helper> helpers() { return inWorkshop() ? helpers : List.of(); }

    /** CWP's categories, then any a content mod's helpers bring (in the order they come). */
    static List<String> categories() {
        Set<String> result = new LinkedHashSet<>(WorkshopHelpers.CATEGORIES);
        helpers().forEach(helper -> result.add(helper.category()));
        return List.copyOf(result);
    }

    static boolean shown(String category) { load(); return !hidden.contains(category); }

    static void setShown(String category, boolean shown) {
        load();
        if (shown) hidden.remove(category);
        else hidden.add(category);
        save();
    }

    static void setAll(boolean shown) {
        load();
        hidden.clear();
        if (!shown) hidden.addAll(categories());
        save();
    }

    static boolean labels() { load(); return labels; }

    static void setLabels(boolean value) { load(); labels = value; save(); }

    /** Teleport or list over the shown categories (the server checks the player may). */
    static void request(String action) {
        String categories = categories().stream().filter(WorkshopHelperClient::shown)
                .collect(Collectors.joining(","));
        if (categories.isEmpty()) categories = "none";
        CwpClientPayloads.send(new WorkshopHelpers.ActionPayload(action, categories));
    }

    /** F6/F7: the server opens the workshop's pocket editor or its script in the admin panel. */
    private static void openEditor(Minecraft client, String action) {
        if (client.player == null) return;
        if (!inWorkshop()) client.gui.hud.setOverlayMessage(Component.translatable(
                "message.crazy-world-progression.workshop.not_inside"), false);
        else CwpClientPayloads.send(new WorkshopHelpers.ActionPayload(action, ""));
    }

    private static void openMenu(Minecraft client) {
        if (client.player == null) return;
        if (!inWorkshop()) {
            client.gui.hud.setOverlayMessage(Component.translatable(
                    "message.crazy-world-progression.workshop.not_inside"), false);
            return;
        }
        client.gui.setScreen(new WorkshopHelperScreen(client.gui.screen()));
    }

    private static void draw(Minecraft client) {
        if (client.player == null || !inWorkshop()) return;
        List<WorkshopHelpers.Helper> current = helpers;
        if (current.isEmpty()) return;
        load();
        Vec3 eye = client.player.getEyePosition();
        try (var ignored = client.collectPerTickGizmos()) {
            for (WorkshopHelpers.Helper helper : current) {
                if (hidden.contains(helper.category())) continue;
                double distance = eye.distanceToSqr(helper.centerX(), helper.y(), helper.centerZ());
                if (distance > SHAPE_DISTANCE_SQUARED) continue;
                int color = COLORS.getOrDefault(helper.category(), 0xFF000000 | net.minecraft.util.Mth.hsvToRgb((helper.category().hashCode() & 0xFF) / 255F, 0.6F, 1F));
                int fill = 0x22000000 | color & 0xFFFFFF;
                double top;
                switch (helper.shape()) {
                    case "box" -> {
                        Gizmos.cuboid(new AABB(helper.x(), helper.y(), helper.z(), helper.maxX(), helper.maxY(),
                                helper.maxZ()), GizmoStyle.strokeAndFill(color, 2.0F, fill));
                        top = helper.maxY();
                    }
                    case "cylinder" -> {
                        float radius = (float) Math.max(0.25, helper.radius());
                        double low = helper.y() - helper.height(), high = helper.y() + helper.height();
                        GizmoStyle stroke = GizmoStyle.stroke(color, 2.0F);
                        Gizmos.circle(new Vec3(helper.x(), low, helper.z()), radius, stroke);
                        Gizmos.circle(new Vec3(helper.x(), high, helper.z()), radius, stroke);
                        for (int side = 0; side < 4; side++) {
                            double angle = side * Math.PI / 2;
                            double dx = Math.cos(angle) * radius, dz = Math.sin(angle) * radius;
                            Gizmos.line(new Vec3(helper.x() + dx, low, helper.z() + dz),
                                    new Vec3(helper.x() + dx, high, helper.z() + dz), color, 2.0F);
                        }
                        Gizmos.cuboid(marker(helper.x(), helper.y(), helper.z()),
                                GizmoStyle.strokeAndFill(color, 2.0F, fill)).setAlwaysOnTop();
                        top = high;
                    }
                    default -> {
                        Gizmos.cuboid(marker(helper.x(), helper.y(), helper.z()),
                                GizmoStyle.strokeAndFill(color, 2.0F, 0x66000000 | color & 0xFFFFFF)).setAlwaysOnTop();
                        Gizmos.line(new Vec3(helper.x(), helper.y(), helper.z()),
                                new Vec3(helper.x(), helper.y() + 1.6, helper.z()), color, 2.0F);
                        top = helper.y() + 1.6;
                    }
                }
                if (selected.contains(helper.source()))
                    Gizmos.cuboid(bounds(helper).inflate(0.08), GizmoStyle.strokeAndFill(0xFFFFFFFF, 4.0F, 0x33FFFFFF))
                            .setAlwaysOnTop();
                if (helper.direction())
                    Gizmos.arrow(new Vec3(helper.centerX(), helper.y() + 0.25, helper.centerZ()),
                            new Vec3(helper.targetX(), helper.targetY() + 0.25, helper.targetZ()), color, 2.0F);
                if (labels && distance <= LABEL_DISTANCE_SQUARED) {
                    Gizmos.billboardText(helper.name(), new Vec3(helper.centerX(), top + 0.7, helper.centerZ()),
                            TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
                    Gizmos.billboardText(helper.source(), new Vec3(helper.centerX(), top + 0.4, helper.centerZ()),
                            TextGizmo.Style.forColorAndCentered(0xFFB0B0B0).withScale(0.25F)).setAlwaysOnTop();
                }
            }
        }
    }

    private static AABB marker(double x, double y, double z) {
        return new AABB(x - 0.25, y, z - 0.25, x + 0.25, y + 0.5, z + 0.25);
    }

    /** Settings are kept per player (by UUID) in one local file. */
    private static void load() {
        Minecraft client = Minecraft.getInstance();
        String player = client.player == null ? "" : client.player.getUUID().toString();
        if (player.equals(loadedFor)) return;
        loadedFor = player;
        hidden.clear();
        labels = true;
        if (player.isEmpty() || !Files.isRegularFile(FILE)) return;
        Properties values = new Properties();
        try (InputStream stream = Files.newInputStream(FILE)) {
            values.load(stream);
            String stored = values.getProperty(player + ".hidden", "");
            if (!stored.isBlank()) hidden.addAll(Arrays.asList(stored.split(",")));
            labels = Boolean.parseBoolean(values.getProperty(player + ".labels", "true"));
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not read workshop helper settings", exception);
        }
    }

    private static void save() {
        if (loadedFor.isEmpty()) return;
        Properties values = new Properties();
        try {
            if (Files.isRegularFile(FILE)) try (InputStream stream = Files.newInputStream(FILE)) {
                values.load(stream);
            }
            values.setProperty(loadedFor + ".hidden", String.join(",", hidden));
            values.setProperty(loadedFor + ".labels", Boolean.toString(labels));
            Files.createDirectories(FILE.getParent());
            try (OutputStream stream = Files.newOutputStream(FILE)) {
                values.store(stream, "Crazy World Progression workshop helpers, per player");
            }
        } catch (IOException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not save workshop helper settings", exception);
        }
    }
}
