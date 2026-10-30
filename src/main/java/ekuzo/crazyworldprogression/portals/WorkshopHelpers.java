package ekuzo.crazyworldprogression.portals;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Designer helpers of a workshop (pocket editor world): event boxes, areas, spawn points, script-placed portals and
 * buildings, sensors and other positions its attached scripts use, which are invisible in normal play. Only
 * designers (creative mode or game master) inside the workshop receive them; the client draws them and filters them
 * by category, and asks the server to teleport between them.
 */
public final class WorkshopHelpers {
    /** Every category, in menu order. */
    public static final List<String> CATEGORIES = List.of("event_box", "area", "mob_spawn", "portal_spawn",
            "building_spawn", "spawn_point", "redstone", "replace_blocks", "aura", "effect", "portal", "anomaly",
            "bonus_area");
    /** World helpers (from providers) are sent within this many blocks of the player. */
    public static final double WORLD_RADIUS = 128.0;
    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Helper>>() { }.getType();
    private static final Map<UUID, String> LAST_SENT = new HashMap<>();
    /** Each designer's position in the helper list for next/previous. */
    private static final Map<UUID, Integer> CURSOR = new HashMap<>();

    private WorkshopHelpers() { }

    /**
     * One helper. {@code shape} is "point", "box" (min to max) or "cylinder" (radius around the point, height up
     * and down). With {@code direction} an arrow points from the point to the target.
     */
    public record Helper(String category, String name, String source, String shape, double x, double y, double z,
                         double maxX, double maxY, double maxZ, double radius, double height, boolean direction,
                         double targetX, double targetY, double targetZ) {
        public static Helper point(String category, String name, String source, double x, double y, double z) {
            return new Helper(category, name, source, "point", x, y, z, x, y, z, 0, 0, false, 0, 0, 0);
        }
        public static Helper box(String category, String name, String source, double x1, double y1, double z1,
                                 double x2, double y2, double z2) {
            return new Helper(category, name, source, "box", Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), 0, 0, false, 0, 0, 0);
        }
        /** {@code height} blocks up and down from the center. */
        public static Helper cylinder(String category, String name, String source, double x, double y, double z,
                                      double radius, double height) {
            return new Helper(category, name, source, "cylinder", x, y, z, x, y, z, radius, height, false, 0, 0, 0);
        }
        public Helper toward(double tx, double ty, double tz) {
            return new Helper(category, name, source, shape, x, y, z, maxX, maxY, maxZ, radius, height, true, tx, ty, tz);
        }
        Helper withSource(String replacement) {
            return new Helper(category, name, replacement, shape, x, y, z, maxX, maxY, maxZ, radius, height, direction,
                    targetX, targetY, targetZ);
        }
        /** Where a designer is taken to look at it. */
        public double centerX() { return shape.equals("box") ? (x + maxX) / 2 : x; }
        public double centerZ() { return shape.equals("box") ? (z + maxZ) / 2 : z; }
    }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.clientboundPlay().register(HelpersPayload.TYPE, HelpersPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenMenuPayload.TYPE, OpenMenuPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MovePayload.TYPE, MovePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) ->
                handle(context.player(), payload.action(), payload.categories()));
        ServerPlayNetworking.registerGlobalReceiver(MovePayload.TYPE, (payload, context) ->
                move(context.player(), payload.helpers(), payload.dx(), payload.dy(), payload.dz()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LAST_SENT.clear();
            CURSOR.clear();
        });
    }

    public static List<Helper> decode(String json) {
        List<Helper> values = GSON.fromJson(json, LIST_TYPE);
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * Objects placed in a world that the helpers show and move outside scripts, such as portals or anomalies. A
     * content mod registers one per kind in its initializer (CWP registers portals, anomalies and bonus areas).
     */
    public interface Provider {
        /** This kind's helpers in the level within {@code radius} of {@code center}; sources are this kind's IDs. */
        List<Helper> helpers(ServerLevel level, Vec3 center, double radius);

        /** Moves the object with this ID by whole blocks and saves it; false when there is none or it cannot move. */
        boolean move(ServerLevel level, String id, int dx, int dy, int dz);
    }

    /** Registers a kind of world helper under a key without ':' (a helper's source becomes "key:id"). */
    public static void registerProvider(String key, Provider provider) {
        if (key.contains(":") || PROVIDERS.putIfAbsent(key, provider) != null)
            throw new IllegalArgumentException("Invalid or duplicate helper provider " + key);
    }

    /** Builders design workshops (see {@link ekuzo.crazyworldprogression.CwpPermissions#isBuilder}). */
    public static boolean designer(ServerPlayer player) {
        return ekuzo.crazyworldprogression.CwpPermissions.isBuilder(player);
    }

    /** The workshop the player designs in right now, or null. */
    public static Identifier workshop(ServerPlayer player) {
        if (!designer(player)) return null;
        return PocketEditorService.pocketForDimension(player.level().getServer(),
                player.level().dimension().identifier());
    }

    /**
     * Whether the player sees helpers where they are: builders in a workshop, and builders in creative anywhere else
     * except instance worlds.
     */
    public static boolean seesHelpers(ServerPlayer player) {
        Identifier dimension = player.level().dimension().identifier();
        if (!designer(player) || PortalRegistry.instanceSlots().contains(dimension)) return false;
        return workshop(player) != null || player.isCreative();
    }

    /** Everything the player sees: the workshop's script helpers, then world helpers nearby. */
    public static List<Helper> helpersFor(ServerPlayer player) {
        if (!seesHelpers(player)) return List.of();
        List<Helper> result = new ArrayList<>();
        Identifier pocket = workshop(player);
        if (pocket != null) result.addAll(helpers(player.level().getServer(), pocket));
        result.addAll(worldHelpers(player));
        return result;
    }

    private static List<Helper> worldHelpers(ServerPlayer player) {
        List<Helper> result = new ArrayList<>();
        PROVIDERS.forEach((key, provider) -> {
            try {
                for (Helper helper : provider.helpers(player.level(), player.position(), WORLD_RADIUS))
                    result.add(helper.withSource(key + ":" + helper.source()));
            } catch (RuntimeException exception) {
                CrazyWorldProgression.LOGGER.warn("Helper provider {} failed", key, exception);
            }
        });
        return result;
    }

    /** Sends designers in a workshop its helpers, and clears them for everyone else who had them. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) send(server);
    }

    private static void send(MinecraftServer server) {
        LAST_SENT.keySet().retainAll(server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        CURSOR.keySet().retainAll(LAST_SENT.keySet());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!seesHelpers(player)) {
                if (LAST_SENT.remove(player.getUUID()) != null)
                    ekuzo.crazyworldprogression.CwpPayloads.send(player, new HelpersPayload("", "[]"));
                continue;
            }
            String dimension = player.level().dimension().identifier().toString();
            String json = GSON.toJson(helpersFor(player));
            String key = dimension + "|" + json;
            if (key.equals(LAST_SENT.get(player.getUUID()))) continue;
            if (ServerPlayNetworking.canSend(player, HelpersPayload.TYPE)) {
                ServerPlayNetworking.send(player, new HelpersPayload(dimension, json));
                LAST_SENT.put(player.getUUID(), key);
            }
        }
    }

    /** Opens the helper menu on the player's game. */
    public static boolean openMenu(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, OpenMenuPayload.TYPE)) return false;
        ServerPlayNetworking.send(player, new OpenMenuPayload());
        return true;
    }

    /** Every helper of the pocket's attached scripts, in script and step order, after the workshop entry. */
    public static List<Helper> helpers(MinecraftServer server, Identifier pocket) {
        List<Helper> result = new ArrayList<>();
        BlockPos entry = PocketEditorService.entry(server, pocket);
        result.add(Helper.point("spawn_point", "Workshop entry", "pocket", entry.getX() + 0.5, entry.getY(),
                entry.getZ() + 0.5));
        for (String scriptId : PocketEditorService.scripts(server, pocket)) {
            MobScript script = MobForge.SCRIPTS.get(scriptId).orElse(null);
            if (script == null) continue;
            List<MobScript.Step> steps = script.steps();
            for (int index = 0; index < steps.size(); index++) {
                Helper helper = helper(server, steps.get(index), scriptId + " #" + (index + 1));
                if (helper != null) result.add(helper);
            }
        }
        return result;
    }

    private static Helper helper(MinecraftServer server, MobScript.Step step, String source) {
        if (step instanceof MobScript.EventBoxStep box)
            return Helper.box("event_box", box.name() + (box.mode().equals("players") ? "" : " (" + box.mode() + ")")
                            + (box.charging() == null ? "" : " (platform " + box.charging().stackId() + ")")
                            + (box.delivery() == null ? "" : " (delivery)"),
                    source, box.min().x(), box.min().y(), box.min().z(),
                    box.max().x() + 1, box.max().y() + 1, box.max().z() + 1);
        if (step instanceof MobScript.AreaStep area)
            return new Helper("area", area.name(), source, "cylinder", area.center().x(), area.center().y(),
                    area.center().z(), area.center().x(), area.center().y(), area.center().z(), area.radius(),
                    area.height(), false, 0, 0, 0);
        if (step instanceof MobScript.SpawnStep spawn) {
            Helper helper = Helper.point("mob_spawn", spawn.template() + count(spawn.count()) + tag(spawn.tag()),
                    source, spawn.location().x(), spawn.location().y(), spawn.location().z());
            var target = spawn.aiOverride() == null ? null : spawn.aiOverride().target();
            return target == null ? helper : helper.toward(target.x(), target.y(), target.z());
        }
        if (step instanceof MobScript.SpawnMobsStep spawn)
            return Helper.point("mob_spawn", spawn.mobId() + count(spawn.count()) + tag(spawn.tag()), source,
                    spawn.location().x(), spawn.location().y(), spawn.location().z());
        if (step instanceof MobScript.PortalLocationStep portal && portal.action().equals("add")) {
            Helper helper = Helper.point("portal_spawn", portal.portalId()
                            + (portal.locationId().isEmpty() ? "" : "/" + portal.locationId()), source,
                    portal.position().x(), portal.position().y(), portal.position().z());
            return portal.target() == null ? helper
                    : helper.toward(portal.target().x(), portal.target().y(), portal.target().z());
        }
        if (step instanceof MobScript.BuildingStep building) {
            BuildingService.BuildingInfo info = BuildingService.building(server, building.buildId());
            BlockPos origin = BlockPos.containing(building.location().x(), building.location().y(),
                    building.location().z());
            String name = building.buildId() + " (" + building.action()
                    + (BuildingService.replaces(building.mode()) ? ", replace" : "") + ")";
            if (info == null) return Helper.point("building_spawn", name + " - unknown building", source,
                    origin.getX(), origin.getY(), origin.getZ());
            return Helper.box("building_spawn", name, source, origin.getX(), origin.getY(), origin.getZ(),
                    origin.getX() + info.sizeX(), origin.getY() + info.sizeY(), origin.getZ() + info.sizeZ());
        }
        if (step instanceof MobScript.MobHideStep hide && hide.location() != null)
            return Helper.point("mob_spawn", hide.tag() + (hide.hidden() ? " (hidden here)" : " (shown here)"), source,
                    hide.location().x(), hide.location().y(), hide.location().z());
        if (step instanceof MobScript.EncounterEndStep end && end.spawn() != null)
            return Helper.point("spawn_point", "Encounter end spawn", source, end.spawn().x(), end.spawn().y(),
                    end.spawn().z());
        if (step instanceof MobScript.FinishInstanceStep finish && finish.spawn() != null)
            return Helper.point("spawn_point", "Finish spawn", source, finish.spawn().x(), finish.spawn().y(),
                    finish.spawn().z());
        if (step instanceof MobScript.RedstoneStep sensor)
            return Helper.box("redstone", sensor.name(), source, Math.floor(sensor.position().x()),
                    Math.floor(sensor.position().y()), Math.floor(sensor.position().z()),
                    Math.floor(sensor.position().x()) + 1, Math.floor(sensor.position().y()) + 1,
                    Math.floor(sensor.position().z()) + 1);
        if (step instanceof MobScript.ReplaceBlocksStep replace && replace.min() != null && replace.max() != null)
            return Helper.box("replace_blocks", replace.from() + " -> " + replace.to(), source,
                    replace.min().x(), replace.min().y(), replace.min().z(),
                    replace.max().x() + 1, replace.max().y() + 1, replace.max().z() + 1);
        if (step instanceof MobScript.AuraStep aura && aura.center() != null && !"remove".equals(aura.action()))
            return new Helper("aura", aura.name() + " (" + aura.effectId() + ")", source, "cylinder",
                    aura.center().x(), aura.center().y(), aura.center().z(), aura.center().x(), aura.center().y(),
                    aura.center().z(), aura.radius(), 1, false, 0, 0, 0);
        if (step instanceof MobScript.ParticleStep particles)
            return Helper.point("effect", "particles " + particles.particleId(), source, particles.location().x(),
                    particles.location().y(), particles.location().z());
        if (step instanceof MobScript.ExplosionStep explosion)
            return Helper.point("effect", "explosion " + explosion.power(), source, explosion.location().x(),
                    explosion.location().y(), explosion.location().z());
        if (step instanceof MobScript.SoundStep sound && sound.location() != null)
            return Helper.point("effect", "sound " + sound.soundId(), source, sound.location().x(),
                    sound.location().y(), sound.location().z());
        return null;
    }

    /**
     * Moves helpers by whole blocks and saves their scripts. Only the helper's own position moves (both corners of a
     * box); targets an arrow points at (mob AI target, portal target) are absolute and stay. Helpers are named by
     * their source: "pocket" for the workshop entry, otherwise "script_id #step".
     */
    private static void move(ServerPlayer player, List<String> refs, int dx, int dy, int dz) {
        MinecraftServer server = player.level().getServer();
        if (!seesHelpers(player) || refs.isEmpty() || refs.size() > 256
                || Math.abs(dx) > 16 || Math.abs(dy) > 16 || Math.abs(dz) > 16 || (dx | dy | dz) == 0) return;
        Identifier pocket = workshop(player);
        List<String> attached = pocket == null ? List.of() : PocketEditorService.scripts(server, pocket);
        Set<String> nearby = null;
        Map<String, Map<Integer, MobScript.Step>> changes = new LinkedHashMap<>();
        int moved = 0;
        for (String ref : new java.util.LinkedHashSet<>(refs)) {
            int colon = ref.indexOf(':');
            if (colon > 0) {
                // A world helper: only one the player can see right now, moved by its provider.
                if (nearby == null) nearby = worldHelpers(player).stream().map(Helper::source)
                        .collect(java.util.stream.Collectors.toSet());
                Provider provider = PROVIDERS.get(ref.substring(0, colon));
                try {
                    if (provider != null && nearby.contains(ref)
                            && provider.move(player.level(), ref.substring(colon + 1), dx, dy, dz)) moved++;
                } catch (RuntimeException exception) {
                    player.sendSystemMessage(Component.translatable("message.crazy-world-progression.workshop.move_failed",
                            ref, exception.getMessage() == null ? exception.getClass().getSimpleName()
                                    : exception.getMessage()).withStyle(ChatFormatting.RED));
                }
                continue;
            }
            if (pocket == null) continue;
            if (ref.equals("pocket")) {
                PocketEditorService.setEntry(server, pocket, PocketEditorService.entry(server, pocket).offset(dx, dy, dz));
                moved++;
                continue;
            }
            int hash = ref.lastIndexOf(" #");
            if (hash < 0) continue;
            String scriptId = ref.substring(0, hash);
            int index;
            try { index = Integer.parseInt(ref.substring(hash + 2)) - 1; }
            catch (NumberFormatException exception) { continue; }
            MobScript script = attached.contains(scriptId) ? MobForge.SCRIPTS.get(scriptId).orElse(null) : null;
            if (script == null || index < 0 || index >= script.steps().size()) continue;
            MobScript.Step shifted = shifted(script.steps().get(index), dx, dy, dz);
            if (shifted == null) continue;
            changes.computeIfAbsent(scriptId, ignored -> new LinkedHashMap<>()).put(index, shifted);
            moved++;
        }
        ekuzo.crazyworldprogression.mobforge.script.MobScriptCode code =
                new ekuzo.crazyworldprogression.mobforge.script.MobScriptCode();
        for (Map.Entry<String, Map<Integer, MobScript.Step>> entry : changes.entrySet()) {
            try {
                MobForge.SCRIPTS.saveSource(code.replaceSteps(MobForge.SCRIPTS.source(entry.getKey()), entry.getValue()));
            } catch (Exception exception) {
                moved -= entry.getValue().size();
                player.sendSystemMessage(Component.translatable("message.crazy-world-progression.workshop.move_failed",
                        entry.getKey(), exception.getMessage() == null ? exception.getClass().getSimpleName()
                                : exception.getMessage()).withStyle(ChatFormatting.RED));
            }
        }
        if (moved > 0) {
            player.sendOverlayMessage(Component.translatable("message.crazy-world-progression.workshop.moved",
                    moved, dx, dy, dz));
            LAST_SENT.clear();
            send(server);
        }
    }

    /** The step with its own position shifted, or null when it has none the helpers show. */
    static MobScript.Step shifted(MobScript.Step step, int dx, int dy, int dz) {
        java.util.function.UnaryOperator<MobScript.Position> by = point -> point == null ? null
                : new MobScript.Position(point.x() + dx, point.y() + dy, point.z() + dz);
        if (step instanceof MobScript.EventBoxStep box) return box.at(by.apply(box.min()), by.apply(box.max()));
        if (step instanceof MobScript.MobHideStep hide && hide.location() != null)
            return new MobScript.MobHideStep(hide.tag(), hide.hidden(), by.apply(hide.location()));
        if (step instanceof MobScript.AreaStep area) return new MobScript.AreaStep(area.name(), by.apply(area.center()),
                area.radius(), area.height(), area.graceTicks(), area.playerVariable(), area.countVariable(),
                area.defendRadius());
        if (step instanceof MobScript.SpawnStep spawn) return new MobScript.SpawnStep(spawn.template(),
                by.apply(spawn.location()), spawn.offset(), spawn.count(), spawn.aiOverride(), spawn.tag());
        if (step instanceof MobScript.SpawnMobsStep spawn) return spawn.at(by.apply(spawn.location()));
        if (step instanceof MobScript.PortalLocationStep portal && portal.action().equals("add"))
            return new MobScript.PortalLocationStep(portal.portalId(), portal.action(), portal.locationId(),
                    by.apply(portal.position()), portal.target());
        if (step instanceof MobScript.BuildingStep building) return new MobScript.BuildingStep(building.buildId(),
                building.action(), by.apply(building.location()), building.mode());
        if (step instanceof MobScript.EncounterEndStep end && end.spawn() != null)
            return new MobScript.EncounterEndStep(by.apply(end.spawn()));
        if (step instanceof MobScript.FinishInstanceStep finish && finish.spawn() != null)
            return new MobScript.FinishInstanceStep(by.apply(finish.spawn()));
        if (step instanceof MobScript.RedstoneStep sensor)
            return new MobScript.RedstoneStep(sensor.name(), by.apply(sensor.position()));
        if (step instanceof MobScript.ReplaceBlocksStep replace && replace.min() != null && replace.max() != null)
            return new MobScript.ReplaceBlocksStep(replace.from(), replace.to(), replace.area(),
                    by.apply(replace.min()), by.apply(replace.max()));
        if (step instanceof MobScript.AuraStep aura && aura.center() != null && !"remove".equals(aura.action()))
            return new MobScript.AuraStep(aura.name(), aura.effectId(), by.apply(aura.center()), aura.radius(),
                    aura.intervalTicks(), aura.maxStacks(), aura.durationTicks(), aura.action());
        if (step instanceof MobScript.ParticleStep particles) return new MobScript.ParticleStep(particles.particleId(),
                by.apply(particles.location()), particles.offset(), particles.count(), particles.speed());
        if (step instanceof MobScript.ExplosionStep explosion)
            return new MobScript.ExplosionStep(by.apply(explosion.location()), explosion.power());
        if (step instanceof MobScript.SoundStep sound && sound.location() != null)
            return new MobScript.SoundStep(sound.soundId(), by.apply(sound.location()));
        return null;
    }

    private static String count(int count) { return count > 1 ? " x" + count : ""; }
    private static String tag(String tag) { return tag.isEmpty() ? "" : " [" + tag + "]"; }

    /** "next", "previous", "nearest" or "list" for the designer's workshop, over the given categories. */
    private static void handle(ServerPlayer player, String action, String categories) {
        if (!seesHelpers(player)) {
            player.sendSystemMessage(Component.translatable("message.crazy-world-progression.workshop.not_inside")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (action.equals("pocket") || action.equals("script")) {
            Identifier pocket = workshop(player);
            if (pocket == null) player.sendSystemMessage(Component.translatable(
                    "message.crazy-world-progression.workshop.workshop_only").withStyle(ChatFormatting.RED));
            else openEditor(player, pocket, action.equals("script"));
            return;
        }
        List<Helper> all = helpersFor(player);
        Set<String> wanted = categories.isBlank()
                ? all.stream().map(Helper::category).collect(java.util.stream.Collectors.toSet())
                : Set.of(categories.split(","));
        List<Helper> helpers = all.stream().filter(helper -> wanted.contains(helper.category())).toList();
        switch (action) {
            case "list" -> list(player, helpers);
            case "next", "previous", "nearest" -> jump(player, helpers, action);
            default -> { }
        }
    }

    /**
     * Opens the workshop's pocket editor, or its first attached script (the pocket editor lists the others). The admin
     * panel needs a game master.
     */
    private static void openEditor(ServerPlayer player, Identifier pocket, boolean script) {
        List<String> scripts = PocketEditorService.scripts(player.level().getServer(), pocket);
        if (script && scripts.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.crazy-world-progression.workshop.no_script")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        boolean opened = script
                ? ekuzo.crazyworldprogression.admin.CwpAdminModule.openPocketScript(player, pocket, scripts.getFirst())
                : ekuzo.crazyworldprogression.admin.CwpAdminModule.openPocketEditor(player, pocket);
        if (!opened) player.sendSystemMessage(Component.translatable(
                "message.crazy-world-progression.workshop.needs_admin").withStyle(ChatFormatting.RED));
        else if (script && scripts.size() > 1) player.sendSystemMessage(Component.translatable(
                "message.crazy-world-progression.workshop.first_script", scripts.size(), scripts.getFirst())
                .withStyle(ChatFormatting.GRAY));
    }

    private static void list(ServerPlayer player, List<Helper> helpers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Helper helper : helpers) counts.merge(helper.category(), 1, Integer::sum);
        player.sendSystemMessage(Component.translatable("message.crazy-world-progression.workshop.list",
                helpers.size()).withStyle(ChatFormatting.GOLD));
        counts.forEach((category, count) -> player.sendSystemMessage(Component.literal(" ")
                .append(Component.translatable("screen.crazy-world-progression.workshop.category." + category))
                .append(": " + count).withStyle(ChatFormatting.GRAY)));
        int shown = 0;
        for (Helper helper : helpers) {
            if (shown++ >= 40) {
                player.sendSystemMessage(Component.literal(" ...").withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            String coordinates = String.format(Locale.ROOT, "%.0f %.0f %.0f", helper.centerX(), helper.y(),
                    helper.centerZ());
            player.sendSystemMessage(Component.literal(" - " + helper.name() + " @ " + coordinates)
                    .withStyle(style -> style.withColor(ChatFormatting.WHITE)
                            .withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + coordinates)))
                    .append(Component.literal("  " + helper.source()).withStyle(ChatFormatting.DARK_GRAY)));
        }
    }

    private static void jump(ServerPlayer player, List<Helper> helpers, String action) {
        if (helpers.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.crazy-world-progression.workshop.none")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        int index;
        if (action.equals("nearest")) {
            index = helpers.indexOf(helpers.stream().min(Comparator.comparingDouble(helper ->
                    player.distanceToSqr(helper.centerX(), helper.y(), helper.centerZ()))).orElseThrow());
        } else {
            int current = CURSOR.getOrDefault(player.getUUID(), -1);
            int step = action.equals("next") ? 1 : -1;
            index = Math.floorMod((current < 0 && step < 0 ? 0 : current) + step, helpers.size());
        }
        CURSOR.put(player.getUUID(), index);
        Helper helper = helpers.get(index);
        if (!action.equals("nearest"))
            player.teleportTo(player.level(), helper.centerX(), helper.y(), helper.centerZ(), Set.of(),
                    player.getYRot(), player.getXRot(), false);
        player.sendSystemMessage(Component.translatable("message.crazy-world-progression.workshop.at",
                index + 1, helpers.size(),
                Component.translatable("screen.crazy-world-progression.workshop.category." + helper.category()),
                helper.name(), helper.source()).withStyle(ChatFormatting.AQUA));
    }

    /** /cwp workshop: menu, list, next, previous, nearest, each optionally limited to one category. */
    public static LiteralArgumentBuilder<CommandSourceStack> commandBranch() {
        return Commands.literal("workshop")
                .executes(context -> openMenu(context.getSource().getPlayerOrException()) ? 1 : 0)
                .then(Commands.literal("menu").executes(context ->
                        openMenu(context.getSource().getPlayerOrException()) ? 1 : 0))
                .then(action("list")).then(action("next")).then(action("previous")).then(action("nearest"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> action(String action) {
        return Commands.literal(action).executes(context -> run(context, action, ""))
                .then(Commands.argument("category", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(CATEGORIES, builder))
                        .executes(context -> run(context, action, StringArgumentType.getString(context, "category"))));
    }

    private static int run(CommandContext<CommandSourceStack> context, String action, String category)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        handle(context.getSource().getPlayerOrException(), action, category);
        return 1;
    }

    /** Server to client: the workshop's helpers; an empty dimension clears them. */
    public record HelpersPayload(String dimension, String json) implements CustomPacketPayload {
        public static final Type<HelpersPayload> TYPE = new Type<>(CrazyWorldProgression.id("workshop_helpers"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HelpersPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HelpersPayload::dimension,
                ByteBufCodecs.stringUtf8(1_048_576), HelpersPayload::json, HelpersPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server to client: open the helper menu (/cwp workshop, admin panel button). */
    public record OpenMenuPayload() implements CustomPacketPayload {
        public static final Type<OpenMenuPayload> TYPE = new Type<>(CrazyWorldProgression.id("workshop_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenuPayload> CODEC =
                StreamCodec.unit(new OpenMenuPayload());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: move the named helpers (their sources) by whole blocks. */
    public record MovePayload(List<String> helpers, int dx, int dy, int dz) implements CustomPacketPayload {
        public static final Type<MovePayload> TYPE = new Type<>(CrazyWorldProgression.id("workshop_move"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MovePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(256)), MovePayload::helpers,
                ByteBufCodecs.VAR_INT, MovePayload::dx, ByteBufCodecs.VAR_INT, MovePayload::dy,
                ByteBufCodecs.VAR_INT, MovePayload::dz, MovePayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: next, previous, nearest or list, over comma-separated categories (blank for all). */
    public record ActionPayload(String action, String categories) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(CrazyWorldProgression.id("workshop_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), ActionPayload::action,
                ByteBufCodecs.stringUtf8(1024), ActionPayload::categories, ActionPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
