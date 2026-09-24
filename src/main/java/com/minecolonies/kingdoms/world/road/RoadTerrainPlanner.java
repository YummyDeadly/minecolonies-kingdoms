package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Optional;

public final class RoadTerrainPlanner
{
    /** A settlement core the corridor should go around: centre and radius in blocks. */
    public record Obstacle(int x, int z, int radius) {}

    /** Per-cell cost of crossing a settlement core; high enough to prefer a long detour, never a hard block. */
    static final double OBSTACLE_COST = 4_000.0D;

    public Optional<List<BlockPos>> findPath(final BlockPos start, final BlockPos end, final TerrainSampler terrain,
        final RoadSettings settings)
    {
        return findPath(start, end, terrain, settings, List.of());
    }

    public Optional<List<BlockPos>> findPath(final BlockPos start, final BlockPos end, final TerrainSampler terrain,
        final RoadSettings settings, final List<Obstacle> obstacles)
    {
        final int grid = settings.gridSize();
        final Node source = new Node(Math.floorDiv(start.getX(), grid), Math.floorDiv(start.getZ(), grid));
        final Node target = new Node(Math.floorDiv(end.getX(), grid), Math.floorDiv(end.getZ(), grid));
        final int margin = Math.max(1, settings.searchMargin() / grid);
        final int minX = Math.min(source.x, target.x) - margin;
        final int maxX = Math.max(source.x, target.x) + margin;
        final int minZ = Math.min(source.z, target.z) - margin;
        final int maxZ = Math.max(source.z, target.z) + margin;
        final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::estimate).thenComparing(e -> e.node.x).thenComparing(e -> e.node.z));
        final Map<Node, Double> costs = new HashMap<>();
        final Map<Node, Node> previous = new HashMap<>();
        final Set<Node> closed = new HashSet<>();
        costs.put(source, 0.0D);
        open.add(new Entry(source, heuristic(source, target)));
        int visited = 0;
        while (!open.isEmpty() && visited++ < settings.maximumPathNodes())
        {
            final Node current = open.remove().node;
            if (!closed.add(current)) continue;
            if (current.equals(target)) return Optional.of(toBlocks(reconstruct(previous, current), terrain, grid, start, end));
            final TerrainSample currentTerrain = terrain.sample(current.x * grid + grid / 2, current.z * grid + grid / 2);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            {
                if (dx == 0 && dz == 0) continue;
                final Node next = new Node(current.x + dx, current.z + dz);
                if (next.x < minX || next.x > maxX || next.z < minZ || next.z > maxZ || closed.contains(next)) continue;
                final TerrainSample sample = terrain.sample(next.x * grid + grid / 2, next.z * grid + grid / 2);
                if (!sample.allowedBiome()) continue;
                final double horizontal = Math.hypot(dx, dz) * grid;
                final int elevation = Math.abs(sample.height() - currentTerrain.height());
                if (!sample.water() && (double) elevation / horizontal > settings.maximumGroundGrade()) continue;
                final double stepCost = horizontal + elevation * settings.slopeCost()
                    + Math.abs(sample.height() - start.getY()) * settings.elevationCost() + (sample.water() ? settings.waterCost() : 0.0D)
                    + (next.equals(source) || next.equals(target) ? 0.0D : obstacleCost(next, grid, obstacles));
                final double candidate = costs.get(current) + stepCost;
                if (candidate < costs.getOrDefault(next, Double.POSITIVE_INFINITY))
                {
                    costs.put(next, candidate);
                    previous.put(next, current);
                    open.add(new Entry(next, candidate + heuristic(next, target) * grid));
                }
            }
        }
        return Optional.empty();
    }

    private static double obstacleCost(final Node node, final int grid, final List<Obstacle> obstacles)
    {
        final int x = node.x * grid + grid / 2;
        final int z = node.z * grid + grid / 2;
        for (final Obstacle obstacle : obstacles)
            if (Math.hypot(x - obstacle.x(), z - obstacle.z()) <= obstacle.radius() + grid * 0.5D) return OBSTACLE_COST;
        return 0.0D;
    }

    private static List<Node> reconstruct(final Map<Node, Node> previous, Node current)
    {
        final List<Node> reversed = new ArrayList<>();
        reversed.add(current);
        while (previous.containsKey(current)) { current = previous.get(current); reversed.add(current); }
        return reversed.reversed();
    }

    private static List<BlockPos> toBlocks(final List<Node> nodes, final TerrainSampler terrain, final int grid,
        final BlockPos start, final BlockPos end)
    {
        final List<BlockPos> result = new ArrayList<>();
        result.add(start.immutable());
        for (int index = 1; index < nodes.size() - 1; index++)
        {
            final int x = nodes.get(index).x * grid + grid / 2;
            final int z = nodes.get(index).z * grid + grid / 2;
            result.add(new BlockPos(x, terrain.sample(x, z).height(), z));
        }
        result.add(end.immutable());
        return result;
    }

    private static double heuristic(final Node left, final Node right) { return Math.hypot(left.x - right.x, left.z - right.z); }
    private record Node(int x, int z) {}
    private record Entry(Node node, double estimate) {}
}
