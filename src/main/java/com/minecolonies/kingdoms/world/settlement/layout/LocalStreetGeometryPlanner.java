package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** A hard-bounded, deterministic local path search. It never reads blocks or loads chunks. */
public final class LocalStreetGeometryPlanner
{
    private static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    public Optional<SettlementStreetSegment> plan(final UUID streetId, final BlockPos entrance,
        final Direction entranceFacing, final StructureFootprint buildingFootprint,
        final Collection<BlockPos> joins, final Collection<SettlementLot> existingLots,
        final TerrainSampler terrain, final LocalStreetSettings settings)
    {
        if (joins.isEmpty()) return Optional.empty();
        // MineColonies doors are often recessed (porches, arcades): the entrance can lie inside the footprint. The
        // street starts at the first column outside the footprint along the door's facing, so it never paves the
        // building's own floor and is not boxed in by its own blocked footprint.
        BlockPos start = entrance.atY(entrance.getY());
        for (int step = 0; step < 64 && buildingFootprint != null
            && buildingFootprint.contains(start.getX(), start.getZ()); step++)
            start = start.relative(entranceFacing);
        if (buildingFootprint != null && buildingFootprint.contains(start.getX(), start.getZ())) return Optional.empty();
        final BlockPos frontage = start.relative(entranceFacing).atY(start.getY());
        if (joins.stream().anyMatch(join -> join.equals(frontage)))
            return Optional.of(SettlementStreetSegment.withGeometry(streetId, List.of(
                new LocalStreetPoint(start, LocalStreetKind.GROUND),
                new LocalStreetPoint(frontage, LocalStreetKind.GROUND)), 1, "building-frontage"));
        final Optional<SettlementStreetSegment> connection = connection(streetId, frontage, joins,
            existingLots, buildingFootprint, terrain, settings, 1, "building-frontage");
        if (connection.isEmpty()) return Optional.empty();
        final List<LocalStreetPoint> geometry = new ArrayList<>(connection.orElseThrow().geometry().size() + 1);
        geometry.add(new LocalStreetPoint(start, LocalStreetKind.GROUND));
        geometry.addAll(connection.orElseThrow().geometry());
        return Optional.of(SettlementStreetSegment.withGeometry(streetId, geometry, 1, "building-frontage"));
    }

    public Optional<SettlementStreetSegment> connection(final UUID streetId, final BlockPos start,
        final Collection<BlockPos> joins, final Collection<SettlementLot> existingLots,
        final StructureFootprint blockedFootprint, final TerrainSampler terrain,
        final LocalStreetSettings settings, final int width, final String purpose)
    {
        if (joins.isEmpty()) return Optional.empty();
        final List<BlockPos> orderedJoins = joins.stream()
            .sorted(Comparator.comparingLong((BlockPos value) -> distanceSquared(start, value))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY))
            .limit(settings.joinsConsidered()).toList();
        Result best = null;
        for (final BlockPos join : orderedJoins)
        {
            final Optional<Result> route = search(start, join, blockedFootprint, existingLots, terrain, settings);
            if (route.isEmpty()) continue;
            final Result value = route.orElseThrow();
            if (best == null || Result.ORDER.compare(value, best) < 0) best = value;
        }
        if (best == null) return Optional.empty();
        if (best.points().size() < 2) return Optional.empty();
        return Optional.of(SettlementStreetSegment.withGeometry(streetId, best.points(), width, purpose));
    }

    private static Optional<Result> search(final BlockPos start, final BlockPos goal,
        final StructureFootprint buildingFootprint, final Collection<SettlementLot> lots,
        final TerrainSampler terrain, final LocalStreetSettings settings)
    {
        final int padding = settings.searchPadding();
        final int minX = Math.min(start.getX(), goal.getX()) - padding;
        final int maxX = Math.max(start.getX(), goal.getX()) + padding;
        final int minZ = Math.min(start.getZ(), goal.getZ()) - padding;
        final int maxZ = Math.max(start.getZ(), goal.getZ()) + padding;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > settings.maximumSearchNodes() * 4L)
            return Optional.empty();

        final PriorityQueue<Node> open = new PriorityQueue<>(Node.ORDER);
        final Map<StateKey, Integer> bestCosts = new HashMap<>();
        final Set<Long> blocked = blocked(lots, buildingFootprint, minX, maxX, minZ, maxZ);
        final Node first = new Node(start.getX(), start.getZ(), start.getY(), 0, 0, 0,
            heuristic(start.getX(), start.getZ(), goal), null, LocalStreetKind.GROUND, -1);
        open.add(first);
        bestCosts.put(first.key(), 0);
        int visited = 0;
        while (!open.isEmpty() && visited++ < settings.maximumSearchNodes())
        {
            final Node current = open.poll();
            if (current.cost() != bestCosts.getOrDefault(current.key(), Integer.MAX_VALUE)) continue;
            if (current.x() == goal.getX() && current.z() == goal.getZ())
                return Optional.of(new Result(reconstruct(current), current.cost()));
            if (current.steps() >= settings.maximumStreetLength()) continue;
            for (int directionIndex = 0; directionIndex < DIRECTIONS.length; directionIndex++)
            {
                final Direction direction = DIRECTIONS[directionIndex];
                final int x = current.x() + direction.getStepX();
                final int z = current.z() + direction.getStepZ();
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                final boolean isGoal = x == goal.getX() && z == goal.getZ();
                if (!isGoal && blocked.contains(BlockPos.asLong(x, 0, z))) continue;
                final TerrainSample sample = terrain.sample(x, z);
                if (!sample.known() || !sample.allowedBiome()) continue;
                final int waterRun = sample.water() ? current.waterRun() + 1 : 0;
                if (waterRun > settings.maximumBridgeSpan()) continue;
                final int desired = isGoal ? goal.getY() : sample.height();
                final int y = sample.water() ? current.y() : clamp(desired, current.y() - 1, current.y() + 1);
                if (Math.abs(y - current.y()) > 1 || Math.abs(y - desired) > settings.maximumTerrainAdjustment()) continue;
                final LocalStreetKind kind = sample.water() ? LocalStreetKind.BRIDGE
                    : y != current.y() ? LocalStreetKind.STAIRS
                    : y != sample.height() ? LocalStreetKind.GRADED : LocalStreetKind.GROUND;
                // A turn penalty keeps streets in straight runs instead of 1-block zig-zag staircases.
                final int turn = current.direction() >= 0 && current.direction() != directionIndex ? TURN_PENALTY : 0;
                final int moveCost = 10 + turn + Math.abs(y - sample.height()) * 6
                    + (kind == LocalStreetKind.STAIRS ? 4 : 0) + (kind == LocalStreetKind.BRIDGE ? 20 : 0);
                final int nextCost = current.cost() + moveCost;
                final Node next = new Node(x, z, y, waterRun, current.steps() + 1, nextCost,
                    nextCost + heuristic(x, z, goal), current, kind, directionIndex);
                if (nextCost >= bestCosts.getOrDefault(next.key(), Integer.MAX_VALUE)) continue;
                bestCosts.put(next.key(), nextCost);
                open.add(next);
            }
        }
        return Optional.empty();
    }

    private static Set<Long> blocked(final Collection<SettlementLot> lots,
        final StructureFootprint current, final int minX, final int maxX, final int minZ, final int maxZ)
    {
        final Set<Long> result = new HashSet<>();
        for (final SettlementLot lot : lots)
        {
            final StructureFootprint footprint = lot.footprint().expand(1);
            for (int x = Math.max(minX, footprint.minX()); x <= Math.min(maxX, footprint.maxX()); x++)
                for (int z = Math.max(minZ, footprint.minZ()); z <= Math.min(maxZ, footprint.maxZ()); z++)
                    result.add(BlockPos.asLong(x, 0, z));
        }
        if (current != null)
            for (int x = Math.max(minX, current.minX()); x <= Math.min(maxX, current.maxX()); x++)
                for (int z = Math.max(minZ, current.minZ()); z <= Math.min(maxZ, current.maxZ()); z++)
                    result.add(BlockPos.asLong(x, 0, z));
        return result;
    }

    private static List<LocalStreetPoint> reconstruct(final Node goal)
    {
        final List<LocalStreetPoint> reversed = new ArrayList<>();
        for (Node node = goal; node != null; node = node.previous())
            reversed.add(new LocalStreetPoint(new BlockPos(node.x(), node.y(), node.z()), node.kind()));
        return reversed.reversed().stream().toList();
    }

    private static int clamp(final int value, final int min, final int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static int heuristic(final int x, final int z, final BlockPos goal)
    {
        return (Math.abs(goal.getX() - x) + Math.abs(goal.getZ() - z)) * 10;
    }

    private static long distanceSquared(final BlockPos a, final BlockPos b)
    {
        final long dx = a.getX() - b.getX();
        final long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static final int TURN_PENALTY = 8;

    private record StateKey(int x, int z, int y, int waterRun, int direction) {}

    private record Node(int x, int z, int y, int waterRun, int steps, int cost, int priority,
        Node previous, LocalStreetKind kind, int direction)
    {
        private static final Comparator<Node> ORDER = Comparator.comparingInt(Node::priority)
            .thenComparingInt(Node::cost).thenComparingInt(Node::steps)
            .thenComparingInt(Node::x).thenComparingInt(Node::z).thenComparingInt(Node::y)
            .thenComparingInt(Node::waterRun).thenComparingInt(Node::direction);
        private StateKey key() { return new StateKey(x, z, y, waterRun, direction); }
    }

    private record Result(List<LocalStreetPoint> points, int cost)
    {
        private static final Comparator<Result> ORDER = Comparator.comparingInt(Result::cost)
            .thenComparingInt(value -> value.points().size())
            .thenComparingInt(value -> value.points().getLast().position().getX())
            .thenComparingInt(value -> value.points().getLast().position().getZ());
    }
}
