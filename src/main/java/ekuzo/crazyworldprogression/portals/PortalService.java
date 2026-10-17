package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.buildings.BuildingService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Authoritative portal scheduling, entry detection, slot reconstruction, evacuation, cleanup, and reuse. */
public final class PortalService {
    private static final double TRIGGER_THICKNESS = 0.85;
    private static final double GROUP_ENTRY_DISTANCE = 20.0;
    private static final long PROMPT_LIFETIME_TICKS = 600L;
    private static final long COORDINATE_TRAVEL_COOLDOWN_TICKS = 20L;
    private static final int CLEANUP_BLOCKS_PER_TICK = 8_192;
    private static final BlockPos INSTANCE_CENTER = new BlockPos(0, 64, 0);
    private static final Map<MinecraftServer, Map<Identifier, List<PortalDefinition>>> PORTALS_BY_DIMENSION =
            new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, String>> PORTAL_CONTACTS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<Identifier, Map<Identifier, PlacementState>>> RUNTIME_LOCATIONS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, PendingEntry>> PENDING_ENTRIES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<String, Map<UUID, Long>>> RISK_APPROVALS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Long>> COORDINATE_TRAVEL_TICKS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, java.util.ArrayDeque<ReturnPoint>>> RETURN_POINTS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<Identifier, CleanupTask>> CLEANUPS = new WeakHashMap<>();
    /** How long an empty private instance waits for its party to come back (disconnects, /leave). */
    public static final long REJOIN_GRACE_TICKS = 5L * 60L * 20L;
    private static final Map<MinecraftServer, Map<Identifier, Long>> EMPTY_SINCE = new WeakHashMap<>();

    // Prevent instantiation of the server portal service.
    private PortalService() {
    }

    // Register server lifecycle restoration and lightweight portal enforcement ticks.
    public static void initialize() {
        RaidService.initialize();
        InstanceEntityLeases.initialize();
        EncounterBanner.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            rebuildIndex(server);
            expireDueInstances(server, Instant.now().toEpochMilli());
            for (ActiveInstance instance : activeInstances(server)) {
                PortalDefinition portal = portal(server, instance.portalId());
                if (portal != null) PortalEnvironmentService.restore(server, instance, portal.environment());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PocketEditorService.clearAutoSaves(server);
            synchronized (PORTALS_BY_DIMENSION) {
                PORTALS_BY_DIMENSION.remove(server);
            }
            synchronized (PORTAL_CONTACTS) {
                PORTAL_CONTACTS.remove(server);
            }
            synchronized (RUNTIME_LOCATIONS) { RUNTIME_LOCATIONS.remove(server); }
            synchronized (PENDING_ENTRIES) {
                PENDING_ENTRIES.remove(server);
            }
            synchronized (RISK_APPROVALS) { RISK_APPROVALS.remove(server); }
            synchronized (COORDINATE_TRAVEL_TICKS) {
                COORDINATE_TRAVEL_TICKS.remove(server);
            }
            synchronized (RETURN_POINTS) {
                RETURN_POINTS.remove(server);
            }
            synchronized (CLEANUPS) {
                CLEANUPS.remove(server);
            }
            PortalNetworking.clear(server);
            PortalGroups.clear(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(PocketEditorService::flushAutoSaves);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            Identifier current = player.level().dimension().identifier();
            boolean orphanInstance = PortalRegistry.instanceSlots().contains(current)
                    && !PortalData.get(server).instances().containsKey(current.toString());
            PortalData.StoredInstance currentInstance = PortalData.get(server).instances().get(current.toString());
            boolean unauthorizedInstance = currentInstance != null
                    && !currentInstance.admittedPlayers().isEmpty()
                    && !currentInstance.admittedPlayers().contains(player.getUUID().toString());
            boolean partyMember = currentInstance != null && !currentInstance.admittedPlayers().isEmpty()
                    && !unauthorizedInstance;
            if ((PortalRegistry.instanceSlots().contains(current)
                    && ((!RaidService.isRaid(server, current) && !partyMember) || unauthorizedInstance))
                    || current.equals(CrazyWorldProgression.id("portal_workshop"))
                    || (PocketEditorService.isEditorDimension(current)
                    && !PocketEditorService.isAssignedDimension(server, current))) {
                teleportBack(server, player);
                if ((orphanInstance || unauthorizedInstance) && player.isSpectator())
                    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            }
            RaidService.onJoin(player);
            PortalNetworking.send(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            clearEntryState(server, handler.getPlayer().getUUID());
            PortalGroups.leave(handler.getPlayer());
        });
        ServerTickEvents.END_SERVER_TICK.register(PortalService::tick);
    }

    // Return all placed portals in deterministic identifier order.
    public static List<PortalDefinition> portals(MinecraftServer server) {
        return PortalData.get(server).portals().values().stream()
                .sorted(Comparator.comparing(portal -> portal.id().toString())).toList();
    }

    // Resolve one placed portal by identifier.
    public static PortalDefinition portal(MinecraftServer server, Identifier id) {
        return PortalData.get(server).portals().get(id.toString());
    }

    /** Named, persistent placements of a shared portal definition. */
    public static Map<String, PortalPlacement> locations(MinecraftServer server, Identifier id) {
        Map<String, PortalPlacement> result = new java.util.LinkedHashMap<>();
        PortalData.get(server).locations(id).forEach((name, location) -> result.put(name,
                new PortalPlacement(location.dimension(), location.x(), location.y(), location.z())));
        return java.util.Collections.unmodifiableMap(result);
    }

    public record PortalPlacement(Identifier dimension, double x, double y, double z) { }

    public static void addLocation(MinecraftServer server, Identifier id, String name,
                                   Identifier dimension, Vec3 center) {
        if (portal(server, id) == null) throw new IllegalArgumentException("Unknown portal: " + id);
        if (name == null || !name.matches("[a-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Location name must use lowercase letters, numbers, _ or -");
        if (locations(server, id).containsKey(name))
            throw new IllegalArgumentException("Portal location already exists: " + name);
        setLocation(server, id, name, dimension, center);
    }

    public static void setLocation(MinecraftServer server, Identifier id, String name,
                                   Identifier dimension, Vec3 center) {
        if (portal(server, id) == null) throw new IllegalArgumentException("Unknown portal: " + id);
        if (name == null || !name.matches("[a-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Location name must use lowercase letters, numbers, _ or -");
        PortalData.get(server).putLocation(id, name,
                new PortalData.PortalLocation(dimension, center.x, center.y, center.z));
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
    }

    public static boolean removeLocation(MinecraftServer server, Identifier id, String name) {
        if (!PortalData.get(server).removeLocation(id, name)) return false;
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
        return true;
    }

    // Persist a new directly template-linked portal and refresh its dimension index.
    public static void create(MinecraftServer server, PortalDefinition portal) {
        createUnplaced(server, portal);
        PortalData.get(server).putLocation(portal.id(), "original", new PortalData.PortalLocation(
                portal.dimension(), portal.centerX(), portal.centerY(), portal.centerZ()));
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
    }

    public static void createUnplaced(MinecraftServer server, PortalDefinition portal) {
        if (portal(server, portal.id()) != null) throw new IllegalArgumentException("Portal already exists: " + portal.id());
        validateDestination(server, portal);
        PortalData.get(server).putPortal(portal);
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
    }

    // Replace an existing portal after validating its linked template.
    public static void update(MinecraftServer server, PortalDefinition portal) {
        PortalDefinition old = portal(server, portal.id());
        if (old == null) throw new IllegalArgumentException("Unknown portal: " + portal.id());
        if ((!old.templateId().equals(portal.templateId())
                || old.admission().mode() != portal.admission().mode()
                || old.raidMode() != portal.raidMode())
                && PortalData.get(server).instances().values().stream()
                        .anyMatch(instance -> instance.portalId().equals(portal.id()))) {
            throw new IllegalStateException("Expire this portal's active instances before changing its destination");
        }
        validateDestination(server, portal);
        PortalData.get(server).putPortal(portal);
        rebuildIndex(server);
        if (!old.environment().equals(portal.environment())) {
            for (ActiveInstance instance : activeInstances(server)) {
                if (!instance.portalId().equals(portal.id())) continue;
                if (old.environment().daytime() != portal.environment().daytime()
                        || old.environment().dayNightCycle() != portal.environment().dayNightCycle()) {
                    PortalEnvironmentService.updateTime(server, instance, old.environment(), portal.environment());
                }
                if (old.environment().weather() != portal.environment().weather()
                        || old.environment().weatherCycle() != portal.environment().weatherCycle()) {
                    PortalEnvironmentService.applyWeather(server, instance, portal.environment());
                }
            }
        }
        PortalNetworking.broadcast(server);
    }

    // Remove a portal after safely expiring any template instance it currently owns.
    public static boolean remove(MinecraftServer server, Identifier id) {
        if (portal(server, id) == null) return false;
        expire(server, id);
        PortalData.get(server).removePortal(id);
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
        return true;
    }

    // Return persisted active instance leases in stable portal-ID order.
    public static List<ActiveInstance> activeInstances(MinecraftServer server) {
        return PortalData.get(server).instances().values().stream()
                .sorted(Comparator.comparing(instance -> instance.portalId().toString()))
                .map(PortalService::view).toList();
    }

    public static List<PortalDefinition> visiblePortals(MinecraftServer server) {
        return portalIndex(server).values().stream().flatMap(List::stream)
                .sorted(Comparator.comparing((PortalDefinition portal) -> portal.dimension().toString())
                        .thenComparing(portal -> portal.id().toString())).toList();
    }

    /** Null outside a portal instance so the world's normal keepInventory rule applies. */
    public static Boolean keepInventoryOverride(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        PortalData.StoredInstance instance = PortalData.get(server).instances()
                .get(player.level().dimension().identifier().toString());
        if (instance == null) return null;
        PortalDefinition source = portal(server, instance.portalId());
        return source == null ? null : source.keepInventory();
    }

    static void refreshProjectedPortals(MinecraftServer server) {
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
    }

    /** True while the player is inside a live portal instance (not a pocket editor). */
    public static boolean inInstance(ServerPlayer player) {
        return PortalData.get(player.level().getServer()).instances()
                .containsKey(player.level().dimension().identifier().toString());
    }

    public static boolean canBuild(ServerPlayer player) {
        Identifier dimension = player.level().dimension().identifier();
        PortalData.StoredInstance instance = PortalData.get(player.level().getServer()).instances()
                .get(dimension.toString());
        if (instance == null) return true;
        PortalDefinition source = portal(player.level().getServer(), instance.portalId());
        return source != null && source.allowBuilding();
    }

    // Return whether a portal accepts new entries at the current real-world instant.
    public static boolean isOpen(PortalDefinition portal) {
        return portal.schedule().allows(Instant.now());
    }

    // Accept or reject a client response only when it matches a recent server-issued nearby portal prompt.
    public static void respondToEntryPrompt(ServerPlayer player, String portalId, boolean accepted,
                                            boolean riskConfirmed) {
        MinecraftServer server = player.level().getServer();
        PendingEntry pending;
        synchronized (PENDING_ENTRIES) {
            pending = PENDING_ENTRIES.computeIfAbsent(server, ignored -> new HashMap<>()).remove(player.getUUID());
        }
        if (pending == null || !pending.portalId().toString().equals(portalId)
                || server.getTickCount() - pending.issuedTick() > PROMPT_LIFETIME_TICKS) return;
        if (!accepted) {
            revokeRiskApproval(server, player.getUUID());
            return;
        }
        PortalDefinition portal = portalIndex(server).getOrDefault(player.level().dimension().identifier(), List.of())
                .stream().filter(candidate -> contactKey(candidate).equals(pending.contactKey())).findFirst().orElse(null);
        Vec3 center = player.position().add(0.0, 0.9, 0.0);
        boolean groupRisk = portal != null && !portal.keepInventory() && !portal.isExit()
                && !portal.isCoordinates()
                && portal.admission().mode() == PortalDefinition.Mode.PRIVATE
                && playerInstance(server, portal.id(), player.getUUID()) == null;
        if (portal == null
                || !portal.dimension().equals(player.level().dimension().identifier())
                || !(groupRisk ? portal.isNear(center, GROUP_ENTRY_DISTANCE) : portal.bounds(4.0).contains(center))
                || !isOpen(portal) || !canPlayerEnter(server, player, portal)) {
            player.sendOverlayMessage(Component.literal("Portal travel could not be confirmed from here"));
            return;
        }
        if (portal.isCoordinates() && coordinateTravelOnCooldown(server, player.getUUID(), server.getTickCount())) return;
        if (!portal.keepInventory() && !riskConfirmed) {
            player.sendOverlayMessage(Component.literal("Confirm the inventory-loss warning twice to enter"));
            return;
        }
        if (!portal.isExit() && !portal.isCoordinates()
                && portal.admission().mode() == PortalDefinition.Mode.PRIVATE
                && playerInstance(server, portal.id(), player.getUUID()) == null
                && !groupRisk && !PortalGroups.leader(server, player.getUUID()).equals(player.getUUID())) {
            player.sendOverlayMessage(Component.literal("Your group leader must confirm private portal travel"));
            return;
        }
        if (groupRisk) {
            confirmRiskGroup(server, player, portal, pending);
            return;
        }
        try {
            ServerLevel source = player.level();
            enter(server, player, portal);
            MobForge.SCRIPT_EXECUTOR.portalUsed(source, portal.id());
            RaidService.portalUsed(player, source, portal.id(), portal.entryPolicy());
        } catch (RuntimeException exception) {
            player.sendOverlayMessage(Component.literal("Portal unavailable: " + exception.getMessage()));
            CrazyWorldProgression.LOGGER.error("Portal {} could not accept {}", portal.id(),
                    player.getGameProfile().name(), exception);
        }
    }

    private static void confirmRiskGroup(MinecraftServer server, ServerPlayer player,
                                         PortalDefinition portal, PendingEntry pending) {
        UUID leaderId = PortalGroups.leader(server, player.getUUID());
        String key = portal.id() + "|" + leaderId + "|" + pending.contactKey();
        long tick = server.getTickCount();
        Map<UUID, Long> approvals;
        synchronized (RISK_APPROVALS) {
            approvals = RISK_APPROVALS.computeIfAbsent(server, ignored -> new HashMap<>())
                    .computeIfAbsent(key, ignored -> new HashMap<>());
            approvals.put(player.getUUID(), tick);
            approvals.entrySet().removeIf(entry -> tick - entry.getValue() > PROMPT_LIFETIME_TICKS);
        }
        List<UUID> members = PortalGroups.members(server, leaderId);
        for (UUID memberId : members) {
            if (approvals.containsKey(memberId)) continue;
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member == null || member.level() != player.level()
                    || !portal.isNear(member.position().add(0, 0.9, 0), GROUP_ENTRY_DISTANCE)) continue;
            synchronized (PENDING_ENTRIES) {
                Map<UUID, PendingEntry> pendingEntries = PENDING_ENTRIES.computeIfAbsent(server,
                        ignored -> new HashMap<>());
                if (pendingEntries.containsKey(memberId)) continue;
                pendingEntries.put(memberId, new PendingEntry(portal.id(), pending.contactKey(), tick));
            }
            PortalNetworking.prompt(member, portal, portal.lifetimeSeconds(), false);
        }
        if (!approvals.keySet().containsAll(members)) {
            player.sendSystemMessage(Component.literal("Waiting for every group member to confirm the inventory-loss warning."));
            return;
        }
        ServerPlayer leader = server.getPlayerList().getPlayer(leaderId);
        if (leader == null) return;
        synchronized (RISK_APPROVALS) { RISK_APPROVALS.get(server).remove(key); }
        try {
            ServerLevel source = leader.level();
            enterPrivate(server, leader, portal);
            MobForge.SCRIPT_EXECUTOR.portalUsed(source, portal.id());
            RaidService.portalUsed(leader, source, portal.id(), portal.entryPolicy());
        } catch (RuntimeException exception) {
            player.sendOverlayMessage(Component.literal("Portal unavailable: " + exception.getMessage()));
            CrazyWorldProgression.LOGGER.error("Risk-confirmed group could not enter {}", portal.id(), exception);
        }
    }

    private static void revokeRiskApproval(MinecraftServer server, UUID playerId) {
        synchronized (RISK_APPROVALS) {
            Map<String, Map<UUID, Long>> groups = RISK_APPROVALS.get(server);
            if (groups != null) groups.values().forEach(approvals -> approvals.remove(playerId));
        }
    }

    // Let a player leave a temporary instance immediately using their remembered entry position.
    public static boolean leave(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Identifier current = player.level().dimension().identifier();
        boolean inInstance = PortalData.get(server).instances().values().stream()
                .anyMatch(instance -> instance.slotDimension().equals(current));
        if (!inInstance) return false;
        teleportBack(server, player);
        return true;
    }

    // Move an administrator into this pocket's dedicated persistent build dimension.
    public static void enterPocketEditor(ServerPlayer player, Identifier pocketId) {
        MinecraftServer server = player.level().getServer();
        Identifier dimension = PocketEditorService.ensureEditor(server, pocketId);
        ServerLevel editor = server.getLevel(dimensionKey(dimension));
        if (editor == null) throw new IllegalStateException("Pocket editor dimension is not registered");
        if (PocketEditorService.isEditorDimension(player.level().dimension().identifier())) {
            leavePocketEditor(player);
        }
        BlockPos entry = PocketEditorService.safeEditorEntry(server, editor, pocketId);
        pushReturn(server, player, new ReturnPoint(player.level().dimension().identifier(), player.position(),
                player.getYRot(), player.getXRot()));
        player.stopRiding();
        if (!player.teleportTo(editor, entry.getX() + 0.5, entry.getY(), entry.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot(), false)) {
            popReturn(server, player);
            throw new IllegalStateException("Pocket editor teleport was rejected; try again");
        }
        player.resetFallDistance();
    }

    // Return an administrator from any pocket editor to their remembered source.
    public static boolean leavePocketEditor(ServerPlayer player) {
        if (!PocketEditorService.isEditorDimension(player.level().dimension().identifier())) return false;
        Identifier pocket = PocketEditorService.pocketForDimension(player.level().getServer(),
                player.level().dimension().identifier());
        if (pocket != null && !PocketEditorService.saveNow(player.level().getServer(), pocket)) {
            player.sendSystemMessage(Component.literal("Pocket autosave is pending; check the server log"));
        }
        teleportBack(player.level().getServer(), player);
        return true;
    }

    // End one active portal instance immediately for administration and testing.
    public static boolean expire(MinecraftServer server, Identifier portalId) {
        boolean found = false;
        for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
            if (instance.portalId().equals(portalId)) found |= expireInstance(server, instance.slotDimension());
        }
        return found;
    }

    /** Expire one lease without affecting other groups using the same private portal. */
    public static boolean expireInstance(MinecraftServer server, Identifier slotDimension) {
        PortalData.StoredInstance instance = PortalData.get(server).instances().get(slotDimension.toString());
        if (instance == null) return false;
        synchronized (CLEANUPS) {
            if (CLEANUPS.computeIfAbsent(server, ignored -> new HashMap<>()).containsKey(slotDimension)) return true;
        }
        ServerLevel level = server.getLevel(dimensionKey(instance.slotDimension()));
        if (level == null) {
            CrazyWorldProgression.LOGGER.error("Cannot clean missing portal instance dimension {}",
                    instance.slotDimension());
            return false;
        }
        ActiveInstance publicInstance = view(instance);
        long now = Instant.now().toEpochMilli();
        if (instance.expiresEpochMillis() > now) {
            instance = instance.expiringAt(now);
            PortalData.get(server).putInstance(instance);
            publicInstance = view(instance);
        }
        ekuzo.crazyworldprogression.api.mobforge.MobForgeApi.cancelPocketRuns(publicInstance.instanceId());
        RaidService.remove(server, slotDimension);
        synchronized (RUNTIME_LOCATIONS) {
            Map<Identifier, Map<Identifier, PlacementState>> locations = RUNTIME_LOCATIONS.get(server);
            if (locations != null) locations.remove(slotDimension);
        }
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
        PortalRegistry.expiring(server, publicInstance);
        for (ServerPlayer player : new ArrayList<>(level.players())) teleportBack(server, player);
        PortalTemplateService.discardEntities(level, instance);
        synchronized (CLEANUPS) {
            CLEANUPS.computeIfAbsent(server, ignored -> new HashMap<>()).put(slotDimension,
                    new CleanupTask(instance, 0L));
        }
        CrazyWorldProgression.LOGGER.info("Evacuated portal instance {} in {}; batched cleanup started",
                instance.portalId(), instance.slotDimension());
        return true;
    }

    // Process entries and cleanup every tick, then schedules and expiry once per second.
    private static void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        PortalEnvironmentService.tickIndependentTime(server);
        PocketEditorService.tickAutoSave(server);
        ekuzo.crazyworldprogression.buildings.BuildingPreviewNetworking.tick(server);
        PortalPreviewNetworking.tick(server);
        processCleanups(server);
        if (tick % 20L == 0L) expireEmptyPrivateInstances(server);
        detectEntries(server, tick);
        if (tick % 20L == 0L) {
            PortalNetworking.broadcastIfChanged(server);
            expireDueInstances(server, Instant.now().toEpochMilli());
        }
    }

    // Detect first contact with an open portal and request confirmation without repeatedly reopening the prompt.
    private static void detectEntries(MinecraftServer server, long tick) {
        Map<Identifier, List<PortalDefinition>> index = portalIndex(server);
        Map<UUID, String> contacts;
        synchronized (PORTAL_CONTACTS) {
            contacts = PORTAL_CONTACTS.computeIfAbsent(server, ignored -> new HashMap<>());
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Identifier dimension = player.level().dimension().identifier();
            Vec3 center = player.position().add(0.0, 0.9, 0.0);
            PortalDefinition touching = index.getOrDefault(dimension, List.of()).stream()
                    .filter(portal -> portal.contains(center, TRIGGER_THICKNESS) && isOpen(portal)
                            && (!portal.isExit()
                                || PortalRegistry.instanceSlots().contains(dimension)))
                    .findFirst().orElse(null);
            if (touching == null) {
                contacts.remove(player.getUUID());
                continue;
            }
            if (contactKey(touching).equals(contacts.put(player.getUUID(), contactKey(touching)))) continue;
            if (!canPlayerEnter(server, player, touching)) {
                player.sendOverlayMessage(Component.literal("You have already used "
                        + touching.effectiveDisplayName()));
                continue;
            }
            if (touching.isCoordinates() && coordinateTravelOnCooldown(server, player.getUUID(), tick)) continue;
            if (!touching.isExit() && !touching.isCoordinates() && touching.keepInventory()
                    && touching.admission().mode() == PortalDefinition.Mode.PRIVATE
                    && playerInstance(server, touching.id(), player.getUUID()) == null
                    && !PortalGroups.leader(server, player.getUUID()).equals(player.getUUID())) continue;
            synchronized (PENDING_ENTRIES) {
                PendingEntry pending = PENDING_ENTRIES.computeIfAbsent(server, ignored -> new HashMap<>())
                        .get(player.getUUID());
                if (pending != null && tick - pending.issuedTick() <= PROMPT_LIFETIME_TICKS) continue;
            }
            requestEntryConfirmation(server, player, touching, tick);
        }
    }

    // Record one expiring pending entry and send its current instance-lifetime context to the client.
    private static void requestEntryConfirmation(MinecraftServer server, ServerPlayer player,
                                                 PortalDefinition portal, long tick) {
        long now = Instant.now().toEpochMilli();
        PortalData.StoredInstance instance = portal.isExit() || portal.isCoordinates()
                ? null : playerInstance(server, portal.id(), player.getUUID());
        if (instance == null && !portal.isCoordinates()
                && portal.admission().mode() == PortalDefinition.Mode.PUBLIC) {
            instance = publicInstance(server, portal.id());
        }
        boolean existing = instance != null && instance.expiresEpochMillis() > now;
        long seconds = portal.isExit() ? 0L : portal.lifetimeSeconds() == 0L ? -1L
                : existing ? Math.max(1L, (instance.expiresEpochMillis() - now + 999L) / 1_000L)
                : portal.lifetimeSeconds();
        synchronized (PENDING_ENTRIES) {
            PENDING_ENTRIES.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(),
                    new PendingEntry(portal.id(), contactKey(portal), tick));
        }
        PortalNetworking.prompt(player, portal, seconds, existing);
    }

    // Check the persistent per-player entry record against one portal's configured reuse policy.
    private static boolean canPlayerEnter(MinecraftServer server, ServerPlayer player, PortalDefinition portal) {
        if (!RaidService.canUse(player, portal)) return false;
        if (portal.entryPolicy() == PortalDefinition.EntryPolicy.ONCE_PER_ATTEMPT
                && RaidService.isRaid(server, player.level().dimension().identifier())) return true;
        if (portal.isCoordinates()) return portal.entryPolicy() == PortalDefinition.EntryPolicy.REPEATABLE
                || !PortalData.get(server).hasPlayerEntry(portal.id(), player.getUUID());
        return portal.isExit()
                || playerInstance(server, portal.id(), player.getUUID()) != null
                || portal.entryPolicy() == PortalDefinition.EntryPolicy.REPEATABLE
                || portal.admission().mode() == PortalDefinition.Mode.PRIVATE
                        && portal.entryPolicy() == PortalDefinition.EntryPolicy.ONCE_PER_INSTANCE
                || !PortalData.get(server).hasPlayerEntry(portal.id(), player.getUUID());
    }

    // Remove all transient prompt/contact state for one disconnected player.
    private static void clearEntryState(MinecraftServer server, UUID playerId) {
        synchronized (PORTAL_CONTACTS) {
            PORTAL_CONTACTS.computeIfAbsent(server, ignored -> new HashMap<>()).remove(playerId);
        }
        synchronized (PENDING_ENTRIES) {
            PENDING_ENTRIES.computeIfAbsent(server, ignored -> new HashMap<>()).remove(playerId);
        }
        synchronized (COORDINATE_TRAVEL_TICKS) {
            COORDINATE_TRAVEL_TICKS.computeIfAbsent(server, ignored -> new HashMap<>()).remove(playerId);
        }
    }

    private static boolean coordinateTravelOnCooldown(MinecraftServer server, UUID playerId, long tick) {
        synchronized (COORDINATE_TRAVEL_TICKS) {
            Long previous = COORDINATE_TRAVEL_TICKS.computeIfAbsent(server, ignored -> new HashMap<>()).get(playerId);
            return previous != null && tick - previous < COORDINATE_TRAVEL_COOLDOWN_TICKS;
        }
    }

    private static void enterCoordinates(MinecraftServer server, ServerPlayer player, PortalDefinition portal,
                                         long tick) {
        PortalDefinition.CoordinateTarget target = portal.coordinateTarget()
                .orElseThrow(() -> new IllegalStateException("destination coordinates are missing"));
        ServerLevel destination = player.level();
        BlockPos block = BlockPos.containing(target.x(), target.y(), target.z());
        if (block.getY() < destination.getMinY()
                || block.getY() >= destination.getMinY() + destination.getLogicalHeight()
                || !destination.getWorldBorder().isWithinBounds(block)) {
            throw new IllegalStateException("destination coordinates are outside this world");
        }
        Entity vehicle = player.getRootVehicle();
        List<ServerPlayer> riders = new ArrayList<>();
        if (vehicle == player) riders.add(player);
        else for (Entity passenger : vehicle.getIndirectPassengers())
            if (passenger instanceof ServerPlayer rider) riders.add(rider);
        boolean allowed = riders.stream().allMatch(rider -> rider == player || canPlayerEnter(server, rider, portal));
        if (takesVehicle(vehicle != player, allowed)) {
            // Same-dimension teleport of the root vehicle carries every passenger along and keeps them mounted.
            Vec3 position = new Vec3(target.x(), target.y(), target.z());
            if (vehicle.teleport(new TeleportTransition(destination, position, Vec3.ZERO, vehicle.getYRot(),
                    vehicle.getXRot(), TeleportTransition.DO_NOTHING)) == null) {
                throw new IllegalStateException("destination rejected teleport");
            }
            vehicle.resetFallDistance();
        } else {
            if (vehicle != player) player.sendOverlayMessage(Component.literal(
                    "Someone riding with you cannot use this portal, so you travel alone."));
            riders = List.of(player);
            player.stopRiding();
            if (!player.teleportTo(destination, target.x(), target.y(), target.z(), Set.of(),
                    player.getYRot(), player.getXRot(), false)) {
                throw new IllegalStateException("destination rejected teleport");
            }
        }
        for (ServerPlayer rider : riders) {
            rider.resetFallDistance();
            synchronized (COORDINATE_TRAVEL_TICKS) {
                COORDINATE_TRAVEL_TICKS.computeIfAbsent(server, ignored -> new HashMap<>()).put(rider.getUUID(), tick);
            }
            if (portal.entryPolicy() != PortalDefinition.EntryPolicy.REPEATABLE
                    && (portal.entryPolicy() != PortalDefinition.EntryPolicy.ONCE_PER_ATTEMPT
                        || !RaidService.isRaid(server, destination.dimension().identifier()))) {
                PortalData.get(server).recordPlayerEntry(portal.id(), rider.getUUID());
            }
        }
    }

    /**
     * Coordinate portals take the whole vehicle (with every passenger) along when the player is riding,
     * unless another player on it may not use the portal. Other portal kinds always dismount.
     */
    static boolean takesVehicle(boolean riding, boolean allRidersAllowed) {
        return riding && allRidersAllowed;
    }

    // Allocate or reuse an instance, remember the return point, and teleport one entering player.
    private static void enter(MinecraftServer server, ServerPlayer player, PortalDefinition portal) {
        if (portal.isCoordinates()) {
            enterCoordinates(server, player, portal, server.getTickCount());
            return;
        }
        if (portal.isExit()) {
            if (!PortalRegistry.instanceSlots().contains(player.level().dimension().identifier())) {
                throw new IllegalStateException("Return portals can only be used inside an instance");
            }
            returnToMainWorld(server, player);
            return;
        }
        if (portal.admission().mode() == PortalDefinition.Mode.PRIVATE) {
            enterPrivate(server, player, portal);
            return;
        }
        PortalData.StoredInstance instance = publicInstance(server, portal.id());
        if (instance != null && instance.expiresEpochMillis() <= Instant.now().toEpochMilli()) {
            expireInstance(server, instance.slotDimension());
            throw new IllegalStateException("instance is being reset");
        }
        if (instance == null) instance = createInstance(server, portal, List.of());
        teleportInto(server, player, portal, instance);
    }

    private static PortalData.StoredInstance publicInstance(MinecraftServer server, Identifier portalId) {
        return PortalData.get(server).instances().values().stream()
                .filter(instance -> instance.portalId().equals(portalId) && instance.admittedPlayers().isEmpty())
                .findFirst().orElse(null);
    }

    private static PortalData.StoredInstance playerInstance(MinecraftServer server, Identifier portalId, UUID playerId) {
        long now = Instant.now().toEpochMilli();
        return PortalData.get(server).instances().values().stream()
                .filter(instance -> instance.portalId().equals(portalId)
                        && instance.expiresEpochMillis() > now
                        && (instance.admittedPlayers().contains(playerId.toString())
                            || instance.visitors().contains(playerId.toString())))
                .findFirst().orElse(null);
    }

    private static void enterPrivate(MinecraftServer server, ServerPlayer player, PortalDefinition portal) {
        PortalData.StoredInstance existing = playerInstance(server, portal.id(), player.getUUID());
        if (existing != null) {
            teleportInto(server, player, portal, existing);
            return;
        }
        PortalData.StoredInstance invited = InstanceInvites.invitedInstance(server, player.getUUID(), portal.id());
        if (invited != null) {
            InstanceInvites.consume(server, player.getUUID());
            joinRunningInstance(player, invited);
            return;
        }
        UUID leader = PortalGroups.leader(server, player.getUUID());
        if (!leader.equals(player.getUUID())) throw new IllegalStateException("Only your group leader can start private travel");
        List<UUID> members = PortalGroups.members(server, leader);
        if (members.size() > portal.admission().maxPlayers()) {
            throw new IllegalStateException("group exceeds this portal's " + portal.admission().maxPlayers() + " player limit");
        }
        List<ServerPlayer> travelers = new ArrayList<>();
        for (UUID id : members) {
            ServerPlayer member = server.getPlayerList().getPlayer(id);
            if (member == null) throw new IllegalStateException("all group members must be online");
            if (!member.level().dimension().identifier().equals(portal.dimension())
                    || !portal.isNear(member.position().add(0, 0.9, 0), GROUP_ENTRY_DISTANCE)) {
                throw new IllegalStateException(member.getGameProfile().name()
                        + " must be within 20 blocks of the portal");
            }
            if (!canPlayerEnter(server, member, portal)) {
                throw new IllegalStateException(member.getGameProfile().name()
                        + " is not eligible to enter this portal");
            }
            travelers.add(member);
        }
        PortalData.StoredInstance instance = createInstance(server, portal,
                members.stream().map(UUID::toString).toList());
        try {
            for (ServerPlayer traveler : travelers) teleportInto(server, traveler, portal, instance);
        } catch (RuntimeException exception) {
            expireInstance(server, instance.slotDimension());
            throw exception;
        }
        PortalGroups.finishTravel(server, leader);
    }

    private static void teleportInto(MinecraftServer server, ServerPlayer player, PortalDefinition portal,
                                     PortalData.StoredInstance instance) {
        boolean sourceRaid = RaidService.isRaid(server, player.level().dimension().identifier());
        ServerLevel destination = server.getLevel(dimensionKey(instance.slotDimension()));
        if (destination == null) throw new IllegalStateException("Missing instance dimension " + instance.slotDimension());
        PortalTemplateService.TemplateInfo template = requireTemplate(server, instance.templateId());
        RaidData.Spot moved = RaidService.spawnPoint(server, instance);
        Vec3 entry = moved != null ? new Vec3(moved.x(), moved.y(), moved.z())
                : new Vec3(instance.originX() + template.spawnX() + 0.5,
                instance.originY() + template.spawnY(), instance.originZ() + template.spawnZ() + 0.5);
        pushReturn(server, player, new ReturnPoint(player.level().dimension().identifier(),
                portal.safeReturnPosition(player.position(), player.position().add(0.0, 0.9, 0.0),
                        player.getDeltaMovement()), player.getYRot(), player.getXRot()));
        player.stopRiding();
        if (!player.teleportTo(destination, entry.x, entry.y, entry.z, Set.of(),
                player.getYRot(), player.getXRot(), false)) {
            popReturn(server, player);
            throw new IllegalStateException("destination rejected teleport");
        }
        PortalEnvironmentService.syncWeather(player);
        if (portal.entryPolicy() != PortalDefinition.EntryPolicy.REPEATABLE
                && (portal.entryPolicy() != PortalDefinition.EntryPolicy.ONCE_PER_ATTEMPT
                    || !sourceRaid)
                && (portal.admission().mode() == PortalDefinition.Mode.PUBLIC
                    || portal.entryPolicy() == PortalDefinition.EntryPolicy.ONCE_EVER)) {
            PortalData.get(server).recordPlayerEntry(portal.id(), player.getUUID());
        }
        PortalData.get(server).putInstance(instance.withVisitor(player.getUUID()));
        player.resetFallDistance();
    }

    // Lease a free registered dimension slot and reconstruct the linked template at its fixed origin.
    private static PortalData.StoredInstance createInstance(MinecraftServer server, PortalDefinition portal,
                                                            List<String> admittedPlayers) {
        Set<Identifier> occupied = new HashSet<>();
        PortalData.get(server).instances().values().forEach(instance -> occupied.add(instance.slotDimension()));
        Identifier slot = PortalRegistry.instanceSlots().stream().filter(id -> !occupied.contains(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("all portal instance slots are occupied"));
        ServerLevel destination = server.getLevel(dimensionKey(slot));
        if (destination == null) throw new IllegalStateException("instance slot is not registered: " + slot);
        PortalTemplateService.TemplateInfo template = requireTemplate(server, portal.templateId());
        BlockPos origin = INSTANCE_CENTER.offset(-template.sizeX() / 2, 0, -template.sizeZ() / 2);
        if (origin.getY() < destination.getMinY()
                || origin.getY() + template.sizeY() > destination.getMinY() + destination.getLogicalHeight()) {
            throw new IllegalStateException("template height does not fit the instance dimension");
        }
        BuildingService.clearInstance(destination);
        PortalTemplateService.discardAllEntities(destination);
        PortalTemplateService.reconstruct(server, destination, template.id(), origin);
        long created = Instant.now().toEpochMilli();
        PortalData.StoredInstance instance = new PortalData.StoredInstance(portal.id(), template.id(), slot,
                created, portal.lifetimeSeconds() == 0L ? Long.MAX_VALUE
                        : Math.addExact(created, Math.multiplyExact(portal.lifetimeSeconds(), 1_000L)),
                origin.getX(), origin.getY(), origin.getZ(), template.sizeX(), template.sizeY(), template.sizeZ(),
                admittedPlayers);
        PortalData.get(server).putInstance(instance);
        if (portal.raidMode()) RaidService.prepare(server, instance);
        PortalEnvironmentService.applyTime(server, view(instance), portal.environment());
        PortalEnvironmentService.applyWeather(server, view(instance), portal.environment());
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
        PortalRegistry.created(server, view(instance));
        PocketEditorService.startScripts(server, template.id(), destination, origin, view(instance).instanceId(),
                RaidCheckpoints.resumeFor(server, instance));
        CrazyWorldProgression.LOGGER.info("Reconstructed portal template {} in slot {} for portal {}",
                template.id(), slot, portal.id());
        return instance;
    }

    // Expire every lease whose real-time deadline has elapsed, including while the server was offline.
    private static void expireDueInstances(MinecraftServer server, long nowEpochMillis) {
        List<Identifier> due = PortalData.get(server).instances().values().stream()
                .filter(instance -> instance.expiresEpochMillis() <= nowEpochMillis)
                .map(PortalData.StoredInstance::slotDimension).toList();
        due.forEach(id -> expireInstance(server, id));
    }

    private static void expireEmptyPrivateInstances(MinecraftServer server) {
        Map<Identifier, Long> emptySince = EMPTY_SINCE.computeIfAbsent(server, ignored -> new HashMap<>());
        emptySince.keySet().retainAll(PortalData.get(server).instances().values().stream()
                .map(PortalData.StoredInstance::slotDimension).collect(java.util.stream.Collectors.toSet()));
        for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
            if (instance.admittedPlayers().isEmpty()) continue;
            ServerLevel level = server.getLevel(dimensionKey(instance.slotDimension()));
            if (level == null) continue;
            if (!level.players().isEmpty() || RaidService.retainEmpty(server, instance)) {
                emptySince.remove(instance.slotDimension());
                continue;
            }
            long since = emptySince.computeIfAbsent(instance.slotDimension(), ignored -> (long) server.getTickCount());
            if (graceOver(since, server.getTickCount())) expireInstance(server, instance.slotDimension());
        }
    }

    /** An empty private instance is released once its rejoin grace period has passed. */
    static boolean graceOver(long emptySinceTick, long nowTick) {
        return nowTick - emptySinceTick >= REJOIN_GRACE_TICKS;
    }

    /** The party leader of a private instance is the first admitted player (the group leader at travel). */
    public static boolean isPartyLeader(PortalData.StoredInstance instance, UUID playerId) {
        return !instance.admittedPlayers().isEmpty() && instance.admittedPlayers().getFirst().equals(playerId.toString());
    }

    /** True inside a running private instance (raid or not), where /party invite adds players. */
    public static boolean inPrivateInstance(ServerPlayer player) {
        PortalData.StoredInstance instance = currentInstance(player);
        return instance != null && !instance.admittedPlayers().isEmpty();
    }

    /** Live instance the player is standing in, or null. */
    public static PortalData.StoredInstance currentInstance(ServerPlayer player) {
        return PortalData.get(player.level().getServer()).instances()
                .get(player.level().dimension().identifier().toString());
    }

    /** Take every player out of the leader's instance and end it. */
    public static int partyLeave(ServerPlayer leader) {
        MinecraftServer server = leader.level().getServer();
        PortalData.StoredInstance instance = currentInstance(leader);
        if (instance == null) throw new IllegalStateException("You are not inside an instance");
        if (!isPartyLeader(instance, leader.getUUID())) throw new IllegalStateException("Only the party leader can use /partyleave");
        ServerLevel level = server.getLevel(dimensionKey(instance.slotDimension()));
        int moved = 0;
        if (level != null) for (ServerPlayer player : new ArrayList<>(level.players())) {
            teleportBack(server, player);
            player.sendSystemMessage(Component.literal(leader.getGameProfile().name() + " ended the activity for the party."));
            moved++;
        }
        expireInstance(server, instance.slotDimension());
        return moved;
    }

    /** Add an invited player to a running private instance and bring them in. */
    public static void joinRunningInstance(ServerPlayer player, PortalData.StoredInstance instance) {
        MinecraftServer server = player.level().getServer();
        PortalData.StoredInstance live = PortalData.get(server).instances().get(instance.slotDimension().toString());
        if (live == null || live.createdEpochMillis() != instance.createdEpochMillis())
            throw new IllegalStateException("That instance has ended");
        PortalDefinition portal = portal(server, live.portalId());
        if (portal == null) throw new IllegalStateException("That instance's portal no longer exists");
        if (!InstanceInvites.hasRoom(live.admittedPlayers(), player.getUUID().toString(), portal.admission().maxPlayers()))
            throw new IllegalStateException("The instance is full");
        live = live.withAdmitted(player.getUUID());
        PortalData.get(server).putInstance(live);
        teleportInto(server, player, portal, live);
    }

    // Spread destructive block removal across ticks so large expired templates cannot freeze the server.
    private static void processCleanups(MinecraftServer server) {
        Map<Identifier, CleanupTask> tasks;
        synchronized (CLEANUPS) {
            tasks = new HashMap<>(CLEANUPS.computeIfAbsent(server, ignored -> new HashMap<>()));
        }
        for (Map.Entry<Identifier, CleanupTask> entry : tasks.entrySet()) {
            CleanupTask task = entry.getValue();
            ServerLevel level = server.getLevel(dimensionKey(task.instance().slotDimension()));
            if (level == null) continue;
            for (ServerPlayer player : new ArrayList<>(level.players())) teleportBack(server, player);
            long next = PortalTemplateService.clearBatch(level, task.instance(), task.nextIndex(),
                    CLEANUP_BLOCKS_PER_TICK);
            synchronized (CLEANUPS) {
                Map<Identifier, CleanupTask> live = CLEANUPS.get(server);
                if (next < 0L) {
                    BuildingService.clearInstance(level);
                    PortalTemplateService.discardEntities(level, task.instance());
                    live.remove(entry.getKey());
                    PortalData.get(server).removeInstance(entry.getKey());
                    rebuildIndex(server);
                    PortalNetworking.broadcast(server);
                    Identifier portalId = task.instance().portalId();
                    PortalDefinition portal = portal(server, portalId);
                    boolean last = PortalData.get(server).instances().values().stream()
                            .noneMatch(instance -> instance.portalId().equals(portalId));
                    if (last && portal != null && portal.entryPolicy() == PortalDefinition.EntryPolicy.ONCE_PER_INSTANCE) {
                        PortalData.get(server).clearPlayerEntries(portalId);
                    }
                    CrazyWorldProgression.LOGGER.info("Finished cleanup and released portal slot {}",
                            task.instance().slotDimension());
                } else {
                    live.put(entry.getKey(), new CleanupTask(task.instance(), next));
                }
            }
        }
    }

    // Return a player to their remembered source or the overworld spawn after restart/fallback.
    private static void teleportBack(MinecraftServer server, ServerPlayer player) {
        ReturnPoint point;
        synchronized (RETURN_POINTS) {
            var points = RETURN_POINTS.computeIfAbsent(server, ignored -> new HashMap<>())
                    .get(player.getUUID());
            while (points != null && !points.isEmpty() && !validReturnDimension(server, points.peekLast().dimension())) {
                points.removeLast();
            }
            point = points == null ? null : points.peekLast();
        }
        ServerLevel destination = point == null ? server.overworld() : server.getLevel(dimensionKey(point.dimension()));
        if (destination == null) destination = server.overworld();
        BlockPos sharedSpawn = destination.getRespawnData().pos();
        Vec3 feet = point == null ? Vec3.atBottomCenterOf(sharedSpawn) : point.feet();
        float yaw = point == null ? 0.0F : point.yaw();
        float pitch = point == null ? 0.0F : point.pitch();
        ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
        if (respawn != null && (PortalRegistry.instanceSlots().contains(
                respawn.respawnData().dimension().identifier())
                || PocketEditorService.isEditorDimension(respawn.respawnData().dimension().identifier()))) {
            player.setRespawnPosition(null, false);
        }
        player.stopRiding();
        if (!player.teleportTo(destination, feet.x, feet.y, feet.z, Set.<Relative>of(), yaw, pitch, false)) {
            throw new IllegalStateException("Return teleport was rejected; try again");
        }
        if (point != null) popReturn(server, player);
        player.resetFallDistance();
    }

    private static void returnToMainWorld(MinecraftServer server, ServerPlayer player) {
        Identifier overworld = server.overworld().dimension().identifier();
        ReturnPoint mainWorldPoint = null;
        synchronized (RETURN_POINTS) {
            var stacks = RETURN_POINTS.get(server);
            var stack = stacks == null ? null : stacks.get(player.getUUID());
            if (stack != null) for (var iterator = stack.descendingIterator(); iterator.hasNext();) {
                ReturnPoint candidate = iterator.next();
                if (candidate.dimension().equals(overworld)) { mainWorldPoint = candidate; break; }
            }
        }
        Vec3 feet = mainWorldPoint == null
                ? Vec3.atBottomCenterOf(server.overworld().getRespawnData().pos()) : mainWorldPoint.feet();
        float yaw = mainWorldPoint == null ? 0.0F : mainWorldPoint.yaw();
        float pitch = mainWorldPoint == null ? 0.0F : mainWorldPoint.pitch();
        player.stopRiding();
        if (!player.teleportTo(server.overworld(), feet.x, feet.y, feet.z, Set.<Relative>of(), yaw, pitch, false)) {
            throw new IllegalStateException("Exit teleport was rejected; try again");
        }
        synchronized (RETURN_POINTS) {
            var stacks = RETURN_POINTS.get(server);
            if (stacks != null) stacks.remove(player.getUUID());
        }
        player.resetFallDistance();
    }

    private static boolean validReturnDimension(MinecraftServer server, Identifier dimension) {
        if (PortalRegistry.instanceSlots().contains(dimension)) {
            PortalData.StoredInstance instance = PortalData.get(server).instances().get(dimension.toString());
            if (instance == null || instance.expiresEpochMillis() <= Instant.now().toEpochMilli()) return false;
            synchronized (CLEANUPS) {
                return !CLEANUPS.computeIfAbsent(server, ignored -> new HashMap<>()).containsKey(dimension);
            }
        }
        if (PocketEditorService.isEditorDimension(dimension)) {
            return PocketEditorService.isAssignedDimension(server, dimension);
        }
        return server.getLevel(dimensionKey(dimension)) != null;
    }

    private static void pushReturn(MinecraftServer server, ServerPlayer player, ReturnPoint point) {
        synchronized (RETURN_POINTS) {
            RETURN_POINTS.computeIfAbsent(server, ignored -> new HashMap<>())
                    .computeIfAbsent(player.getUUID(), ignored -> new java.util.ArrayDeque<>()).addLast(point);
        }
    }

    private static void popReturn(MinecraftServer server, ServerPlayer player) {
        synchronized (RETURN_POINTS) {
            var points = RETURN_POINTS.get(server);
            if (points == null) return;
            var stack = points.get(player.getUUID());
            if (stack == null) return;
            if (!stack.isEmpty()) stack.removeLast();
            if (stack.isEmpty()) points.remove(player.getUUID());
        }
    }

    // Rebuild the dimension grouping after one persistent portal mutation.
    private static void rebuildIndex(MinecraftServer server) {
        Map<Identifier, List<PortalDefinition>> mutable = new HashMap<>();
        for (PortalDefinition definition : portals(server)) {
            for (PortalPlacement location : locations(server, definition.id()).values()) {
                PortalDefinition portal = definition.movedTo(location.dimension(),
                        new Vec3(location.x(), location.y(), location.z()));
                if (portal.baseActive() && !suppressed(server, portal.dimension(), portal.id()))
                    mutable.computeIfAbsent(portal.dimension(), ignored -> new ArrayList<>()).add(portal);
                Identifier sourcePocket = PocketEditorService.pocketForDimension(server, portal.dimension());
                if (sourcePocket == null) continue;
                BlockPos captureOrigin = PocketEditorService.captureOrigin(server, sourcePocket);
                if (captureOrigin == null) continue;
                for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
                    if (!instance.templateId().equals(sourcePocket)) continue;
                    if (portal.baseActive() && !suppressed(server, instance.slotDimension(), portal.id()))
                        mutable.computeIfAbsent(instance.slotDimension(), ignored -> new ArrayList<>())
                                .add(projectPortal(portal, captureOrigin, instance));
                }
            }
        }
        synchronized (RUNTIME_LOCATIONS) {
            Map<Identifier, Map<Identifier, PlacementState>> byDimension = RUNTIME_LOCATIONS.get(server);
            if (byDimension != null) byDimension.forEach((dimension, byPortal) -> byPortal.forEach((id, state) -> {
                PortalDefinition preset = portal(server, id);
                if (preset == null) return;
                PortalDefinition basis = preset;
                PortalData.StoredInstance instance = PortalData.get(server).instances().get(dimension.toString());
                Identifier pocket = PocketEditorService.pocketForDimension(server, preset.dimension());
                if (instance != null && pocket != null && pocket.equals(instance.templateId())) {
                    BlockPos origin = PocketEditorService.captureOrigin(server, pocket);
                    if (origin != null) basis = projectPortal(preset, origin, instance);
                }
                PortalDefinition placed = basis;
                for (Placement location : state.locations.values()) {
                    PortalDefinition projected = placed.movedTo(dimension, location.position());
                    if (location.target() != null) projected = projected.withCoordinateTarget(
                            new PortalDefinition.CoordinateTarget(location.target().x,
                                    location.target().y, location.target().z));
                    mutable.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(projected);
                }
            }));
        }
        Map<Identifier, List<PortalDefinition>> frozen = new HashMap<>();
        mutable.forEach((dimension, definitions) -> frozen.put(dimension, List.copyOf(definitions)));
        synchronized (PORTALS_BY_DIMENSION) {
            PORTALS_BY_DIMENSION.put(server, Map.copyOf(frozen));
        }
    }

    private static String contactKey(PortalDefinition portal) {
        return portal.id() + "|" + portal.dimension() + "|" + portal.centerX() + "|" + portal.centerY()
                + "|" + portal.centerZ();
    }

    private static boolean suppressed(MinecraftServer server, Identifier dimension, Identifier portalId) {
        synchronized (RUNTIME_LOCATIONS) {
            var byDimension = RUNTIME_LOCATIONS.get(server);
            var byPortal = byDimension == null ? null : byDimension.get(dimension);
            var state = byPortal == null ? null : byPortal.get(portalId);
            return state != null && state.suppressBase;
        }
    }

    /** Change a preset's named placements in one live world or pocket instance. */
    public static void changeLocation(MinecraftServer server, Identifier dimension, Identifier portalId,
                                      String action, String locationId, Vec3 position) {
        changeLocation(server, dimension, portalId, action, locationId, position, null);
    }

    public static void changeLocation(MinecraftServer server, Identifier dimension, Identifier portalId,
                                      String action, String locationId, Vec3 position, Vec3 target) {
        PortalDefinition preset = portal(server, portalId);
        if (preset == null) throw new IllegalArgumentException("Unknown portal preset " + portalId);
        if (target != null && !preset.isCoordinates())
            throw new IllegalArgumentException("Only Coordinates portal presets accept a script target");
        synchronized (RUNTIME_LOCATIONS) {
            PlacementState state = RUNTIME_LOCATIONS.computeIfAbsent(server, ignored -> new HashMap<>())
                    .computeIfAbsent(dimension, ignored -> new HashMap<>())
                    .computeIfAbsent(portalId, ignored -> new PlacementState());
            switch (action) {
                case "add" -> state.locations.put(locationId, new Placement(position, target));
                case "remove" -> state.locations.remove(locationId);
                case "clear" -> { state.locations.clear(); state.suppressBase = true; }
                default -> throw new IllegalArgumentException("Unknown portal location action " + action);
            }
        }
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
    }

    public static void resetRuntimeLocations(MinecraftServer server, Identifier dimension) {
        synchronized (RUNTIME_LOCATIONS) {
            Map<Identifier, Map<Identifier, PlacementState>> locations = RUNTIME_LOCATIONS.get(server);
            if (locations != null) locations.remove(dimension);
        }
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
    }

    static UUID instanceId(PortalData.StoredInstance instance) { return view(instance).instanceId(); }

    private static final class PlacementState {
        private boolean suppressBase;
        private final Map<String, Placement> locations = new java.util.LinkedHashMap<>();
    }

    private record Placement(Vec3 position, Vec3 target) { }

    static PortalDefinition projectPortal(PortalDefinition portal, BlockPos captureOrigin,
                                          PortalData.StoredInstance instance) {
        Vec3 center = new Vec3(instance.originX() + portal.centerX() - captureOrigin.getX(),
                instance.originY() + portal.centerY() - captureOrigin.getY(),
                instance.originZ() + portal.centerZ() - captureOrigin.getZ());
        PortalDefinition projected = portal.movedTo(instance.slotDimension(), center);
        if (!portal.isCoordinates() || portal.coordinateTarget().isEmpty()) return projected;
        PortalDefinition.CoordinateTarget target = portal.coordinateTarget().orElseThrow();
        return projected.withCoordinateTarget(new PortalDefinition.CoordinateTarget(
                target.x() + instance.originX() - captureOrigin.getX(),
                target.y() + instance.originY() - captureOrigin.getY(),
                target.z() + instance.originZ() - captureOrigin.getZ()));
    }

    // Return an initialized dimension index even when queried unusually early.
    private static Map<Identifier, List<PortalDefinition>> portalIndex(MinecraftServer server) {
        synchronized (PORTALS_BY_DIMENSION) {
            Map<Identifier, List<PortalDefinition>> index = PORTALS_BY_DIMENSION.get(server);
            if (index != null) return index;
        }
        rebuildIndex(server);
        synchronized (PORTALS_BY_DIMENSION) {
            return PORTALS_BY_DIMENSION.get(server);
        }
    }

    // Require template metadata before portal creation or reconstruction.
    private static PortalTemplateService.TemplateInfo requireTemplate(MinecraftServer server, Identifier id) {
        PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, id);
        if (template == null) throw new IllegalArgumentException("Unknown CWP portal template: " + id);
        return template;
    }

    private static void validateDestination(MinecraftServer server, PortalDefinition portal) {
        if (!portal.keepInventory() && (portal.isCoordinates() || portal.isExit()))
            throw new IllegalArgumentException("Keep inventory can be disabled only for pocket portals");
        if (portal.isCoordinates()) {
            if (portal.coordinateTarget().isEmpty()) {
                throw new IllegalArgumentException("Coordinate portals need destination X, Y and Z");
            }
        } else if (!portal.isExit()) requireTemplate(server, portal.templateId());
    }

    // Convert an identifier into the registry key required by Minecraft's level lookup.
    private static ResourceKey<Level> dimensionKey(Identifier dimension) {
        return ResourceKey.create(Registries.DIMENSION, dimension);
    }

    // Convert private persistence state to a public integration-safe lifecycle view.
    private static ActiveInstance view(PortalData.StoredInstance instance) {
        return new ActiveInstance(instance.portalId(), instance.templateId(), instance.slotDimension(),
                Instant.ofEpochMilli(instance.createdEpochMillis()), Instant.ofEpochMilli(instance.expiresEpochMillis()),
                new BlockPos(instance.originX(), instance.originY(), instance.originZ()),
                new Vec3iView(instance.sizeX(), instance.sizeY(), instance.sizeZ()));
    }

    /** Public immutable state exposed to commands and dependent-mod lifecycle listeners. */
    public record ActiveInstance(Identifier portalId, Identifier templateId, Identifier slotDimension,
                                 Instant created, Instant expires, BlockPos origin, Vec3iView size) {
        /** Stable for the lifetime of this lease, including after a server restart. */
        public UUID instanceId() {
            return UUID.nameUUIDFromBytes((portalId + "@" + slotDimension + "@" + created.toEpochMilli())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public ServerLevel world(MinecraftServer server) {
            ServerLevel level = server.getLevel(dimensionKey(slotDimension));
            if (level == null) throw new IllegalStateException("Missing pocket instance world " + slotDimension);
            return level;
        }
    }

    /** API-safe size value that does not expose mutable reconstruction internals. */
    public record Vec3iView(int x, int y, int z) {
    }

    private record ReturnPoint(Identifier dimension, Vec3 feet, float yaw, float pitch) {
    }

    private record PendingEntry(Identifier portalId, String contactKey, long issuedTick) {
    }

    private record CleanupTask(PortalData.StoredInstance instance, long nextIndex) {
    }
}
