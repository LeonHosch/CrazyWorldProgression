package ekuzo.crazyworldprogression.worldboundary.restriction;

import ekuzo.crazyworldprogression.portals.PocketEditorService;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import ekuzo.crazyworldprogression.portals.PortalService;
import ekuzo.crazyworldprogression.portals.PortalTemplateService;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.worldboundary.AdvancedWorldBoundary;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.UUID;

import static ekuzo.crazyworldprogression.worldboundary.restriction.Restriction.BoundaryMode.KEEP_IN;
import static ekuzo.crazyworldprogression.worldboundary.restriction.Restriction.BoundaryMode.KEEP_OUT;
import static ekuzo.crazyworldprogression.worldboundary.restriction.Restriction.Collision.SOLID;

/** Authoritative restriction persistence, level-state indexing, synchronization, and player rejection. */
public final class RestrictionService {
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final double PLAYER_HALF_HEIGHT = 0.9;
    private static final double REJECTION_RADIUS = 6.0;
    private static final double REJECTION_VERTICAL_RANGE = 4.0;
    private static final int RANDOM_TELEPORT_ATTEMPTS = 24;
    private static final int BOUNDARY_TELEPORT_ATTEMPTS = 16;
    private static final Map<MinecraftServer, RuntimeState> RUNTIMES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, SafePosition>> SAFE_POSITIONS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Map<Identifier, SafePosition>>> CONTAINED_POSITIONS =
            new WeakHashMap<>();

    // Prevent instantiation of the server-side restriction service.
    private RestrictionService() {
    }

    // Register lifecycle synchronization and once-per-second Advanced World Boundary level transition detection.
    public static void initialize() {
        PortalRegistry.registerListener(new PortalRegistry.InstanceListener() {
            @Override
            public void onCreated(MinecraftServer server, PortalService.ActiveInstance instance) {
                rebuild(server, true);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> rebuild(server, true));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (RUNTIMES) {
                RUNTIMES.remove(server);
            }
            synchronized (SAFE_POSITIONS) {
                SAFE_POSITIONS.remove(server);
            }
            synchronized (CONTAINED_POSITIONS) {
                CONTAINED_POSITIONS.remove(server);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendSnapshot(handler.getPlayer()));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                player instanceof ServerPlayer serverPlayer && protectsInteraction(serverPlayer, pos)
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !(player instanceof ServerPlayer serverPlayer) || !protectsInteraction(serverPlayer, pos));
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            BlockPos hit = hitResult.getBlockPos();
            return protectsInteraction(serverPlayer, hit)
                    || protectsInteraction(serverPlayer, hit.relative(hitResult.getDirection()))
                    ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            enforcePlayerBoundaries(server);
            if (server.getTickCount() % 20 == 0) rebuild(server, false);
        });
    }

    // Return all persisted restriction definitions in deterministic identifier order.
    public static List<Restriction> restrictions(MinecraftServer server) {
        return RestrictionData.get(server).values().values().stream()
                .sorted(Comparator.comparing(restriction -> restriction.id().toString())).toList();
    }

    // Resolve one restriction by its complete identifier.
    public static Restriction get(MinecraftServer server, Identifier id) {
        return RestrictionData.get(server).values().get(id.toString());
    }

    // Create a new restriction, rejecting an identifier already present in world data.
    public static void create(MinecraftServer server, Restriction restriction) {
        if (get(server, restriction.id()) != null) throw new IllegalArgumentException("Restriction already exists: " + restriction.id());
        RestrictionData.get(server).put(restriction);
        rebuild(server, true);
    }

    // Replace an existing restriction after validating that its identifier is already stored.
    public static void update(MinecraftServer server, Restriction restriction) {
        Restriction existing = get(server, restriction.id());
        if (existing == null) throw new IllegalArgumentException("Unknown restriction: " + restriction.id());
        RestrictionData.get(server).put(restriction);
        rebuild(server, true);
    }

    // Delete an restriction and immediately remove it from collision and client state.
    public static boolean remove(MinecraftServer server, Identifier id) {
        if (RestrictionData.get(server).remove(id) == null) return false;
        rebuild(server, true);
        return true;
    }

    // Return how many restrictions are present in the current server collision index.
    public static int solidRestrictionCount(MinecraftServer server) {
        return runtime(server).solidIndex().restrictionCount();
    }

    // Immediately rebuild resolved indexes when CWP reapplies a changed global Advanced World Boundary level.
    public static void refreshLevel(MinecraftServer server) {
        rebuild(server, false);
    }

    // Reject block use, breaking, and ordinary adjacent placement within one block of a solid restriction box.
    public static boolean protectsInteraction(ServerPlayer player, BlockPos position) {
        MinecraftServer server = player.level().getServer();
        RuntimeState runtime = runtime(server);
        Identifier dimension = player.level().dimension().identifier();
        return RestrictionCollision.protectsBlock(dimension, position, runtime.interactionIndex());
    }

    // Enforce both one-way boundary modes and retain the latest valid position on the permitted side.
    private static void enforcePlayerBoundaries(MinecraftServer server) {
        RuntimeState runtime = runtime(server);
        Map<UUID, SafePosition> safePositions;
        synchronized (SAFE_POSITIONS) {
            safePositions = SAFE_POSITIONS.computeIfAbsent(server, ignored -> new HashMap<>());
        }
        Map<UUID, Map<Identifier, SafePosition>> containedPositions;
        synchronized (CONTAINED_POSITIONS) {
            containedPositions = CONTAINED_POSITIONS.computeIfAbsent(server, ignored -> new HashMap<>());
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Identifier dimension = player.level().dimension().identifier();
            Vec3 bodyCenter = player.position().add(0.0, 0.9, 0.0);
            Restriction keepOut = containing(runtime.keepOutIndex(), dimension, bodyCenter);
            if (keepOut == null) {
                safePositions.put(player.getUUID(), new SafePosition(dimension, player.position()));
            } else {
                player.stopRiding();
                boolean teleported = tryNearbyRejection(player, runtime.solidIndex(), dimension);
                if (!teleported) {
                    SafePosition remembered = safePositions.get(player.getUUID());
                    teleported = tryRememberedPosition(player, runtime.solidIndex(), dimension, remembered);
                }
                if (!teleported) {
                    teleported = tryHorizontalBoundaryRejection(player, runtime.solidIndex(), dimension, keepOut);
                }
                if (!teleported) forceOutside(player, runtime.solidIndex(), dimension, keepOut, bodyCenter);
                safePositions.put(player.getUUID(), new SafePosition(dimension, player.position()));
                CrazyWorldProgression.LOGGER.info("Restriction {} rejected {} with a chorus teleport",
                        keepOut.id(), player.getGameProfile().name());
                continue;
            }

            trackAndEnforceContainment(player, runtime, containedPositions
                    .computeIfAbsent(player.getUUID(), ignored -> new HashMap<>()));
        }
    }

    // Remember entered keep-in restrictions, return outward crossings, and release membership on dimension travel.
    private static void trackAndEnforceContainment(ServerPlayer player, RuntimeState runtime,
                                                   Map<Identifier, SafePosition> memberships) {
        Identifier dimension = player.level().dimension().identifier();
        Vec3 bodyCenter = player.position().add(0.0, PLAYER_HALF_HEIGHT, 0.0);
        memberships.entrySet().removeIf(entry -> {
            Restriction restriction = runtime.keepInById().get(entry.getKey());
            SafePosition remembered = entry.getValue();
            return restriction == null || !dimension.equals(restriction.dimension())
                    || !remembered.dimension().equals(restriction.dimension())
                    || !fullyContainsPlayer(restriction,
                    remembered.feet().add(0.0, PLAYER_HALF_HEIGHT, 0.0));
        });

        Set<Identifier> currentlyInside = new HashSet<>();
        for (Restriction restriction : fullyContainingAll(runtime.keepInIndex(), dimension, bodyCenter)) {
            currentlyInside.add(restriction.id());
            memberships.put(restriction.id(), new SafePosition(dimension, player.position()));
        }
        for (Map.Entry<Identifier, SafePosition> entry : new ArrayList<>(memberships.entrySet())) {
            if (currentlyInside.contains(entry.getKey())) continue;
            Restriction restriction = runtime.keepInById().get(entry.getKey());
            if (restriction == null) continue;
            player.stopRiding();
            if (!returnInside(player, runtime.keepOutIndex(), restriction, entry.getValue())) {
                memberships.remove(entry.getKey());
                CrazyWorldProgression.LOGGER.warn("Released {} from Restriction {}: no safe inside position remains",
                        player.getGameProfile().name(), restriction.id());
                continue;
            }
            memberships.put(restriction.id(), new SafePosition(restriction.dimension(), player.position()));
            CrazyWorldProgression.LOGGER.info("Restriction {} contained {} with a chorus teleport",
                    restriction.id(), player.getGameProfile().name());
            break;
        }
    }

    // Find a safe chorus landing inside the containing restriction, falling back to its last known inside position.
    private static boolean returnInside(ServerPlayer player, RestrictionSpatialIndex keepOutIndex,
                                        Restriction restriction, SafePosition remembered) {
        Vec3 origin = player.position();
        for (int attempt = 0; attempt < RANDOM_TELEPORT_ATTEMPTS; attempt++) {
            double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(player.getRandom().nextDouble()) * REJECTION_RADIUS;
            Vec3 requested = new Vec3(origin.x + Math.cos(angle) * distance,
                    origin.y + (player.getRandom().nextDouble() * 2.0 - 1.0) * REJECTION_VERTICAL_RANGE,
                    origin.z + Math.sin(angle) * distance);
            if (tryContainedChorusTeleport(player, keepOutIndex, restriction, requested, origin)) return true;
        }
        if (tryContainedChorusTeleport(player, keepOutIndex, restriction, remembered.feet(), origin)) return true;
        return forceInside(player, keepOutIndex, restriction, remembered);
    }

    // Validate that vanilla's final landing remains inside the selected restriction and outside every keep-out restriction.
    private static boolean tryContainedChorusTeleport(ServerPlayer player, RestrictionSpatialIndex keepOutIndex,
                                                      Restriction restriction, Vec3 requested, Vec3 effectOrigin) {
        Vec3 landing = findLanding(player.level(), requested);
        Vec3 landingBody = landing == null ? null : landing.add(0.0, PLAYER_HALF_HEIGHT, 0.0);
        if (landingBody == null || !fullyContainsPlayer(restriction, landingBody)
                || containing(keepOutIndex, restriction.dimension(), landingBody) != null) return false;
        if (!player.randomTeleport(requested.x, requested.y, requested.z, true)) return false;
        Vec3 finalBody = player.position().add(0.0, PLAYER_HALF_HEIGHT, 0.0);
        if (!fullyContainsPlayer(restriction, finalBody)
                || containing(keepOutIndex, restriction.dimension(), finalBody) != null) {
            player.teleportTo(effectOrigin.x, effectOrigin.y, effectOrigin.z);
            return false;
        }
        finishChorusEffect(player, effectOrigin);
        return true;
    }

    // Guarantee same-dimension containment at the last server-observed inside position.
    private static boolean forceInside(ServerPlayer player, RestrictionSpatialIndex keepOutIndex,
                                       Restriction restriction, SafePosition remembered) {
        Vec3 bodyCenter = remembered.feet().add(0.0, PLAYER_HALF_HEIGHT, 0.0);
        if (!fullyContainsPlayer(restriction, bodyCenter)
                || containing(keepOutIndex, restriction.dimension(), bodyCenter) != null) {
            return false;
        }
        player.teleportTo(remembered.feet().x, remembered.feet().y, remembered.feet().z);
        finishChorusEffect(player, player.position());
        return true;
    }

    // Try chorus-like destinations within a six-block horizontal radius and four vertical blocks of entry.
    private static boolean tryNearbyRejection(ServerPlayer player, RestrictionSpatialIndex index,
                                              Identifier dimension) {
        Vec3 origin = player.position();
        ServerLevel level = player.level();
        for (int attempt = 0; attempt < RANDOM_TELEPORT_ATTEMPTS; attempt++) {
            double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(player.getRandom().nextDouble()) * REJECTION_RADIUS;
            double x = origin.x + Math.cos(angle) * distance;
            double y = Mth.clamp(origin.y
                            + (player.getRandom().nextDouble() * 2.0 - 1.0) * REJECTION_VERTICAL_RANGE,
                    level.getMinY(), level.getMinY() + level.getLogicalHeight() - 1.0);
            double z = origin.z + Math.sin(angle) * distance;
            if (trySafeChorusTeleport(player, index, dimension, new Vec3(x, y, z), origin)) return true;
        }
        return false;
    }

    // Use the latest nearby outside position only when random landing searches cannot find safe terrain.
    private static boolean tryRememberedPosition(ServerPlayer player, RestrictionSpatialIndex index,
                                                 Identifier dimension, SafePosition remembered) {
        if (remembered == null || !remembered.dimension().equals(dimension)
                || remembered.feet().distanceToSqr(player.position()) > 144.0) return false;
        return trySafeChorusTeleport(player, index, dimension, remembered.feet(), player.position());
    }

    // Search just beyond the restriction's horizontal footprint when a large roof area covers every local candidate.
    private static boolean tryHorizontalBoundaryRejection(ServerPlayer player, RestrictionSpatialIndex index,
                                                          Identifier dimension, Restriction restriction) {
        AABB bounds = restriction.bounds();
        double preferredX = player.getX() - restriction.centerX();
        double preferredZ = player.getZ() - restriction.centerZ();
        double preferredAngle = preferredX * preferredX + preferredZ * preferredZ < 1.0E-8
                ? player.getRandom().nextDouble() * Math.PI * 2.0 : Math.atan2(preferredZ, preferredX);
        Vec3 origin = player.position();
        for (int attempt = 0; attempt < BOUNDARY_TELEPORT_ATTEMPTS; attempt++) {
            double angle = attempt == 0 ? preferredAngle : player.getRandom().nextDouble() * Math.PI * 2.0;
            double directionX = Math.cos(angle);
            double directionZ = Math.sin(angle);
            double toXFace = Math.abs(directionX) < 1.0E-8 ? Double.POSITIVE_INFINITY
                    : ((bounds.maxX - bounds.minX) * 0.5 + PLAYER_HALF_WIDTH + 1.0)
                    / Math.abs(directionX);
            double toZFace = Math.abs(directionZ) < 1.0E-8 ? Double.POSITIVE_INFINITY
                    : ((bounds.maxZ - bounds.minZ) * 0.5 + PLAYER_HALF_WIDTH + 1.0)
                    / Math.abs(directionZ);
            double distance = Math.min(toXFace, toZFace);
            Vec3 requested = new Vec3(restriction.centerX() + directionX * distance,
                    Math.min(player.getY() + REJECTION_VERTICAL_RANGE,
                            player.level().getMinY() + player.level().getLogicalHeight() - 1.0),
                    restriction.centerZ() + directionZ * distance);
            if (trySafeChorusTeleport(player, index, dimension, requested, origin)) return true;
        }
        return false;
    }

    // Validate vanilla's eventual ground landing before invoking its teleport and particle implementation.
    private static boolean trySafeChorusTeleport(ServerPlayer player, RestrictionSpatialIndex index,
                                                 Identifier dimension, Vec3 requested, Vec3 effectOrigin) {
        Vec3 landing = findLanding(player.level(), requested);
        if (landing == null || containing(index, dimension, landing.add(0.0, PLAYER_HALF_HEIGHT, 0.0)) != null) {
            return false;
        }
        if (!player.randomTeleport(requested.x, requested.y, requested.z, true)) return false;
        if (containing(index, dimension, player.position().add(0.0, PLAYER_HALF_HEIGHT, 0.0)) != null) {
            player.teleportTo(effectOrigin.x, effectOrigin.y, effectOrigin.z);
            return false;
        }
        finishChorusEffect(player, effectOrigin);
        return true;
    }

    // Mirror vanilla chorus-fruit ground search without loading new chunks so restriction validation sees the final point.
    private static Vec3 findLanding(ServerLevel level, Vec3 requested) {
        BlockPos cursor = BlockPos.containing(requested);
        if (!level.hasChunkAt(cursor)) return null;
        double landingY = requested.y;
        while (cursor.getY() > level.getMinY()) {
            BlockPos below = cursor.below();
            if (level.getBlockState(below).blocksMotion()) return new Vec3(requested.x, landingY, requested.z);
            landingY -= 1.0;
            cursor = below;
        }
        return null;
    }

    // Add the chorus sound, game event, fall reset, and impulse reset after a validated teleport succeeds.
    private static void finishChorusEffect(ServerPlayer player, Vec3 origin) {
        ServerLevel level = player.level();
        level.gameEvent(GameEvent.TELEPORT, origin, GameEvent.Context.of(player));
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CHORUS_FRUIT_TELEPORT,
                SoundSource.PLAYERS);
        player.resetFallDistance();
        player.resetCurrentImpulseContext();
        player.setDeltaMovement(Vec3.ZERO);
    }

    // Guarantee removal from protected geometry if random and remembered chorus destinations both fail.
    private static void forceOutside(ServerPlayer player, RestrictionSpatialIndex index, Identifier dimension,
                                     Restriction firstContaining, Vec3 bodyCenter) {
        AABB escapeBounds = firstContaining.bounds().inflate(PLAYER_HALF_WIDTH, PLAYER_HALF_HEIGHT,
                PLAYER_HALF_WIDTH);
        Vec3 outsideCenter = outsideHorizontally(escapeBounds, bodyCenter, 1.0);
        for (int attempt = 0; attempt <= index.restrictionCount(); attempt++) {
            double requestedY = Math.min(player.getY() + REJECTION_VERTICAL_RANGE,
                    player.level().getMinY() + player.level().getLogicalHeight() - 1.0);
            Vec3 requested = new Vec3(outsideCenter.x, requestedY, outsideCenter.z);
            Vec3 landing = findLanding(player.level(), requested);
            Vec3 destination = landing == null ? new Vec3(outsideCenter.x, player.getY(), outsideCenter.z) : landing;
            Restriction overlap = containing(index, dimension,
                    destination.add(0.0, PLAYER_HALF_HEIGHT, 0.0));
            if (overlap == null) {
                Vec3 origin = player.position();
                player.teleportTo(destination.x, destination.y, destination.z);
                finishChorusEffect(player, origin);
                return;
            }
            AABB other = overlap.bounds().inflate(PLAYER_HALF_WIDTH, PLAYER_HALF_HEIGHT, PLAYER_HALF_WIDTH);
            escapeBounds = new AABB(Math.min(escapeBounds.minX, other.minX), Math.min(escapeBounds.minY, other.minY),
                    Math.min(escapeBounds.minZ, other.minZ), Math.max(escapeBounds.maxX, other.maxX),
                    Math.max(escapeBounds.maxY, other.maxY), Math.max(escapeBounds.maxZ, other.maxZ));
            outsideCenter = outsideHorizontally(escapeBounds, bodyCenter, 1.0);
        }
        throw new IllegalStateException("Could not calculate an outside point for solid Restrictions");
    }

    // Select the nearest horizontal face so gravity cannot drop the player back through an upper escape point.
    private static Vec3 outsideHorizontally(AABB box, Vec3 point, double clearance) {
        double toMinX = Math.abs(point.x - box.minX);
        double toMaxX = Math.abs(box.maxX - point.x);
        double toMinZ = Math.abs(point.z - box.minZ);
        double toMaxZ = Math.abs(box.maxZ - point.z);
        double nearest = Math.min(Math.min(toMinX, toMaxX), Math.min(toMinZ, toMaxZ));
        if (nearest == toMinX) return new Vec3(box.minX - clearance, point.y, point.z);
        if (nearest == toMaxX) return new Vec3(box.maxX + clearance, point.y, point.z);
        if (nearest == toMinZ) return new Vec3(point.x, point.y, box.minZ - clearance);
        return new Vec3(point.x, point.y, box.maxZ + clearance);
    }

    // Resolve one containing solid restriction through a nearby query covering the complete fixed player body.
    private static Restriction containing(RestrictionSpatialIndex index, Identifier dimension, Vec3 bodyCenter) {
        AABB bodyBounds = AABB.ofSize(bodyCenter, PLAYER_HALF_WIDTH * 2.0, PLAYER_HALF_HEIGHT * 2.0,
                PLAYER_HALF_WIDTH * 2.0);
        for (Restriction restriction : index.query(dimension, bodyBounds)) {
            if (restriction.contains(bodyCenter, PLAYER_HALF_WIDTH, PLAYER_HALF_HEIGHT)) return restriction;
        }
        return null;
    }

    // Resolve regions fully enclosing the standing-size body so their last stored position never crosses the shell.
    private static List<Restriction> fullyContainingAll(RestrictionSpatialIndex index, Identifier dimension,
                                                        Vec3 bodyCenter) {
        AABB bodyBounds = AABB.ofSize(bodyCenter, PLAYER_HALF_WIDTH * 2.0, PLAYER_HALF_HEIGHT * 2.0,
                PLAYER_HALF_WIDTH * 2.0);
        List<Restriction> matches = new ArrayList<>();
        for (Restriction restriction : index.query(dimension, bodyBounds)) {
            if (fullyContainsPlayer(restriction, bodyCenter)) matches.add(restriction);
        }
        return matches;
    }

    // Test all standing-body corners so even curved and sloped shells only remember fully inside positions.
    private static boolean fullyContainsPlayer(Restriction restriction, Vec3 bodyCenter) {
        for (int xSign = -1; xSign <= 1; xSign += 2) {
            for (int ySign = -1; ySign <= 1; ySign += 2) {
                for (int zSign = -1; zSign <= 1; zSign += 2) {
                    Vec3 corner = bodyCenter.add(xSign * PLAYER_HALF_WIDTH, ySign * PLAYER_HALF_HEIGHT,
                            zSign * PLAYER_HALF_WIDTH);
                    if (!restriction.contains(corner, 0.0, 0.0)) return false;
                }
            }
        }
        return true;
    }

    // Rebuild resolved runtime collections only when definitions changed or the Advanced World Boundary level crossed a threshold.
    private static void rebuild(MinecraftServer server, boolean force) {
        int boundaryLevel = AdvancedWorldBoundary.getLevel(server);
        List<ProjectionSpec> projections = projectionSpecs(server);
        RuntimeState previous;
        synchronized (RUNTIMES) {
            previous = RUNTIMES.get(server);
        }
        if (!force && previous != null && previous.boundaryLevel() == boundaryLevel
                && previous.projections().equals(projections)) return;
        List<Restriction> saved = restrictions(server);
        List<Restriction> resolved = new ArrayList<>(saved);
        for (ProjectionSpec projection : projections) {
            for (Restriction restriction : saved) {
                if (restriction.dimension().equals(projection.editorDimension())) {
                    resolved.add(RestrictionProjection.intoInstance(restriction, projection.instance(),
                            projection.captureOrigin()));
                }
            }
        }
        List<Restriction> restrictions = List.copyOf(resolved);
        RestrictionSpatialIndex solidIndex = new RestrictionSpatialIndex();
        RestrictionSpatialIndex interactionIndex = new RestrictionSpatialIndex();
        RestrictionSpatialIndex keepOutIndex = new RestrictionSpatialIndex();
        RestrictionSpatialIndex keepInIndex = new RestrictionSpatialIndex();
        Map<Identifier, Restriction> keepInById = new HashMap<>();
        for (Restriction restriction : restrictions) {
            Restriction.RestrictionState state = restriction.stateAt(boundaryLevel);
            if (state.protectsBlocks()) interactionIndex.add(restriction);
            if (state.collision() != SOLID) continue;
            solidIndex.add(restriction);
            if (restriction.boundaryMode() == KEEP_OUT) {
                keepOutIndex.add(restriction);
            } else if (restriction.boundaryMode() == KEEP_IN) {
                keepInIndex.add(restriction);
                keepInById.put(restriction.id(), restriction);
            }
        }
        RuntimeState current = new RuntimeState(boundaryLevel, projections, restrictions,
                solidIndex, interactionIndex, keepOutIndex,
                keepInIndex, keepInById);
        synchronized (RUNTIMES) {
            RUNTIMES.put(server, current);
        }
        server.getPlayerList().getPlayers().forEach(player ->
                RestrictionNetworking.send(player, current.boundaryLevel(), current.restrictions()));
        CrazyWorldProgression.LOGGER.info("Indexed {} solid Restriction/restrictions ({} keep-out, {} keep-in) and {} "
                        + "block-protected restriction/restrictions out of {} at Advanced World Boundary level {}", solidIndex.restrictionCount(),
                keepOutIndex.restrictionCount(), keepInIndex.restrictionCount(), interactionIndex.restrictionCount(),
                restrictions.size(), boundaryLevel);
    }

    // Track the instance lease and editor capture origin so recaptures and expiry refresh projections.
    private static List<ProjectionSpec> projectionSpecs(MinecraftServer server) {
        List<ProjectionSpec> specs = new ArrayList<>();
        for (PortalService.ActiveInstance instance : PortalService.activeInstances(server)) {
            Identifier pocketId = instance.templateId();
            Identifier editor = PocketEditorService.dimension(server, pocketId);
            if (editor == null) continue;
            BlockPos origin = PocketEditorService.captureOrigin(server, pocketId);
            if (origin == null) {
                PortalTemplateService.TemplateInfo template = PortalTemplateService.template(server, pocketId);
                BlockPos entry = PocketEditorService.entry(server, pocketId);
                if (template != null && entry != null) {
                    origin = entry.offset(-template.spawnX(), -template.spawnY(), -template.spawnZ());
                }
            }
            if (origin != null) specs.add(new ProjectionSpec(instance, editor, origin));
        }
        return List.copyOf(specs);
    }

    // Send the current resolved level and complete definitions to one newly connected client.
    private static void sendSnapshot(ServerPlayer player) {
        RuntimeState runtime = runtime(player.level().getServer());
        RestrictionNetworking.send(player, runtime.boundaryLevel(), runtime.restrictions());
    }

    // Return initialized runtime state even if a movement hook runs unusually early during startup.
    private static RuntimeState runtime(MinecraftServer server) {
        synchronized (RUNTIMES) {
            RuntimeState state = RUNTIMES.get(server);
            if (state != null) return state;
        }
        rebuild(server, true);
        synchronized (RUNTIMES) {
            return RUNTIMES.get(server);
        }
    }

    private record ProjectionSpec(PortalService.ActiveInstance instance, Identifier editorDimension,
                                  BlockPos captureOrigin) {
    }

    private record RuntimeState(int boundaryLevel, List<ProjectionSpec> projections, List<Restriction> restrictions,
                                RestrictionSpatialIndex solidIndex, RestrictionSpatialIndex interactionIndex,
                                RestrictionSpatialIndex keepOutIndex, RestrictionSpatialIndex keepInIndex,
                                Map<Identifier, Restriction> keepInById) {
        // Freeze definition snapshots independently from persistent mutable storage.
        private RuntimeState {
            projections = List.copyOf(projections);
            restrictions = List.copyOf(restrictions);
            keepInById = Map.copyOf(keepInById);
        }
    }

    /** A server-observed player feet position on the permitted side of a one-way restriction boundary. */
    private record SafePosition(Identifier dimension, Vec3 feet) {
    }
}
