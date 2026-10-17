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

    /** Seconds between a wipe and the encounter reset; an encounter_end in this window is a clutch clear. */
    public static final int WIPE_DELAY_SECONDS = 5;
    private static final java.util.Map<MinecraftServer, java.util.Map<String, Long>> PENDING_WIPES = new java.util.WeakHashMap<>();

    /** Tick at which a wipe started at the given tick resets the encounter. */
    static long wipeResetTick(long wipeTick) {
        return wipeTick + WIPE_DELAY_SECONDS * 20L;
    }

    /** What happens to a pending wipe: still waiting, reset now, or none pending. */
    enum WipeState { NONE, WAITING, RESET }

    static WipeState wipeState(Long resetTick, long now) {
        if (resetTick == null) return WipeState.NONE;
        return now >= resetTick ? WipeState.RESET : WipeState.WAITING;
    }

    public static boolean wipePending(MinecraftServer server, Identifier slot) {
        return PENDING_WIPES.getOrDefault(server, java.util.Map.of()).containsKey(slot.toString());
    }

    /**
     * The wipe step (and /cwp wipe): every participant still standing is downed in place as a spectator, without
     * dying, so nothing drops. Then the same 5-second window as a natural wipe starts.
     */
    public static void forceWipe(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Identifier slot = level.dimension().identifier();
        if (!isRaid(server, slot)) throw new IllegalStateException("A wipe requires a raid instance");
        PortalData.StoredInstance instance = PortalData.get(server).instances().get(slot.toString());
        RaidData data = RaidData.get(server);
        RaidData.Attempt before = data.get(slot.toString());
        if (before == null) before = RaidData.Attempt.first();
        Map<String, RaidData.Spot> deaths = new HashMap<>(before.deaths());
        Map<String, String> modes = new HashMap<>(before.originalModes());
        for (ServerPlayer player : List.copyOf(level.players())) {
            String id = player.getUUID().toString();
            if (!instance.admittedPlayers().contains(id) || player.isDeadOrDying() || deaths.containsKey(id)) continue;
            deaths.put(id, deathSpot(player, instance));
            modes.putIfAbsent(id, player.gameMode.getGameModeForPlayer().getName());
            player.setGameMode(GameType.SPECTATOR);
        }
        data.put(slot.toString(), new RaidData.Attempt(before.number(), deaths, before.spentRevives(),
                before.portalEntries(), before.pendingRespawn(), modes, before.encounterActive()));
        scheduleWipe(server, instance);
    }

    private static void scheduleWipe(MinecraftServer server, PortalData.StoredInstance instance) {
        java.util.Map<String, Long> pending = PENDING_WIPES.computeIfAbsent(server, ignored -> new HashMap<>());
        if (pending.containsKey(instance.slotDimension().toString())) return;
        pending.put(instance.slotDimension().toString(), wipeResetTick(server.getTickCount()));
        ServerLevel level = level(server, instance.slotDimension());
        if (level != null) clearMarkers(level);
        EncounterBanner.show(onlineParticipants(server, instance), EncounterBanner.Kind.FAILED,
                Integer.toString(WIPE_DELAY_SECONDS));
    }

    private static void tickWipes(MinecraftServer server) {
        java.util.Map<String, Long> pending = PENDING_WIPES.get(server);
        if (pending == null || pending.isEmpty()) return;
        for (String slot : List.copyOf(pending.keySet())) {
            if (wipeState(pending.get(slot), server.getTickCount()) != WipeState.RESET) continue;
            pending.remove(slot);
            PortalData.StoredInstance instance = PortalData.get(server).instances().get(slot);
            RaidData.Attempt attempt = RaidData.get(server).get(slot);
            if (instance != null && attempt != null) wipe(server, instance, attempt);
        }
    }

    /** A downed raid player: their revive point and when they went down. Lost on restart (rebuilt on join). */
    private record Down(String slot, Vec3 point, long downedTick) { }
    private static final Map<MinecraftServer, Map<UUID, Down>> DOWNS = new java.util.WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Long>> LAST_REVIVE = new java.util.WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Vec3>> LAST_SAFE = new java.util.WeakHashMap<>();

    private static Map<UUID, Down> downs(MinecraftServer server) {
        return DOWNS.computeIfAbsent(server, ignored -> new HashMap<>());
    }

    /** The checkpoint spawn: the last encounter_end spawn, or the pocket's own spawn. */
    private static Vec3 rallyPoint(MinecraftServer server, PortalData.StoredInstance instance) {
        RaidData.Spot moved = spawnPoint(server, instance);
        if (moved != null) return new Vec3(moved.x(), moved.y(), moved.z());
        PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, instance.templateId());
        if (template == null) return new Vec3(instance.originX() + 0.5, instance.originY() + 1.0, instance.originZ() + 0.5);
        return new Vec3(instance.originX() + template.spawnX() + 0.5, instance.originY() + template.spawnY(),
                instance.originZ() + template.spawnZ() + 0.5);
    }

    /**
     * Raid deaths do not kill: the player is downed as a spectator at a revive point. Returns true when the
     * death was replaced this way (the death itself is then cancelled).
     */
    private static boolean down(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
        MinecraftServer server = player.level().getServer();
        Identifier slot = player.level().dimension().identifier();
        if (!isRaid(server, slot)) return false;
        PortalData.StoredInstance instance = PortalData.get(server).instances().get(slot.toString());
        String playerId = player.getUUID().toString();
        if (instance == null || !instance.admittedPlayers().contains(playerId)) return false;
        player.setHealth(player.getMaxHealth());
        if (player.isSpectator()) return true;
        ServerLevel level = player.level();
        boolean fellOut = source.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)
                || player.getY() < level.getMinY();
        boolean drowned = source.is(net.minecraft.world.damagesource.DamageTypes.DROWN);
        long now = server.getTickCount();
        Long revived = LAST_REVIVE.computeIfAbsent(server, ignored -> new HashMap<>()).get(player.getUUID());
        Vec3 point;
        if (RaidDowns.useCheckpoint(revived == null ? -1L : now - revived, fellOut || drowned)) point = rallyPoint(server, instance);
        else if (fellOut) point = LAST_SAFE.computeIfAbsent(server, ignored -> new HashMap<>())
                .getOrDefault(player.getUUID(), rallyPoint(server, instance));
        else point = player.position();
        if (Boolean.FALSE.equals(PortalService.keepInventoryOverride(player))) player.getInventory().dropAll();
        player.clearFire();
        player.setAirSupply(player.getMaxAirSupply());
        player.removeAllEffects();
        player.setDeltaMovement(Vec3.ZERO);
        RaidData data = RaidData.get(server);
        RaidData.Attempt before = data.get(slot.toString());
        if (before == null) before = RaidData.Attempt.first();
        Map<String, String> modes = new HashMap<>(before.originalModes());
        modes.putIfAbsent(playerId, player.gameMode.getGameModeForPlayer().getName());
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(level, point.x, point.y, point.z, Set.of(), player.getYRot(), player.getXRot(), false);
        player.resetFallDistance();
        downs(server).put(player.getUUID(), new Down(slot.toString(), point, now));
        RaidData.Spot spot = new RaidData.Spot(point.x, point.y, point.z);
        boolean encounter = inEncounter(server, slot);
        Map<String, RaidData.Spot> deaths = new HashMap<>(before.deaths());
        if (encounter) deaths.put(playerId, spot);
        RaidData.Attempt after = new RaidData.Attempt(before.number(), deaths, before.spentRevives(),
                before.portalEntries(), before.pendingRespawn(), modes, before.encounterActive());
        data.put(slot.toString(), after);
        if (encounter && allDown(server, instance, after)) {
            scheduleWipe(server, instance);
        } else {
            spawnMarker(player, spot);
            player.sendSystemMessage(Component.translatableWithFallback(encounter
                    ? "message.crazy-world-progression.raid.down_encounter" : "message.crazy-world-progression.raid.down",
                    encounter ? "You are down. A teammate can revive you at your marker."
                            : "You are down. Fly into your revive point after 5 seconds to get back up."));
        }
        return true;
    }

    private static void reviveSelf(ServerPlayer player, Down down) {
        MinecraftServer server = player.level().getServer();
        RaidData data = RaidData.get(server);
        RaidData.Attempt before = data.get(down.slot());
        String mode = null;
        if (before != null) {
            Map<String, String> modes = new HashMap<>(before.originalModes());
            mode = modes.remove(player.getUUID().toString());
            data.put(down.slot(), new RaidData.Attempt(before.number(), before.deaths(), before.spentRevives(),
                    before.portalEntries(), before.pendingRespawn(), modes, before.encounterActive()));
        }
        finishRevive(player, down.point(), mode);
        player.sendOverlayMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.revived",
                "You got back up."));
    }

    /** Shared end of every revive: back in the old game mode at the point, remembered for the quick-death rule. */
    private static void finishRevive(ServerPlayer player, Vec3 point, String mode) {
        MinecraftServer server = player.level().getServer();
        player.teleportTo(player.level(), point.x, point.y, point.z, Set.of(), player.getYRot(), player.getXRot(), false);
        restoreMode(player, mode);
        player.setHealth(player.getMaxHealth());
        player.resetFallDistance();
        removeMarker(player.level(), player.getUUID());
        downs(server).remove(player.getUUID());
        LAST_REVIVE.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(), (long) server.getTickCount());
    }

    /** Leash, self-revive hints and checks, and last safe ground positions for void deaths. */
    private static void tickDowns(MinecraftServer server) {
        long now = server.getTickCount();
        Map<UUID, Down> downs = downs(server);
        for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
            if (!isRaid(server, instance.slotDimension())) continue;
            ServerLevel level = level(server, instance.slotDimension());
            if (level == null) continue;
            boolean encounter = inEncounter(server, instance.slotDimension());
            RaidData.Attempt attempt = RaidData.get(server).get(instance.slotDimension().toString());
            for (ServerPlayer player : List.copyOf(level.players())) {
                if (!player.isSpectator()) {
                    if (player.onGround() && !player.isInWater() && !player.isInLava())
                        LAST_SAFE.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(), player.position());
                    continue;
                }
                Down down = downs.get(player.getUUID());
                if (down == null && attempt != null && attempt.deaths().containsKey(player.getUUID().toString())) {
                    RaidData.Spot spot = attempt.deaths().get(player.getUUID().toString());
                    down = new Down(instance.slotDimension().toString(), new Vec3(spot.x(), spot.y(), spot.z()), now);
                    downs.put(player.getUUID(), down);
                }
                if (down == null) continue;
                if (player.getCamera() != player) player.setCamera(player);
                Vec3 clamped = RaidDowns.leash(down.point(), player.position(), RaidDowns.LEASH_RADIUS);
                if (clamped != player.position())
                    player.teleportTo(level, clamped.x, clamped.y, clamped.z, Set.of(), player.getYRot(), player.getXRot(), false);
                if (encounter) continue;
                long ticksDown = now - down.downedTick();
                double distance = player.position().distanceTo(down.point());
                if (RaidDowns.canSelfRevive(false, ticksDown, distance)) {
                    reviveSelf(player, down);
                } else if (now % 10L == 0L) {
                    long seconds = (RaidDowns.SELF_REVIVE_DELAY_TICKS - ticksDown + 19L) / 20L;
                    player.sendOverlayMessage(seconds > 0
                            ? Component.translatableWithFallback("message.crazy-world-progression.raid.revive_in",
                                    "You can revive in %s s", seconds)
                            : Component.translatableWithFallback("message.crazy-world-progression.raid.revive_now",
                                    "Fly into your revive point to revive"));
                }
            }
        }
        downs.values().removeIf(down -> !PortalData.get(server).instances().containsKey(down.slot()));
    }

    /** Clears down records of one instance after a wipe or a completed encounter restored everyone. */
    private static void clearDowns(MinecraftServer server, Identifier slot) {
        downs(server).values().removeIf(down -> down.slot().equals(slot.toString()));
    }

    public static void initialize() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(RaidService::tickWipes);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(RaidService::tickDowns);
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer player) || !down(player, source));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(PENDING_WIPES::remove);
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

    /** Spawn moved by encounter_end(spawn=...), or null for the template's own spawn. */
    static RaidData.Spot spawnPoint(MinecraftServer server, PortalData.StoredInstance instance) {
        return RaidData.get(server).spawn(instance.slotDimension().toString());
    }

    /**
     * Makes this position the instance's rally, respawn and rejoin point. With movePlayers (resuming from a
     * checkpoint) everyone in the instance is moved there now.
     */
    public static void moveSpawn(ServerLevel level, double x, double y, double z, boolean movePlayers) {
        MinecraftServer server = level.getServer();
        if (!isRaid(server, level.dimension().identifier())) return;
        RaidData.get(server).spawn(level.dimension().identifier().toString(), new RaidData.Spot(x, y, z));
        if (!movePlayers) return;
        for (ServerPlayer player : List.copyOf(level.players())) {
            player.teleportTo(level, x, y, z, Set.of(), player.getYRot(), player.getXRot(), false);
            player.resetFallDistance();
        }
    }

    static void prepare(MinecraftServer server, PortalData.StoredInstance instance) {
        RaidData.get(server).spawn(instance.slotDimension().toString(), null);
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
        Map<String, RaidData.Spot> stillDown = new HashMap<>();
        for (Map.Entry<UUID, Down> entry : downs(server).entrySet())
            if (entry.getValue().slot().equals(slot.toString())) stillDown.put(entry.getKey().toString(),
                    new RaidData.Spot(entry.getValue().point().x, entry.getValue().point().y, entry.getValue().point().z));
        data.put(slot.toString(), new RaidData.Attempt(before.number(), stillDown, List.of(),
                Map.of(), before.pendingRespawn(), before.originalModes(), true));
        EncounterBanner.show(level.players(), EncounterBanner.Kind.STARTED, "");
    }

    private static boolean inEncounter(MinecraftServer server, Identifier dimension) {
        if (!isRaid(server, dimension)) return false;
        RaidData.Attempt attempt = RaidData.get(server).get(dimension.toString());
        return attempt != null && attempt.encounterActive();
    }

    static void remove(MinecraftServer server, Identifier slot) {
        java.util.Map<String, Long> pendingWipes = PENDING_WIPES.get(server);
        if (pendingWipes != null) pendingWipes.remove(slot.toString());
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
        MinecraftServer server = player.level().getServer();
        Identifier slot = player.level().dimension().identifier();
        if (!isRaid(server, slot)) return;
        RaidData.Attempt attempt = RaidData.get(server).get(slot.toString());
        String id = player.getUUID().toString();
        if (attempt != null && attempt.deaths().containsKey(id)) {
            RaidData.Spot spot = attempt.deaths().get(id);
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(player.level(), spot.x(), spot.y(), spot.z(), Set.of(), player.getYRot(), player.getXRot(), false);
            downs(server).put(player.getUUID(), new Down(slot.toString(), new Vec3(spot.x(), spot.y(), spot.z()),
                    server.getTickCount()));
        } else if (player.isSpectator() && attempt != null && attempt.originalModes().containsKey(id)
                && !downs(server).containsKey(player.getUUID())) {
            Map<String, String> modes = new HashMap<>(attempt.originalModes());
            restoreMode(player, modes.remove(id));
            RaidData.get(server).put(slot.toString(), new RaidData.Attempt(attempt.number(), attempt.deaths(),
                    attempt.spentRevives(), attempt.portalEntries(), attempt.pendingRespawn(), modes, attempt.encounterActive()));
            rally(player);
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
        if (allDown(server, instance, after)) scheduleWipe(server, instance);
        else spawnMarker(player, after.deaths().get(player.getUUID().toString()));
    }

    /** Where the revive marker goes: the death position, or the rally point when the player fell out of the world. */
    static RaidData.Spot deathSpot(ServerPlayer player, PortalData.StoredInstance instance) {
        if (player.getY() >= player.level().getMinY()) return new RaidData.Spot(player.getX(), player.getY(), player.getZ());
        RaidData.Spot moved = spawnPoint(player.level().getServer(), instance);
        if (moved != null) return moved;
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
        marker.discard();
        finishRevive(target, new Vec3(spot.x(), spot.y(), spot.z()), mode);
        reviver.sendSystemMessage(Component.literal("Revived " + target.getGameProfile().name() + ". Your revive is spent."));
        target.sendSystemMessage(Component.literal("You were revived by " + reviver.getGameProfile().name()));
    }

    private static void wipe(MinecraftServer server, PortalData.StoredInstance instance, RaidData.Attempt before) {
        ServerLevel level = level(server, instance.slotDimension());
        if (level == null) return;
        clearDowns(server, instance.slotDimension());
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
        java.util.Map<String, Long> pendingWipes = PENDING_WIPES.get(server);
        boolean clutch = pendingWipes != null && pendingWipes.remove(slot.toString()) != null;
        clearDowns(server, slot);
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
            // After a clutch clear everyone goes to the (new) spawn; otherwise only downed players do.
            if (clutch || before.deaths().containsKey(player.getUUID().toString()))
                rally(player, PortalData.get(server).instances().get(slot.toString()));
        }
        PortalData.StoredInstance current = PortalData.get(server).instances().get(slot.toString());
        EncounterBanner.show(current == null ? level.players() : onlineParticipants(server, current),
                clutch ? EncounterBanner.Kind.CLUTCH : EncounterBanner.Kind.COMPLETE, "");
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
        RaidData.Spot moved = spawnPoint(player.level().getServer(), instance);
        if (level != null && moved != null) {
            player.teleportTo(level, moved.x(), moved.y(), moved.z(), Set.of(), player.getYRot(), player.getXRot(), false);
            player.resetFallDistance();
            return;
        }
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
