package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.NodeSnapshot;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.TreeSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SkillTreeLayout {
    static final int NODE_SIZE = 26;
    private static final int NODE_HORIZONTAL_SPACING = 52;
    private static final int NODE_VERTICAL_SPACING = 38;
    private static final int CONNECTION_TRACK_MARGIN = 6;
    private static final int CONNECTION_TARGET_OFFSET = 8;

    // Prevent this static layout utility from being instantiated.
    private SkillTreeLayout() {
    }

    // Build immutable node positions, connector routes, and pan bounds for one tree tab.
    static Result build(TreeSnapshot tree) {
        return new Builder(tree).build();
    }

    private static final class Builder {
        private final TreeSnapshot tree;
        private final Map<String, NodePosition> nodePositions = new HashMap<>();
        private final Map<ConnectionKey, ConnectorRoute> connectorRoutes = new HashMap<>();

        // Create one stateful builder for a single deterministic layout pass.
        private Builder(TreeSnapshot tree) {
            this.tree = tree;
        }

        // Run node placement before routing connectors around the finished graph.
        private Result build() {
            placeNodes();
            normalizeNodeRows();
            rebuildConnectorRoutes();
            return new Result(
                    Map.copyOf(nodePositions),
                    Map.copyOf(connectorRoutes),
                    calculateBounds()
            );
        }

        // Lay out straight chains on one row and allocate new rows only around forks and merges.
        private void placeNodes() {
            Map<String, NodeSnapshot> byId = new LinkedHashMap<>();
            tree.skills().forEach(skill -> byId.put(skill.id(), skill));
            Map<String, Integer> depths = new HashMap<>();
            for (NodeSnapshot skill : tree.skills()) {
                dependencyDepth(skill, byId, depths);
            }

            Map<String, List<String>> children = new LinkedHashMap<>();
            tree.skills().forEach(skill -> children.put(skill.id(), new ArrayList<>()));
            for (NodeSnapshot skill : tree.skills()) {
                skill.previous().forEach(parentId -> children.get(parentId).add(skill.id()));
            }

            Map<Integer, List<NodeSnapshot>> levels = new LinkedHashMap<>();
            for (NodeSnapshot skill : tree.skills()) {
                levels.computeIfAbsent(depths.get(skill.id()), ignored -> new ArrayList<>()).add(skill);
            }

            List<Integer> orderedDepths = new ArrayList<>(levels.keySet());
            orderedDepths.sort(Integer::compareTo);
            Map<String, Integer> rows = new HashMap<>();
            Map<Integer, List<Integer>> occupiedRows = new HashMap<>();
            Map<Integer, Integer> rowUsage = new HashMap<>();
            int nextRootRow = 0;
            for (int depth : orderedDepths) {
                for (NodeSnapshot skill : levels.get(depth)) {
                    int preferredRow;
                    if (skill.previous().isEmpty()) {
                        preferredRow = nextRootRow;
                        nextRootRow += 2;
                    } else {
                        preferredRow = preferredRow(skill, children, rows);
                    }
                    int row = nearestFreeRow(
                            preferredRow,
                            occupiedRows.computeIfAbsent(depth, ignored -> new ArrayList<>()),
                            rowUsage
                    );
                    occupiedRows.get(depth).add(row);
                    rowUsage.merge(row, 1, Integer::sum);
                    rows.put(skill.id(), row);
                    nodePositions.put(skill.id(), new NodePosition(
                            depth * NODE_HORIZONTAL_SPACING,
                            row * NODE_VERTICAL_SPACING
                    ));
                }
            }
            moveBlockedLongRangeRootsDown();
        }

        // Keep chains straight, spread fork children symmetrically, and center merges among their parents.
        private static int preferredRow(
                NodeSnapshot skill,
                Map<String, List<String>> children,
                Map<String, Integer> rows
        ) {
            String onlyParent = skill.previous().size() == 1 ? skill.previous().getFirst() : null;
            if (onlyParent != null) {
                List<String> siblings = children.get(onlyParent);
                if (siblings.size() == 1) {
                    return rows.get(onlyParent);
                }
                return rows.get(onlyParent) + balancedBranchOffset(siblings.indexOf(skill.id()), siblings.size());
            }
            double average = skill.previous().stream().mapToInt(rows::get).average().orElse(0.0D);
            return (int) Math.round(average);
        }

        // Return a centered branch offset with equal upper and lower spacing for even splits.
        private static int balancedBranchOffset(int branchIndex, int branchCount) {
            int midpoint = branchCount / 2;
            if (branchCount % 2 == 1) {
                return branchIndex - midpoint;
            }
            return branchIndex < midpoint ? branchIndex - midpoint : branchIndex - midpoint + 1;
        }

        // Move a root below the graph when its long target-side route would otherwise hit another node.
        private void moveBlockedLongRangeRootsDown() {
            int nextY = nodePositions.values().stream().mapToInt(NodePosition::y).max().orElse(0)
                    + 2 * NODE_VERTICAL_SPACING;
            for (NodeSnapshot root : tree.skills()) {
                if (!root.previous().isEmpty()) {
                    continue;
                }
                NodePosition rootPosition = nodePositions.get(root.id());
                List<NodeSnapshot> longRangeChildren = tree.skills().stream()
                        .filter(child -> child.previous().contains(root.id()))
                        .filter(child -> nodePositions.get(child.id()).x() - rootPosition.x() > NODE_HORIZONTAL_SPACING)
                        .toList();
                if (longRangeChildren.isEmpty() || longRangeRoutesAreClear(root, longRangeChildren)) {
                    continue;
                }
                for (int attempt = 0; attempt <= tree.skills().size(); attempt++) {
                    nodePositions.put(root.id(), new NodePosition(rootPosition.x(), nextY));
                    nextY += NODE_VERTICAL_SPACING;
                    if (longRangeRoutesAreClear(root, longRangeChildren)) {
                        break;
                    }
                }
            }
        }

        // Check whether every long root connection can stay horizontal until its target-side turn.
        private boolean longRangeRoutesAreClear(NodeSnapshot root, List<NodeSnapshot> children) {
            NodePosition rootPosition = nodePositions.get(root.id());
            for (NodeSnapshot child : children) {
                ConnectorRoute route = targetAlignedConnectorRoute(rootPosition, nodePositions.get(child.id()));
                if (!connectorRouteIsClear(root.id(), child.id(), route)) {
                    return false;
                }
            }
            return true;
        }

        // Find the closest depth-free row and favor the side used less by the complete graph so far.
        private static int nearestFreeRow(
                int preferredRow,
                List<Integer> occupiedRows,
                Map<Integer, Integer> rowUsage
        ) {
            if (!occupiedRows.contains(preferredRow)) {
                return preferredRow;
            }
            for (int distance = 1; ; distance++) {
                int upperRow = preferredRow - distance;
                int lowerRow = preferredRow + distance;
                boolean upperAvailable = !occupiedRows.contains(upperRow);
                boolean lowerAvailable = !occupiedRows.contains(lowerRow);
                if (upperAvailable && lowerAvailable) {
                    int upperUsage = rowUsage.getOrDefault(upperRow, 0);
                    int lowerUsage = rowUsage.getOrDefault(lowerRow, 0);
                    return upperUsage <= lowerUsage ? upperRow : lowerRow;
                }
                if (upperAvailable) {
                    return upperRow;
                }
                if (lowerAvailable) {
                    return lowerRow;
                }
            }
        }

        // Shift a layout with upper branches down so every local row remains non-negative.
        private void normalizeNodeRows() {
            int minimumY = nodePositions.values().stream().mapToInt(NodePosition::y).min().orElse(0);
            if (minimumY >= 0) {
                return;
            }
            int verticalShift = -minimumY;
            nodePositions.replaceAll((ignored, position) ->
                    new NodePosition(position.x(), position.y() + verticalShift));
        }

        // Calculate a node's dependency depth for automatic left-to-right placement.
        private static int dependencyDepth(
                NodeSnapshot skill,
                Map<String, NodeSnapshot> byId,
                Map<String, Integer> cache
        ) {
            Integer cached = cache.get(skill.id());
            if (cached != null) {
                return cached;
            }
            int depth = 0;
            for (String previousId : skill.previous()) {
                depth = Math.max(depth, dependencyDepth(byId.get(previousId), byId, cache) + 1);
            }
            cache.put(skill.id(), depth);
            return depth;
        }

        // Build collision-free connector routes through column gaps or outside the complete graph.
        private void rebuildConnectorRoutes() {
            if (nodePositions.isEmpty()) {
                return;
            }
            int minimumY = nodePositions.values().stream().mapToInt(NodePosition::y).min().orElse(0);
            int maximumY = nodePositions.values().stream().mapToInt(NodePosition::y).max().orElse(0);
            List<Integer> trackCandidates = connectorTrackCandidates(minimumY, maximumY);

            for (NodeSnapshot child : tree.skills()) {
                NodePosition childPosition = nodePositions.get(child.id());
                for (String parentId : child.previous()) {
                    NodePosition parentPosition = nodePositions.get(parentId);
                    if (parentPosition == null || childPosition == null) {
                        continue;
                    }
                    ConnectorRoute preferredRoute = targetAlignedConnectorRoute(parentPosition, childPosition);
                    ConnectorRoute route = preferredRoute;
                    if (!connectorRouteIsClear(parentId, child.id(), preferredRoute)) {
                        int preferredY = (parentPosition.y() + childPosition.y() + NODE_SIZE) / 2;
                        int trackY = trackCandidates.stream()
                                .min((first, second) -> Integer.compare(
                                        Math.abs(first - preferredY),
                                        Math.abs(second - preferredY)
                                ))
                                .orElse(minimumY - CONNECTION_TRACK_MARGIN);
                        route = longConnectorRoute(parentPosition, childPosition, trackY);
                    }
                    connectorRoutes.put(new ConnectionKey(parentId, child.id()), route);
                }
            }
        }

        // Check every segment of a candidate route against every unrelated node rectangle.
        private boolean connectorRouteIsClear(
                String parentId,
                String childId,
                ConnectorRoute route
        ) {
            List<RoutePoint> points = route.points();
            for (int pointIndex = 1; pointIndex < points.size(); pointIndex++) {
                RoutePoint start = points.get(pointIndex - 1);
                RoutePoint end = points.get(pointIndex);
                int steps = Math.max(Math.abs(end.x() - start.x()), Math.abs(end.y() - start.y()));
                for (int step = 1; step < steps; step++) {
                    int x = start.x() + Math.round((end.x() - start.x()) * step / (float) steps);
                    int y = start.y() + Math.round((end.y() - start.y()) * step / (float) steps);
                    for (Map.Entry<String, NodePosition> entry : nodePositions.entrySet()) {
                        if (entry.getKey().equals(parentId) || entry.getKey().equals(childId)) {
                            continue;
                        }
                        NodePosition obstacle = entry.getValue();
                        if (x >= obstacle.x() - 2
                                && x < obstacle.x() + NODE_SIZE + 2
                                && y >= obstacle.y() - 2
                                && y < obstacle.y() + NODE_SIZE + 2) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        // Return safe horizontal lanes between node rows plus compact fallback lanes outside them.
        private List<Integer> connectorTrackCandidates(int minimumY, int maximumY) {
            List<Integer> nodeRows = nodePositions.values().stream()
                    .map(NodePosition::y)
                    .distinct()
                    .sorted()
                    .toList();
            List<Integer> tracks = new ArrayList<>();
            for (int index = 1; index < nodeRows.size(); index++) {
                int previousBottom = nodeRows.get(index - 1) + NODE_SIZE;
                int nextTop = nodeRows.get(index);
                if (nextTop - previousBottom >= 4) {
                    tracks.add((previousBottom + nextTop) / 2);
                }
            }
            tracks.add(minimumY - CONNECTION_TRACK_MARGIN);
            tracks.add(maximumY + NODE_SIZE + CONNECTION_TRACK_MARGIN);
            return tracks;
        }

        // Route toward the child first, then use one shared target-side vertical junction.
        private static ConnectorRoute targetAlignedConnectorRoute(NodePosition parent, NodePosition child) {
            int parentX = parent.x() + NODE_SIZE;
            int parentY = parent.y() + NODE_SIZE / 2;
            int childX = child.x();
            int childY = child.y() + NODE_SIZE / 2;
            if (parentY == childY) {
                return new ConnectorRoute(List.of(
                        new RoutePoint(parentX, parentY),
                        new RoutePoint(childX, childY)
                ));
            }
            int targetChannelX = childX - CONNECTION_TARGET_OFFSET;
            return new ConnectorRoute(List.of(
                    new RoutePoint(parentX, parentY),
                    new RoutePoint(targetChannelX, parentY),
                    new RoutePoint(targetChannelX, childY),
                    new RoutePoint(childX, childY)
            ));
        }

        // Route a dependency that skips columns along a reserved node-free horizontal track.
        private static ConnectorRoute longConnectorRoute(
                NodePosition parent,
                NodePosition child,
                int trackY
        ) {
            int channelOffset = (NODE_HORIZONTAL_SPACING - NODE_SIZE) / 2;
            int parentX = parent.x() + NODE_SIZE;
            int parentY = parent.y() + NODE_SIZE / 2;
            int childX = child.x();
            int childY = child.y() + NODE_SIZE / 2;
            int firstChannelX = parentX + channelOffset;
            int lastChannelX = childX - CONNECTION_TARGET_OFFSET;
            return new ConnectorRoute(List.of(
                    new RoutePoint(parentX, parentY),
                    new RoutePoint(firstChannelX, parentY),
                    new RoutePoint(firstChannelX, trackY),
                    new RoutePoint(lastChannelX, trackY),
                    new RoutePoint(lastChannelX, childY),
                    new RoutePoint(childX, childY)
            ));
        }

        // Calculate the complete graph-local rectangle used to clamp canvas panning.
        private Bounds calculateBounds() {
            if (nodePositions.isEmpty()) {
                return new Bounds(0, 0, 0, 0);
            }
            int minimumX = nodePositions.values().stream().mapToInt(NodePosition::x).min().orElse(0);
            int minimumY = nodePositions.values().stream().mapToInt(NodePosition::y).min().orElse(0);
            int maximumX = nodePositions.values().stream()
                    .mapToInt(position -> position.x() + NODE_SIZE)
                    .max()
                    .orElse(0);
            int maximumY = nodePositions.values().stream()
                    .mapToInt(position -> position.y() + NODE_SIZE)
                    .max()
                    .orElse(0);
            for (ConnectorRoute route : connectorRoutes.values()) {
                minimumX = Math.min(minimumX, route.points().stream().mapToInt(RoutePoint::x).min().orElse(minimumX));
                minimumY = Math.min(minimumY, route.points().stream().mapToInt(RoutePoint::y).min().orElse(minimumY));
                maximumX = Math.max(maximumX, route.points().stream().mapToInt(RoutePoint::x).max().orElse(maximumX));
                maximumY = Math.max(maximumY, route.points().stream().mapToInt(RoutePoint::y).max().orElse(maximumY));
            }
            return new Bounds(minimumX, minimumY, maximumX, maximumY);
        }
    }

    record Result(
            Map<String, NodePosition> nodePositions,
            Map<ConnectionKey, ConnectorRoute> connectorRoutes,
            Bounds bounds
    ) {
    }

    record NodePosition(int x, int y) {
    }

    record RoutePoint(int x, int y) {
    }

    record ConnectorRoute(List<RoutePoint> points) {
    }

    record ConnectionKey(String parentId, String childId) {
    }

    record Bounds(int minimumX, int minimumY, int maximumX, int maximumY) {
    }
}
