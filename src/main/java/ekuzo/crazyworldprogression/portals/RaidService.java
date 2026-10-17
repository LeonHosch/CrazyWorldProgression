package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import ekuzo.crazyworldprogression.api.mobforge.MobForgeApi;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Wipe, revive, and per-attempt entry rules for private raid instances. */
public final class RaidService {
    private static final String MARKER = "cwp.raid.revive.";
    private RaidService() { }

    public static void initialize() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) onDeath(player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onRespawn(newPlayer));
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(player instanceof ServerPlayer reviver) || !(world instanceof ServerLevel)
                    || !(entity instanceof ArmorStand)) return InteractionResult.PASS;
            String tag = entity.entityTags().stream().filter(value -> value.startsWith(MARKER)).findFirst().orElse(null);
            if (tag == null) return InteractionResult.PASS;
            try { revive(reviver, UUID.fromString(tag.substring(MARKER.length())), entity); }
            catch (RuntimeException exception) { reviver.sendOverlayMessage(Component.literal(exception.getMessage())); }
            return InteractionResult.SUCCESS;
        });
    }

    static void prepare(MinecraftServer server, PortalData.StoredInstance instance) {
        RaidData.get(server).put(instance.slotDimension().toString(), RaidData.Attempt.first()
                .withActive(!hasScriptedStart(server, instance.templateId())));
    }

    private static boolean hasScriptedStart(MinecraftServer server, Identifier templateId) {
        return PocketEditorService.scripts(server, templateId).stream()
                .map(id -> ekuzo.crazyworldprogression.mobforge.MobForge.SCRIPTS.get(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .anyMatch(script -> script.steps().stream().anyMatch(
                        step -> step instanceof ekuzo.crazyworldprogression.mobforge.script.MobScript.EncounterStartStep));
    }

    public static void start(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Identifier slot = level.dimension().identifier();
        if (!isRaid(server, slot)) throw new IllegalStateException("Encounter start requires a raid instance");
        RaidData data = RaidData.get(server);
        RaidData.Attempt before = data.get(slot.toString());
        if (before == null) before = RaidData.Attempt.first();
        if (!before.deaths().isEmpty()) throw new IllegalStateException("Cannot start an encounter while players are down");
        data.put(slot.toString(), new RaidData.Attempt(before.number(), Map.of(), List.of(),
                Map.of(), before.pendingRespawn(), before.originalModes(), true));
        EncounterBanner.show(level.players(), EncounterBanner.Kind.STARTED, "");
    }

    private static boolean inEncounter(MinecraftServer server, Identifier dimension) {
        if (!isRaid(server, dimension)) return false;
        RaidData.Attempt attempt = RaidData.get(server).get(dimension.toString());
        return attempt != null && attempt.encounterActive();
    }

    static void remove(MinecraftServer server, Identifier slot) {
        ServerLevel instanceLevel = level(server, slot);
        if (instanceLevel != null && isRaid(server, slot)) clearEffects(instanceLevel);
        RaidData.Attempt attempt = RaidData.get(server).get(slot.toString());
        if (attempt != null) {
            for (String id : attempt.deaths().keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(id));
                if (player != null && !player.isDeadOrDying()) restoreMode(player, attempt.originalModes().get(id));
            }
        }
        RaidData.get(server).remove(slot.toString());
    }

    public static boolean isRaidInstance(MinecraftServer server, PortalData.StoredInstance instance) {
        return isRaid(server, instance.slotDimension());
    }

    static boolean isRaid(MinecraftServer server, Identifier dimension) {
        PortalData.StoredInstance instance = PortalData.get(server).instances().get(dimension.toString());
        PortalDefinition portal = instance == null ? null : PortalService.portal(server, instance.portalId());
        return portal != null && portal.raidMode();
    }

    static boolean canUse(ServerPlayer player, PortalDefinition portal) {
        MinecraftServer server = player.level().getServer();
        if (!inEncounter(server, player.level().dimension().identifier())) return true;
        RaidData.Attempt attempt = RaidData.get(server).get(player.level().dimension().identifier().toString());
        if (attempt == null) return true;
        if (attempt.deaths().containsKey(player.getUUID().toString())
                || attempt.pendingRespawn().contains(player.getUUID().toString())) return false;
        if (portal.isExit()) return true;
        return portal.entryPolicy() != PortalDefinition.EntryPolicy.ONCE_PER_ATTEMPT
                || !attempt.portalEntries().getOrDefault(portal.id().toString(), List.of())
                        .contains(player.getUUID().toString());
    }

    static void portalUsed(ServerPlayer player, ServerLevel source, Identifier portalId,
                           PortalDefinition.EntryPolicy policy) {
        if (policy != PortalDefinition.EntryPolicy.ONCE_PER_ATTEMPT || !inEncounter(source.getServer(), source.dimension().identifier())) return;
        RaidData data = RaidData.get(source.getServer());
        RaidData.Attempt before = data.get(source.dimension().identifier().toString());
        if (before == null) return;
        Map<String, List<String>> entries = new HashMap<>(before.portalEntries());
        List<String> players = new ArrayList<>(entries.getOrDefault(portalId.toString(), List.of()));
        if (!players.contains(player.getUUID().toString())) players.add(player.getUUID().toString());
        entries.put(portalId.toString(), players);
        data.put(source.dimension().identifier().toString(), new RaidData.Attempt(before.number(), before.deaths(),
                before.spentRevives(), entries, before.pendingRespawn(), before.originalModes(), before.encounterActive()));
    }

    /**
     * Vanilla removes a dead player's body from the world about a second after death, so an instance looks
     * empty while its only player is on the death screen. Keep it while any admitted, online player is dead
     * or is recorded as down / waiting to respawn here, whether or not the encounter is active.
     */
    public static boolean retainEmpty(MinecraftServer server, PortalData.StoredInstance instance) {
        if (!isRaid(server, instance.slotDimension())) return false;
        RaidData.Attempt attempt = RaidData.get(server).get(instance.slotDimension().toString());
        java.util.Set<String> dead = new java.util.HashSet<>();
        java.util.Set<String> online = new java.util.HashSet<>();
        for (String id : instance.admittedPlayers()) {
            ServerPlayer player;
            try { player = server.getPlayerList().getPlayer(UUID.fromString(id)); }
            catch (IllegalArgumentException ignored) { continue; }
            if (player == null) continue;
            online.add(id);
            if (player.isDeadOrDying()) dead.add(id);
        }
        return keepWhileRespawning(online, dead, attempt == null ? java.util.Set.of() : attempt.deaths().keySet(),
                attempt == null ? List.of() : attempt.pendingRespawn());
    }

    static boolean keepWhileRespawning(java.util.Set<String> online, java.util.Set<String> dead,
                                       java.util.Set<String> down, List<String> pendingRespawn) {
        for (String id : online)
            if (dead.contains(id) || down.contains(id) || pendingRespawn.contains(id)) return true;
        return false;
    }

    private static List<ServerPlayer> onlineParticipants(MinecraftServer server, PortalData.StoredInstance instance) {
        List<ServerPlayer> players = new ArrayList<>();
        for (String id : instance.admittedPlayers()) {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(id));
                if (player != null) players.add(player);
            } catch (IllegalArgumentException ignored) { }
        }
        return players;
    }

    static void onJoin(ServerPlayer player) {
        if (inEncounter(player.level().getServer(), player.level().dimension().identifier())) {
            RaidData.Attempt attempt = RaidData.get(player.level().getServer())
                    .get(player.level().dimension().identifier().toString());
            if (attempt != null && attempt.deaths().containsKey(player.getUUID().toString())) {
                player.setGameMode(GameType.SPECTATOR);
                rally(player);
            }
        }
    }

    private static void onDeath(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Identifier slot = player.level().dimension().identifier();
        if (!isRaid(server, slot)) return;
        PortalData.StoredInstance instance = PortalData.get(server).instances().get(slot.toString());
        if (instance == null || !instance.admittedPlayers().contains(player.getUUID().toString())) return;
        RaidData data = RaidData.get(server);
        RaidData.Attempt before = data.get(slot.toString());
        if (before == null) before = RaidData.Attempt.first();
        String playerId = player.getUUID().toString();
        if (!inEncounter(server, slot)) {
            // Before the encounter starts, any death (void, fall, /kill) just respawns at the raid's rally point.
            List<String> pending = new ArrayList<>(before.pendingRespawn());
            if (!pending.contains(playerId)) pending.add(playerId);
            Map<String, String> modes = new HashMap<>(before.originalModes());
            modes.putIfAbsent(playerId, player.gameMode.getGameModeForPlayer().getName());
            data.put(slot.toString(), new RaidData.Attempt(before.number(), before.deaths(), before.spentRevives(),
                    before.portalEntries(), pending, modes, before.encounterActive()));
            return;
        }
        Map<String, RaidData.Spot> deaths = new HashMap<>(before.deaths());
        deaths.put(playerId, deathSpot(player, instance));
        Map<String, String> modes = new HashMap<>(before.originalModes());
        modes.putIfAbsent(player.getUUID().toString(), player.gameMode.getGameModeForPlayer().getName());
        RaidData.Attempt after = new RaidData.Attempt(before.number(), deaths, before.spentRevives(),
                before.portalEntries(), before.pendingRespawn(), modes, before.encounterActive());
        data.put(slot.toString(), after);
        if (allDown(server, instance, after)) wipe(server, instance, after);
        else spawnMarker(player, after.deaths().get(player.getUUID().toString()));
    }

    /** Where the revive marker goes: the death position, or the rally point when the player fell out of the world. */
    static RaidData.Spot deathSpot(ServerPlayer player, PortalData.StoredInstance instance) {
        if (player.getY() >= player.level().getMinY()) return new RaidData.Spot(player.getX(), player.getY(), player.getZ());
        PortalTemplateService.TemplateInfo template = PortalTemplateService.template(player.level().getServer(),
                instance.templateId());
        if (template == null) return new RaidData.Spot(instance.originX() + 0.5, instance.originY() + 1.0,
                instance.originZ() + 0.5);
        return new RaidData.Spot(instance.originX() + template.spawnX() + 0.5, instance.originY() + template.spawnY(),
                instance.originZ() + template.spawnZ() + 0.5);
    }

    private static boolean allDown(MinecraftServer server, PortalData.StoredInstance instance, RaidData.Attempt attempt) {
        boolean participant = false;
        for (String id : instance.admittedPlayers()) {
            ServerPlayer player;
            try { player = server.getPlayerList().getPlayer(UUID.fromString(id)); }
            catch (IllegalArgumentException ignored) { continue; }
            if (player == null) continue;
            if (!player.level().dimension().identifier().equals(instance.slotDimension())
                    && !attempt.deaths().containsKey(id)) continue;
            participant = true;
            if (!attempt.deaths().containsKey(id)) return false;
        }
        return participant;
    }

    private static void onRespawn(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        String id = player.getUUID().toString();
        for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
            if (!isRaid(server, instance.slotDimension())) continue;
            RaidData data = RaidData.get(server);
            RaidData.Attempt attempt = data.get(instance.slotDimension().toString());
            if (attempt == null) continue;
            if (attempt.deaths().containsKey(id)) {
                player.setGameMode(GameType.SPECTATOR);
                rally(player, instance);
                player.sendSystemMessage(Component.literal("Your team can revive you at your death marker."));
                return;
            }
            if (attempt.pendingRespawn().contains(id)) {
                restoreMode(player, attempt.originalModes().get(id));
                rally(player, instance);
                List<String> pending = new ArrayList<>(attempt.pendingRespawn()); pending.remove(id);
                Map<String, String> modes = new HashMap<>(attempt.originalModes()); modes.remove(id);
                data.put(instance.slotDimension().toString(), new RaidData.Attempt(attempt.number(),
                        attempt.deaths(), attempt.spentRevives(), attempt.portalEntries(), pending, modes,
                        attempt.encounterActive()));
                return;
            }
        }
    }

    private static void revive(ServerPlayer reviver, UUID targetId, Entity marker) {
        MinecraftServer server = reviver.level().getServer();
        Identifier slot = reviver.level().dimension().identifier();
        if (!inEncounter(server, slot)) throw new IllegalStateException("This marker is not in an active encounter");
        RaidData data = RaidData.get(server);
        RaidData.Attempt before = data.get(slot.toString());
        String reviverId = reviver.getUUID().toString(), targetKey = targetId.toString();
        if (before == null || before.deaths().containsKey(reviverId) || reviver.isSpectator())
            throw new IllegalStateException("Only a living teammate can revive");
        if (before.spentRevives().contains(reviverId)) throw new IllegalStateException("Your revive is already spent this attempt");
        RaidData.Spot spot = before.deaths().get(targetKey);
        if (spot == null) throw new IllegalStateException("That teammate is no longer down");
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null || target.isDeadOrDying() || !target.isSpectator()
                || !target.level().dimension().identifier().equals(slot)) {
            throw new IllegalStateException("Wait for that teammate to respawn at the rally point");
        }
        if (reviver.distanceToSqr(marker) > 25.0) throw new IllegalStateException("Move closer to the revive marker");
        List<String> spent = new ArrayList<>(before.spentRevives()); spent.add(reviverId);
        Map<String, RaidData.Spot> deaths = new HashMap<>(before.deaths()); deaths.remove(targetKey);
        Map<String, String> modes = new HashMap<>(before.originalModes()); String mode = modes.remove(targetKey);
        if (!target.teleportTo((ServerLevel) reviver.level(), spot.x(), spot.y(), spot.z(), Set.of(),
                target.getYRot(), target.getXRot(), false)) {
            throw new IllegalStateException("Revive teleport was rejected");
        }
        data.put(slot.toString(), new RaidData.Attempt(before.number(), deaths, spent,
                before.portalEntries(), before.pendingRespawn(), modes, before.encounterActive()));
        restoreMode(target, mode);
        target.resetFallDistance();
        marker.discard();
        reviver.sendSystemMessage(Component.literal("Revived " + target.getGameProfile().name() + ". Your revive is spent."));
        target.sendSystemMessage(Component.literal("You were revived by " + reviver.getGameProfile().name()));
    }

    private static void wipe(MinecraftServer server, PortalData.StoredInstance instance, RaidData.Attempt before) {
        ServerLevel level = level(server, instance.slotDimension());
        if (level == null) return;
        clearEffects(level);
        clearMarkers(level);
        resetEncounter(server, instance);
        List<String> pending = new ArrayList<>();
        for (String id : before.deaths().keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(id));
            if (player != null && player.isDeadOrDying()) pending.add(id);
        }
        Map<String, String> pendingModes = new HashMap<>(before.originalModes());
        pendingModes.keySet().retainAll(pending);
        RaidData.get(server).put(instance.slotDimension().toString(), new RaidData.Attempt(before.number() + 1,
                Map.of(), List.of(), Map.of(), pending, pendingModes,
                !hasScriptedStart(server, instance.templateId())));
        for (ServerPlayer player : List.copyOf(level.players())) {
            if (player.isDeadOrDying()) continue;
            if (before.originalModes().containsKey(player.getUUID().toString()))
                restoreMode(player, before.originalModes().get(player.getUUID().toString()));
            rally(player, instance);
        }
        // Dead players get the banner too; it is still showing when they respawn quickly.
        EncounterBanner.show(onlineParticipants(server, instance), EncounterBanner.Kind.WIPED,
                Integer.toString(before.number() + 1));
    }

    /** Scripted encounter completion releases entry locks and revive charges without destroying the instance. */
    public static void complete(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Identifier slot = level.dimension().identifier();
        if (!isRaid(server, slot)) throw new IllegalStateException("Raid completion requires a raid instance");
        RaidData data = RaidData.get(server);
        RaidData.Attempt before = data.get(slot.toString());
        if (before == null) return;
        clearEffects(level);
        ekuzo.crazyworldprogression.mobforge.MobForge.SCRIPT_EXECUTOR.clearPocketState(
                PortalService.instanceId(PortalData.get(server).instances().get(slot.toString())));
        clearMarkers(level);
        List<String> pending = new ArrayList<>();
        for (String id : before.deaths().keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(id));
            if (player != null && player.isDeadOrDying()) pending.add(id);
        }
        Map<String, String> pendingModes = new HashMap<>(before.originalModes());
        pendingModes.keySet().retainAll(pending);
        data.put(slot.toString(), new RaidData.Attempt(before.number() + 1, Map.of(), List.of(), Map.of(),
                pending, pendingModes, false));
        for (ServerPlayer player : List.copyOf(level.players())) {
            if (player.isDeadOrDying()) continue;
            if (before.originalModes().containsKey(player.getUUID().toString()))
                restoreMode(player, before.originalModes().get(player.getUUID().toString()));
            if (before.deaths().containsKey(player.getUUID().toString())) rally(player, PortalData.get(server).instances().get(slot.toString()));
        }
        EncounterBanner.show(level.players(), EncounterBanner.Kind.COMPLETE, "");
    }

    private static void resetEncounter(MinecraftServer server, PortalData.StoredInstance instance) {
        UUID instanceId = PortalService.instanceId(instance);
        MobForgeApi.cancelPocketRuns(instanceId);
        ServerLevel level = level(server, instance.slotDimension());
        if (level == null) return;
        BuildingService.clearInstance(level);
        // Boats, minecarts and MobForge mobs go; dropped items stay so players keep their death drops.
        PortalTemplateService.discardEntitiesExceptDrops(level);
        PortalTemplateService.reconstruct(server, level, instance.templateId(),
                new net.minecraft.core.BlockPos(instance.originX(), instance.originY(), instance.originZ()));
        PortalService.resetRuntimeLocations(server, instance.slotDimension());
        PocketEditorService.startScripts(server, instance.templateId(), level,
                new net.minecraft.core.BlockPos(instance.originX(), instance.originY(), instance.originZ()), instanceId,
                RaidCheckpoints.resumeFor(server, instance));
    }

    private static void clearEffects(ServerLevel level) {
        for (ServerPlayer player : List.copyOf(level.players())) player.removeAllEffects();
    }

    private static void spawnMarker(ServerPlayer player, RaidData.Spot spot) {
        ServerLevel level = player.level();
        removeMarker(level, player.getUUID());
        ArmorStand stand = new ArmorStand(level, spot.x(), spot.y(), spot.z());
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setCustomName(Component.literal("Revive " + player.getGameProfile().name()));
        stand.setCustomNameVisible(true);
        stand.addTag(MARKER + player.getUUID());
        level.addFreshEntity(stand);
    }

    private static void removeMarker(ServerLevel level, UUID playerId) {
        String tag = MARKER + playerId;
        for (Entity entity : level.getAllEntities()) if (entity.entityTags().contains(tag)) entity.discard();
    }

    private static void clearMarkers(ServerLevel level) {
        for (Entity entity : level.getAllEntities())
            if (entity.entityTags().stream().anyMatch(tag -> tag.startsWith(MARKER))) entity.discard();
    }

    private static void rally(ServerPlayer player) {
        PortalData.StoredInstance instance = PortalData.get(player.level().getServer()).instances()
                .get(player.level().dimension().identifier().toString());
        if (instance != null) rally(player, instance);
    }

    private static void rally(ServerPlayer player, PortalData.StoredInstance instance) {
        ServerLevel level = level(player.level().getServer(), instance.slotDimension());
        PortalTemplateService.TemplateInfo template = PortalTemplateService.template(player.level().getServer(), instance.templateId());
        if (level == null || template == null) return;
        player.teleportTo(level, instance.originX() + template.spawnX() + 0.5,
                instance.originY() + template.spawnY(), instance.originZ() + template.spawnZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot(), false);
        player.resetFallDistance();
    }

    private static ServerLevel level(MinecraftServer server, Identifier id) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static void restoreMode(ServerPlayer player, String name) {
        player.setGameMode(name == null ? GameType.SURVIVAL : GameType.byName(name));
    }
}
