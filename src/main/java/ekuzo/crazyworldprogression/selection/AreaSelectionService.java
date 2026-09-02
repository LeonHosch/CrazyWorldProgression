package ekuzo.crazyworldprogression.selection;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shared click-driven cuboid selector with visible edges and batched area clearing. */
public final class AreaSelectionService {
    private static final int CLEAR_BLOCKS_PER_TICK = 4096;
    private static final int MAX_EDGE_PARTICLES = 16;
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();
    private static final List<ClearTask> CLEAR_TASKS = new ArrayList<>();

    // Prevent construction of the static selection service.
    private AreaSelectionService() {
    }

    // Register private visualization ticks and lifecycle cleanup.
    public static void initialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (SELECTIONS) {
                SELECTIONS.clear();
            }
            synchronized (CLEAR_TASKS) {
                CLEAR_TASKS.clear();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processClears(server);
            if (server.getTickCount() % 5L == 0L) renderSelections(server);
        });
    }

    // Start or replace one player's selection session on behalf of an owning mod feature.
    public static void begin(ServerPlayer player, Identifier owner) {
        Selection selection = new Selection(owner, player.level().dimension().identifier(), null, null, null);
        synchronized (SELECTIONS) {
            SELECTIONS.put(player.getUUID(), selection);
        }
        AreaSelectionRegistry.changed(player, selection);
        AreaSelectionNetworking.send(player, true);
        player.sendOverlayMessage(Component.literal("Area selection: left-click first corner, right-click second corner"));
    }

    // Return the current immutable selection for integrations and admin workflows.
    public static Optional<Selection> selection(ServerPlayer player) {
        synchronized (SELECTIONS) {
            return Optional.ofNullable(SELECTIONS.get(player.getUUID()));
        }
    }

    // Return whether block clicks are currently reserved for area selection.
    public static boolean isSelecting(ServerPlayer player) {
        return selection(player).isPresent();
    }

    // Set an optional anchor inside the completed cuboid for spawn points or other integration metadata.
    public static Selection setAnchor(ServerPlayer player, BlockPos position) {
        Selection updated;
        synchronized (SELECTIONS) {
            Selection current = SELECTIONS.get(player.getUUID());
            if (current == null) throw new IllegalStateException("Start area selection first");
            if (!current.complete()) throw new IllegalStateException("Select both corners first");
            if (!current.dimension().equals(player.level().dimension().identifier())) {
                throw new IllegalStateException("Return to the selected dimension first");
            }
            if (!current.contains(position)) throw new IllegalArgumentException("Anchor must be inside the selection");
            updated = new Selection(current.owner(), current.dimension(), current.first(), current.second(),
                    position.immutable());
            SELECTIONS.put(player.getUUID(), updated);
        }
        AreaSelectionRegistry.changed(player, updated);
        player.sendOverlayMessage(Component.literal("Selection anchor: " + position.toShortString()));
        return updated;
    }

    // Validate and apply a client selector click without permitting arbitrary remote coordinates.
    static void selectPoint(ServerPlayer player, BlockPos position, boolean first) {
        if (!isSelecting(player) || !player.level().isLoaded(position)
                || player.blockPosition().distSqr(position) > 1024.0) return;
        if (first) setFirst(player, position);
        else setSecond(player, position);
    }

    // Clear one player's selection mode and notify interested integrations.
    public static void clear(ServerPlayer player) {
        Selection removed;
        synchronized (SELECTIONS) {
            removed = SELECTIONS.remove(player.getUUID());
        }
        if (removed != null) AreaSelectionRegistry.changed(player, null);
        if (removed != null) AreaSelectionNetworking.send(player, false);
    }

    // Schedule the selected cuboid to be replaced with air without blocking one server tick.
    public static long clearSelectedArea(ServerPlayer player) {
        Selection selection = requireComplete(player);
        if (!selection.dimension().equals(player.level().dimension().identifier())) {
            throw new IllegalStateException("Return to the selected dimension before clearing it");
        }
        synchronized (CLEAR_TASKS) {
            CLEAR_TASKS.add(new ClearTask(player.level(), selection.minimum(), selection.maximum(), 0L));
        }
        return selection.volume();
    }

    // Require two selected corners and return their normalized immutable bounds.
    public static Selection requireComplete(ServerPlayer player) {
        Selection selection = selection(player).orElseThrow(() ->
                new IllegalStateException("Start area selection first"));
        if (!selection.complete()) throw new IllegalStateException("Select both corners first");
        return selection;
    }

    // Set the left-click corner and reset cross-dimension selections to the player's current level.
    private static void setFirst(ServerPlayer player, BlockPos position) {
        updatePoint(player, position, true);
    }

    // Set the right-click corner and reset cross-dimension selections to the player's current level.
    private static void setSecond(ServerPlayer player, BlockPos position) {
        updatePoint(player, position, false);
    }

    // Apply one point, notify hooks, and show concise coordinates and volume to the selecting player.
    private static void updatePoint(ServerPlayer player, BlockPos position, boolean first) {
        Selection updated;
        synchronized (SELECTIONS) {
            Selection current = SELECTIONS.get(player.getUUID());
            if (current == null) return;
            Identifier dimension = player.level().dimension().identifier();
            boolean sameDimension = current.dimension().equals(dimension);
            updated = new Selection(current.owner(), dimension,
                    first ? position.immutable() : sameDimension ? current.first() : null,
                    first ? sameDimension ? current.second() : null : position.immutable(),
                    sameDimension ? current.anchor() : null);
            if (updated.anchor() != null && updated.complete() && !updated.contains(updated.anchor())) {
                updated = new Selection(updated.owner(), updated.dimension(), updated.first(), updated.second(), null);
            }
            SELECTIONS.put(player.getUUID(), updated);
        }
        AreaSelectionRegistry.changed(player, updated);
        String label = first ? "First" : "Second";
        player.sendOverlayMessage(Component.literal(label + " corner: " + position.toShortString()
                + (updated.complete() ? " · " + updated.sizeX() + "×" + updated.sizeY() + "×"
                + updated.sizeZ() + " (" + updated.volume() + " blocks)" : "")));
    }

    // Draw a private particle wireframe around every active selection at a bounded density.
    private static void renderSelections(MinecraftServer server) {
        Map<UUID, Selection> snapshot;
        synchronized (SELECTIONS) {
            snapshot = Map.copyOf(SELECTIONS);
        }
        for (Map.Entry<UUID, Selection> entry : snapshot.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            Selection selection = entry.getValue();
            if (!selection.dimension().equals(player.level().dimension().identifier())) continue;
            if (!selection.complete()) {
                BlockPos point = selection.first() != null ? selection.first() : selection.second();
                if (point != null) particle(player, point.getX() + 0.5, point.getY() + 0.5, point.getZ() + 0.5);
                continue;
            }
            renderBox(player, selection);
            if (selection.anchor() != null) anchorParticle(player, selection.anchor());
        }
    }

    // Sample all twelve box edges without letting huge selections create excessive packets.
    private static void renderBox(ServerPlayer player, Selection selection) {
        double minX = selection.minimum().getX();
        double minY = selection.minimum().getY();
        double minZ = selection.minimum().getZ();
        double maxX = selection.maximum().getX() + 1.0;
        double maxY = selection.maximum().getY() + 1.0;
        double maxZ = selection.maximum().getZ() + 1.0;
        renderAxisEdges(player, minX, maxX, minY, maxY, minZ, maxZ, 0);
        renderAxisEdges(player, minY, maxY, minX, maxX, minZ, maxZ, 1);
        renderAxisEdges(player, minZ, maxZ, minX, maxX, minY, maxY, 2);
    }

    // Render four parallel sampled edges for one axis of a cuboid.
    private static void renderAxisEdges(ServerPlayer player, double start, double end,
                                        double sideA0, double sideA1, double sideB0, double sideB1, int axis) {
        int samples = Math.min(MAX_EDGE_PARTICLES, Math.max(2, (int) Math.ceil(end - start) + 1));
        for (int index = 0; index < samples; index++) {
            double value = start + (end - start) * index / (samples - 1.0);
            edgeParticle(player, axis, value, sideA0, sideB0);
            edgeParticle(player, axis, value, sideA0, sideB1);
            edgeParticle(player, axis, value, sideA1, sideB0);
            edgeParticle(player, axis, value, sideA1, sideB1);
        }
    }

    // Convert one axis-local edge point back to world coordinates.
    private static void edgeParticle(ServerPlayer player, int axis, double value, double sideA, double sideB) {
        if (axis == 0) particle(player, value, sideA, sideB);
        else if (axis == 1) particle(player, sideA, value, sideB);
        else particle(player, sideA, sideB, value);
    }

    // Send one selection particle only to its owning administrator.
    private static void particle(ServerPlayer player, double x, double y, double z) {
        ((ServerLevel) player.level()).sendParticles(player, ParticleTypes.END_ROD,
                false, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    // Mark the optional anchor more prominently than the sampled cuboid edges.
    private static void anchorParticle(ServerPlayer player, BlockPos position) {
        ((ServerLevel) player.level()).sendParticles(player, ParticleTypes.SOUL_FIRE_FLAME,
                false, false, position.getX() + 0.5, position.getY() + 0.2, position.getZ() + 0.5,
                4, 0.15, 0.1, 0.15, 0.0);
    }

    // Clear bounded batches from all scheduled areas and remove completed jobs.
    private static void processClears(MinecraftServer server) {
        synchronized (CLEAR_TASKS) {
            for (int taskIndex = CLEAR_TASKS.size() - 1; taskIndex >= 0; taskIndex--) {
                ClearTask task = CLEAR_TASKS.get(taskIndex);
                if (task.level().getServer() != server) continue;
                long volume = volume(task.minimum(), task.maximum());
                long end = Math.min(volume, task.nextIndex() + CLEAR_BLOCKS_PER_TICK);
                int sizeX = task.maximum().getX() - task.minimum().getX() + 1;
                int sizeZ = task.maximum().getZ() - task.minimum().getZ() + 1;
                long layer = (long) sizeX * sizeZ;
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (long index = task.nextIndex(); index < end; index++) {
                    int y = (int) (index / layer);
                    long within = index % layer;
                    int z = (int) (within / sizeX);
                    int x = (int) (within % sizeX);
                    cursor.set(task.minimum().getX() + x, task.minimum().getY() + y,
                            task.minimum().getZ() + z);
                    if (!task.level().getBlockState(cursor).isAir()) {
                        task.level().setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                if (end >= volume) CLEAR_TASKS.remove(taskIndex);
                else CLEAR_TASKS.set(taskIndex, new ClearTask(task.level(), task.minimum(), task.maximum(), end));
            }
        }
    }

    // Calculate an inclusive cuboid volume using widened arithmetic.
    private static long volume(BlockPos minimum, BlockPos maximum) {
        return (long) (maximum.getX() - minimum.getX() + 1)
                * (maximum.getY() - minimum.getY() + 1)
                * (maximum.getZ() - minimum.getZ() + 1);
    }

    /** One mod-owned selection session; corners may be incomplete while the player is choosing them. */
    public record Selection(Identifier owner, Identifier dimension, BlockPos first, BlockPos second,
                            BlockPos anchor) {
        // Return whether both corners are ready for a consuming workflow.
        public boolean complete() {
            return first != null && second != null;
        }

        // Return the inclusive minimum corner of a complete selection.
        public BlockPos minimum() {
            requireComplete();
            return new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ()));
        }

        // Return the inclusive maximum corner of a complete selection.
        public BlockPos maximum() {
            requireComplete();
            return new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                    Math.max(first.getZ(), second.getZ()));
        }

        // Return the selected x-axis block count.
        public int sizeX() {
            return maximum().getX() - minimum().getX() + 1;
        }

        // Return the selected y-axis block count.
        public int sizeY() {
            return maximum().getY() - minimum().getY() + 1;
        }

        // Return the selected z-axis block count.
        public int sizeZ() {
            return maximum().getZ() - minimum().getZ() + 1;
        }

        // Return the inclusive selected block volume.
        public long volume() {
            return AreaSelectionService.volume(minimum(), maximum());
        }

        // Return whether one block lies inside this selection's inclusive normalized bounds.
        public boolean contains(BlockPos position) {
            BlockPos minimum = minimum();
            BlockPos maximum = maximum();
            return position.getX() >= minimum.getX() && position.getX() <= maximum.getX()
                    && position.getY() >= minimum.getY() && position.getY() <= maximum.getY()
                    && position.getZ() >= minimum.getZ() && position.getZ() <= maximum.getZ();
        }

        // Reject bounds queries until both interaction points exist.
        private void requireComplete() {
            if (!complete()) throw new IllegalStateException("Selection is incomplete");
        }
    }

    private record ClearTask(ServerLevel level, BlockPos minimum, BlockPos maximum, long nextIndex) {
    }
}
