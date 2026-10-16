package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
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
import net.minecraft.world.entity.Relative;
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
    public static final Identifier WORKSHOP_DIMENSION = CrazyWorldProgression.id("portal_workshop");
    private static final double TRIGGER_THICKNESS = 0.85;
    private static final double GROUP_ENTRY_DISTANCE = 20.0;
    private static final long PROMPT_LIFETIME_TICKS = 600L;
    private static final int CLEANUP_BLOCKS_PER_TICK = 8_192;
    private static final BlockPos INSTANCE_CENTER = new BlockPos(0, 64, 0);
    private static final Map<MinecraftServer, Map<Identifier, List<PortalDefinition>>> PORTALS_BY_DIMENSION =
            new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Identifier>> PORTAL_CONTACTS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, PendingEntry>> PENDING_ENTRIES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, ReturnPoint>> RETURN_POINTS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<Identifier, CleanupTask>> CLEANUPS = new WeakHashMap<>();

    // Prevent instantiation of the server portal service.
    private PortalService() {
    }

    // Register server lifecycle restoration and lightweight portal enforcement ticks.
    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            rebuildIndex(server);
            expireDueInstances(server, Instant.now().toEpochMilli());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (PORTALS_BY_DIMENSION) {
                PORTALS_BY_DIMENSION.remove(server);
            }
            synchronized (PORTAL_CONTACTS) {
                PORTAL_CONTACTS.remove(server);
            }
            synchronized (PENDING_ENTRIES) {
                PENDING_ENTRIES.remove(server);
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
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (PortalRegistry.instanceSlots().contains(player.level().dimension().identifier())) {
                teleportBack(server, player);
            }
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

    // Persist a new directly template-linked portal and refresh its dimension index.
    public static void create(MinecraftServer server, PortalDefinition portal) {
        if (portal(server, portal.id()) != null) throw new IllegalArgumentException("Portal already exists: " + portal.id());
        requireTemplate(server, portal.templateId());
        PortalData.get(server).putPortal(portal);
        rebuildIndex(server);
        PortalNetworking.broadcast(server);
    }

    // Replace an existing portal after validating its linked template.
    public static void update(MinecraftServer server, PortalDefinition portal) {
        if (portal(server, portal.id()) == null) throw new IllegalArgumentException("Unknown portal: " + portal.id());
        requireTemplate(server, portal.templateId());
        PortalData.get(server).putPortal(portal);
        rebuildIndex(server);
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

    // Return whether a portal accepts new entries at the current real-world instant.
    public static boolean isOpen(PortalDefinition portal) {
        return portal.schedule().allows(Instant.now());
    }

    // Accept or reject a client response only when it matches a recent server-issued nearby portal prompt.
    public static void respondToEntryPrompt(ServerPlayer player, String portalId, boolean accepted) {
        MinecraftServer server = player.level().getServer();
        PendingEntry pending;
        synchronized (PENDING_ENTRIES) {
            pending = PENDING_ENTRIES.computeIfAbsent(server, ignored -> new HashMap<>()).remove(player.getUUID());
        }
        if (pending == null || !pending.portalId().toString().equals(portalId)
                || server.getTickCount() - pending.issuedTick() > PROMPT_LIFETIME_TICKS || !accepted) return;
        PortalDefinition portal = portal(server, pending.portalId());
        Vec3 center = player.position().add(0.0, 0.9, 0.0);
        if (portal == null || !portal.dimension().equals(player.level().dimension().identifier())
                || !portal.bounds(4.0).contains(center) || !isOpen(portal) || !canPlayerEnter(server, player, portal)) {
            player.sendOverlayMessage(Component.literal("Portal travel could not be confirmed from here"));
            return;
        }
        if (portal.admission().mode() == PortalDefinition.Mode.PRIVATE
                && !PortalGroups.leader(server, player.getUUID()).equals(player.getUUID())) {
            player.sendOverlayMessage(Component.literal("Your group leader must confirm private portal travel"));
            return;
        }
        try {
            enter(server, player, portal);
        } catch (RuntimeException exception) {
            player.sendOverlayMessage(Component.literal("Portal unavailable: " + exception.getMessage()));
            CrazyWorldProgression.LOGGER.error("Portal {} could not accept {}", portal.id(),
                    player.getGameProfile().name(), exception);
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

    // Move a game master into CWP's persistent, never-leased void workshop and remember their source.
    public static void enterWorkshop(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        ServerLevel workshop = server.getLevel(dimensionKey(WORKSHOP_DIMENSION));
        if (workshop == null) throw new IllegalStateException("CWP portal workshop dimension is not registered");
        synchronized (RETURN_POINTS) {
            RETURN_POINTS.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(),
                    new ReturnPoint(player.level().dimension().identifier(), player.position(),
                            player.getYRot(), player.getXRot()));
        }
        BlockPos platform = new BlockPos(0, 63, 0);
        if (workshop.getBlockState(platform).isAir()) workshop.setBlock(platform, Blocks.STONE.defaultBlockState(), 3);
        player.stopRiding();
        player.teleportTo(workshop, 0.5, 64.0, 0.5, Set.of(), player.getYRot(), player.getXRot(), false);
        player.resetFallDistance();
    }

    // Return a game master from the persistent template workshop to their remembered source.
    public static boolean leaveWorkshop(ServerPlayer player) {
        if (!player.level().dimension().identifier().equals(WORKSHOP_DIMENSION)) return false;
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
        processCleanups(server);
        detectEntries(server, tick);
        if (tick % 20L == 0L) {
            PortalNetworking.broadcastIfChanged(server);
            expireDueInstances(server, Instant.now().toEpochMilli());
        }
    }

    // Detect first contact with an open portal and request confirmation without repeatedly reopening the prompt.
    private static void detectEntries(MinecraftServer server, long tick) {
        Map<Identifier, List<PortalDefinition>> index = portalIndex(server);
        Map<UUID, Identifier> contacts;
        synchronized (PORTAL_CONTACTS) {
            contacts = PORTAL_CONTACTS.computeIfAbsent(server, ignored -> new HashMap<>());
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Identifier dimension = player.level().dimension().identifier();
            Vec3 center = player.position().add(0.0, 0.9, 0.0);
            PortalDefinition touching = index.getOrDefault(dimension, List.of()).stream()
                    .filter(portal -> portal.contains(center, TRIGGER_THICKNESS) && isOpen(portal))
                    .findFirst().orElse(null);
            if (touching == null) {
                contacts.remove(player.getUUID());
                continue;
            }
            if (touching.id().equals(contacts.put(player.getUUID(), touching.id()))) continue;
            if (!canPlayerEnter(server, player, touching)) {
                player.sendOverlayMessage(Component.literal("You have already used "
                        + touching.effectiveDisplayName()));
                continue;
            }
            if (touching.admission().mode() == PortalDefinition.Mode.PRIVATE
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
        PortalData.StoredInstance instance = portal.admission().mode() == PortalDefinition.Mode.PUBLIC
                ? publicInstance(server, portal.id()) : null;
        boolean existing = instance != null && instance.expiresEpochMillis() > now;
        long seconds = existing ? Math.max(1L, (instance.expiresEpochMillis() - now + 999L) / 1_000L)
                : portal.lifetimeSeconds();
        synchronized (PENDING_ENTRIES) {
            PENDING_ENTRIES.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(),
                    new PendingEntry(portal.id(), tick));
        }
        PortalNetworking.prompt(player, portal, seconds, existing);
    }

    // Check the persistent per-player entry record against one portal's configured reuse policy.
    private static boolean canPlayerEnter(MinecraftServer server, ServerPlayer player, PortalDefinition portal) {
        return portal.entryPolicy() == PortalDefinition.EntryPolicy.REPEATABLE
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
    }

    // Allocate or reuse an instance, remember the return point, and teleport one entering player.
    private static void enter(MinecraftServer server, ServerPlayer player, PortalDefinition portal) {
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

    private static void enterPrivate(MinecraftServer server, ServerPlayer player, PortalDefinition portal) {
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
        ServerLevel destination = server.getLevel(dimensionKey(instance.slotDimension()));
        if (destination == null) throw new IllegalStateException("Missing instance dimension " + instance.slotDimension());
        PortalTemplateService.TemplateInfo template = requireTemplate(server, instance.templateId());
        Vec3 entry = new Vec3(instance.originX() + template.spawnX() + 0.5,
                instance.originY() + template.spawnY(), instance.originZ() + template.spawnZ() + 0.5);
        synchronized (RETURN_POINTS) {
            RETURN_POINTS.computeIfAbsent(server, ignored -> new HashMap<>()).put(player.getUUID(),
                    new ReturnPoint(player.level().dimension().identifier(), portal.safeReturnPosition(
                            player.position(), player.position().add(0.0, 0.9, 0.0), player.getDeltaMovement()),
                            player.getYRot(), player.getXRot()));
        }
        player.stopRiding();
        if (!player.teleportTo(destination, entry.x, entry.y, entry.z, Set.of(),
                player.getYRot(), player.getXRot(), false)) {
            throw new IllegalStateException("destination rejected teleport");
        }
        if (portal.entryPolicy() != PortalDefinition.EntryPolicy.REPEATABLE
                && (portal.admission().mode() == PortalDefinition.Mode.PUBLIC
                    || portal.entryPolicy() == PortalDefinition.EntryPolicy.ONCE_EVER)) {
            PortalData.get(server).recordPlayerEntry(portal.id(), player.getUUID());
        }
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
        PortalTemplateService.reconstruct(server, destination, template.id(), origin);
        long created = Instant.now().toEpochMilli();
        PortalData.StoredInstance instance = new PortalData.StoredInstance(portal.id(), template.id(), slot,
                created, Math.addExact(created, Math.multiplyExact(portal.lifetimeSeconds(), 1_000L)),
                origin.getX(), origin.getY(), origin.getZ(), template.sizeX(), template.sizeY(), template.sizeZ(),
                admittedPlayers);
        PortalData.get(server).putInstance(instance);
        PortalRegistry.created(server, view(instance));
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
                    live.remove(entry.getKey());
                    PortalData.get(server).removeInstance(entry.getKey());
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
            point = RETURN_POINTS.computeIfAbsent(server, ignored -> new HashMap<>()).remove(player.getUUID());
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
                || respawn.respawnData().dimension().identifier().equals(WORKSHOP_DIMENSION))) {
            player.setRespawnPosition(null, false);
        }
        player.stopRiding();
        player.teleportTo(destination, feet.x, feet.y, feet.z, Set.<Relative>of(), yaw, pitch, false);
        player.resetFallDistance();
    }

    // Rebuild the dimension grouping after one persistent portal mutation.
    private static void rebuildIndex(MinecraftServer server) {
        Map<Identifier, List<PortalDefinition>> mutable = new HashMap<>();
        for (PortalDefinition portal : portals(server)) {
            mutable.computeIfAbsent(portal.dimension(), ignored -> new ArrayList<>()).add(portal);
        }
        Map<Identifier, List<PortalDefinition>> frozen = new HashMap<>();
        mutable.forEach((dimension, definitions) -> frozen.put(dimension, List.copyOf(definitions)));
        synchronized (PORTALS_BY_DIMENSION) {
            PORTALS_BY_DIMENSION.put(server, Map.copyOf(frozen));
        }
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

    private record PendingEntry(Identifier portalId, long issuedTick) {
    }

    private record CleanupTask(PortalData.StoredInstance instance, long nextIndex) {
    }
}
