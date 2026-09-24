package com.minecolonies.kingdoms.citizen;

import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetSegment;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walkable graph over a settlement's persisted local streets (not a second road system). Nodes are street centre
 * points; edges join consecutive points, touching points of different segments (street joins), and every street
 * point on the plaza square. Routes are bounded BFS and return sparse waypoints; Minecraft pathfinding handles the
 * few blocks between waypoints. The router is rebuildable from the layout and is never persisted.
 */
public final class StreetRouter
{
    public static final int MAX_NODES = 4096;
    static final int WAYPOINT_SPACING = 5;
    static final int SNAP_DISTANCE = 24;
    private final List<BlockPos> nodes = new ArrayList<>();
    private final List<List<Integer>> edges = new ArrayList<>();
    private final Map<Long, Integer> byColumn = new HashMap<>();
    private final Map<Long, List<BlockPos>> routeCache = new LinkedHashMap<>(64, 0.75F, true)
    {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, List<BlockPos>> eldest) { return size() > 256; }
    };

    public StreetRouter(final SettlementLayoutPlan layout)
    {
        BlockPos plaza = null;
        int plazaRadius = 0;
        for (final SettlementStreetSegment segment : layout.streets().segments())
        {
            if (SettlementLayoutPlanner.PLAZA_PURPOSE.equals(segment.purpose()))
            {
                plaza = segment.points().getFirst();
                plazaRadius = segment.width();
                continue;
            }
            Integer previous = null;
            for (final BlockPos point : segment.points())
            {
                final Integer node = node(point);
                if (node == null) return;
                if (previous != null && !previous.equals(node)) link(previous, node);
                previous = node;
            }
        }
        for (int index = 0; index < nodes.size(); index++)
        {
            final BlockPos point = nodes.get(index);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            {
                final Integer other = byColumn.get(BlockPos.asLong(point.getX() + dx, 0, point.getZ() + dz));
                if (other != null && other != index) link(index, other);
            }
        }
        if (plaza != null)
        {
            final Integer center = node(plaza);
            if (center != null)
                for (int index = 0; index < nodes.size(); index++)
                {
                    final BlockPos point = nodes.get(index);
                    if (index != center && Math.abs(point.getX() - plaza.getX()) <= plazaRadius + 1
                        && Math.abs(point.getZ() - plaza.getZ()) <= plazaRadius + 1) link(center, index);
                }
        }
    }

    public int size() { return nodes.size(); }

    /**
     * Sparse waypoints from {@code from} to {@code to} along the street network, ending exactly at {@code to}. Falls
     * back to a direct single waypoint when either end is too far from any street.
     */
    public List<BlockPos> route(final BlockPos from, final BlockPos to)
    {
        final int start = nearest(from);
        final int goal = nearest(to);
        if (start < 0 || goal < 0 || start == goal) return List.of(to.immutable());
        final long key = ((long) start << 32) | (goal & 0xffffffffL);
        final List<BlockPos> cached = routeCache.get(key);
        if (cached != null) return withTarget(cached, to);
        final int[] previous = new int[nodes.size()];
        java.util.Arrays.fill(previous, -1);
        previous[start] = start;
        final ArrayDeque<Integer> open = new ArrayDeque<>();
        open.add(start);
        while (!open.isEmpty() && previous[goal] < 0)
        {
            final int current = open.removeFirst();
            for (final int next : edges.get(current))
            {
                if (previous[next] >= 0) continue;
                previous[next] = current;
                open.addLast(next);
            }
        }
        if (previous[goal] < 0) return List.of(to.immutable());
        final List<BlockPos> path = new ArrayList<>();
        for (int node = goal; node != start; node = previous[node]) path.add(nodes.get(node));
        path.add(nodes.get(start));
        Collections.reverse(path);
        final List<BlockPos> sparse = new ArrayList<>();
        for (int index = WAYPOINT_SPACING; index < path.size(); index += WAYPOINT_SPACING) sparse.add(path.get(index));
        if (sparse.isEmpty() || !sparse.getLast().equals(path.getLast())) sparse.add(path.getLast());
        routeCache.put(key, List.copyOf(sparse));
        return withTarget(sparse, to);
    }

    private static List<BlockPos> withTarget(final List<BlockPos> sparse, final BlockPos to)
    {
        final List<BlockPos> result = new ArrayList<>(sparse);
        if (result.isEmpty() || !result.getLast().equals(to)) result.add(to.immutable());
        return List.copyOf(result);
    }

    private int nearest(final BlockPos position)
    {
        int best = -1;
        long bestDistance = (long) SNAP_DISTANCE * SNAP_DISTANCE + 1;
        for (int index = 0; index < nodes.size(); index++)
        {
            final BlockPos node = nodes.get(index);
            final long dx = node.getX() - position.getX();
            final long dz = node.getZ() - position.getZ();
            final long distance = dx * dx + dz * dz;
            if (distance < bestDistance) { bestDistance = distance; best = index; }
        }
        return best;
    }

    private Integer node(final BlockPos point)
    {
        final long column = BlockPos.asLong(point.getX(), 0, point.getZ());
        final Integer existing = byColumn.get(column);
        if (existing != null) return existing;
        if (nodes.size() >= MAX_NODES) return null;
        nodes.add(point.immutable());
        edges.add(new ArrayList<>(4));
        byColumn.put(column, nodes.size() - 1);
        return nodes.size() - 1;
    }

    private void link(final int a, final int b)
    {
        if (!edges.get(a).contains(b)) edges.get(a).add(b);
        if (!edges.get(b).contains(a)) edges.get(b).add(a);
    }
}
