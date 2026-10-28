package ekuzo.crazyworldprogression.worldboundary.restriction;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Uniform-grid broad phase containing only restrictions whose current state rejects players and interactions. */
final class RestrictionSpatialIndex {
    private static final int CELL_SIZE = 64;
    private final Map<Identifier, Map<Cell, List<Restriction>>> dimensions = new HashMap<>();
    private int restrictionCount;

    // Insert one rejecting restriction into every horizontal cell overlapped by its broad-phase bounds.
    void add(Restriction restriction) {
        AABB bounds = restriction.bounds();
        int minX = cell(bounds.minX);
        int maxX = cell(bounds.maxX);
        int minZ = cell(bounds.minZ);
        int maxZ = cell(bounds.maxZ);
        Map<Cell, List<Restriction>> cells = dimensions.computeIfAbsent(restriction.dimension(), ignored -> new HashMap<>());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                cells.computeIfAbsent(new Cell(x, z), ignored -> new ArrayList<>()).add(restriction);
            }
        }
        restrictionCount++;
    }

    // Return unique rejecting candidates whose indexed cells overlap one query bound.
    List<Restriction> query(Identifier dimension, AABB bounds) {
        Map<Cell, List<Restriction>> cells = dimensions.get(dimension);
        if (cells == null) return List.of();
        LinkedHashSet<Restriction> result = new LinkedHashSet<>();
        for (int x = cell(bounds.minX); x <= cell(bounds.maxX); x++) {
            for (int z = cell(bounds.minZ); z <= cell(bounds.maxZ); z++) {
                result.addAll(cells.getOrDefault(new Cell(x, z), List.of()));
            }
        }
        return List.copyOf(result);
    }

    // Report how many distinct rejection-enabled restrictions were inserted, excluding cell duplication.
    int restrictionCount() {
        return restrictionCount;
    }

    // Map one world coordinate to a stable signed grid cell.
    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private record Cell(int x, int z) {
    }
}
