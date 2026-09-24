package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.world.settlement.SettlementPlanner;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegistry;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RoadPlanner
{
    private final com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPlanner geometryPlanner =
        new com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPlanner();

    public int planMissing(final long worldSeed, final SettlementRegistry settlements, final RoadNetwork network,
        final TerrainSampler terrain, final RoadSettings settings)
    {
        final List<SettlementRecord> records = settlements.records().stream()
            .sorted(Comparator.comparing(SettlementRecord::id)).toList();
        final Map<UUID, Integer> degree = new HashMap<>();
        network.roads().forEach(road -> { degree.merge(road.firstSettlementId(), 1, Integer::sum); degree.merge(road.secondSettlementId(), 1, Integer::sum); });
        final List<Edge> edges = new ArrayList<>();
        for (int left = 0; left < records.size(); left++) for (int right = left + 1; right < records.size(); right++)
        {
            final SettlementRecord a = records.get(left);
            final SettlementRecord b = records.get(right);
            if (!a.dimension().equals(b.dimension())) continue;
            final double distance = horizontalDistance(a, b);
            if (distance <= settings.hardMaximumDistance()) edges.add(new Edge(a, b, distance));
        }
        edges.sort(Comparator.comparingDouble(Edge::distance).thenComparing(e -> e.a.id()).thenComparing(e -> e.b.id()));
        final DisjointSet components = new DisjointSet(records);
        network.roads().forEach(road -> components.union(road.firstSettlementId(), road.secondSettlementId()));
        int created = 0;
        for (final Edge edge : edges)
        {
            if (components.connected(edge.a.id(), edge.b.id())) continue;
            if (degree.getOrDefault(edge.a.id(), 0) >= settings.maximumDegree()
                || degree.getOrDefault(edge.b.id(), 0) >= settings.maximumDegree()) continue;
            if (add(worldSeed, edge, network, terrain, settings, obstacles(records, edge.a.dimension())))
            {
                created++; components.union(edge.a.id(), edge.b.id());
                degree.merge(edge.a.id(), 1, Integer::sum); degree.merge(edge.b.id(), 1, Integer::sum);
            }
        }
        for (final Edge edge : edges)
        {
            if (edge.distance > settings.softMaximumDistance()) continue;
            if (degree.getOrDefault(edge.a.id(), 0) >= Math.min(settings.maximumDegree(), edge.a.type().desiredConnections())
                || degree.getOrDefault(edge.b.id(), 0) >= Math.min(settings.maximumDegree(), edge.b.type().desiredConnections())) continue;
            if (add(worldSeed, edge, network, terrain, settings, obstacles(records, edge.a.dimension())))
            {
                created++; degree.merge(edge.a.id(), 1, Integer::sum); degree.merge(edge.b.id(), 1, Integer::sum);
            }
        }
        return created;
    }

    /** Length of the straight road segment leaving a gate; matches the settlement gate-approach reservation. */
    public static final int GATE_APPROACH = 32;

    /** Road geometry between two settlements: straight out of each gate, then around settlement cores. */
    public java.util.Optional<com.minecolonies.kingdoms.world.road.geometry.RoadGeometry> geometry(final SettlementRecord a,
        final SettlementRecord b, final List<RoadTerrainPlanner.Obstacle> obstacles, final TerrainSampler terrain,
        final RoadSettings settings)
    {
        return geometryPlanner.plan(a.gate(), approach(a, terrain), approach(b, terrain), b.gate(), obstacles, terrain, settings);
    }

    public static List<RoadTerrainPlanner.Obstacle> obstacles(final java.util.Collection<SettlementRecord> settlements,
        final net.minecraft.resources.ResourceLocation dimension)
    {
        return settlements.stream().filter(value -> value.dimension().equals(dimension))
            .sorted(Comparator.comparing(SettlementRecord::id))
            .map(value -> new RoadTerrainPlanner.Obstacle(value.anchor().getX(), value.anchor().getZ(),
                value.type().footprintRadius() + 8)).toList();
    }

    static net.minecraft.core.BlockPos approach(final SettlementRecord settlement, final TerrainSampler terrain)
    {
        final var gate = settlement.gate();
        final var anchor = settlement.anchor();
        final boolean alongX = Math.abs(gate.getX() - anchor.getX()) >= Math.abs(gate.getZ() - anchor.getZ());
        final int direction = alongX ? Integer.signum(gate.getX() - anchor.getX()) : Integer.signum(gate.getZ() - anchor.getZ());
        final int outward = direction == 0 ? 1 : direction;
        final int x = alongX ? gate.getX() + outward * GATE_APPROACH : gate.getX();
        final int z = alongX ? gate.getZ() : gate.getZ() + outward * GATE_APPROACH;
        return new net.minecraft.core.BlockPos(x, terrain.sample(x, z).height(), z);
    }

    private boolean add(final long worldSeed, final Edge edge, final RoadNetwork network, final TerrainSampler terrain,
        final RoadSettings settings, final List<RoadTerrainPlanner.Obstacle> obstacles)
    {
        final UUID first = edge.a.id().compareTo(edge.b.id()) <= 0 ? edge.a.id() : edge.b.id();
        final UUID second = first.equals(edge.a.id()) ? edge.b.id() : edge.a.id();
        final UUID id = SettlementPlanner.stableUuid("road", worldSeed, first + ":" + second);
        if (network.get(id).isPresent()) return false;
        final RoadType type = chooseType(edge.a.type(), edge.b.type());
        final var geometry = geometry(edge.a, edge.b, obstacles, terrain, settings);
        return network.put(geometry.<RoadRecord>map(value -> new RoadRecord(id, first, second, edge.a.dimension(), type,
            value, RoadStatus.PLANNED, 2)).orElseGet(() -> RoadRecord.unrouteable(id, first, second,
            edge.a.dimension(), type, edge.a.gate(), edge.b.gate(), 2)));
    }

    private static RoadType chooseType(final SettlementType left, final SettlementType right)
    {
        if (left == SettlementType.CASTLE || right == SettlementType.CASTLE) return RoadType.ROYAL;
        if (left == SettlementType.TRADING_TOWN || right == SettlementType.TRADING_TOWN) return RoadType.STONE;
        if (left == SettlementType.TOWN || right == SettlementType.TOWN) return RoadType.DIRT;
        return RoadType.TRAIL;
    }

    private static double horizontalDistance(final SettlementRecord left, final SettlementRecord right)
    {
        return Math.hypot(left.gate().getX() - right.gate().getX(), left.gate().getZ() - right.gate().getZ());
    }

    private record Edge(SettlementRecord a, SettlementRecord b, double distance) {}

    private static final class DisjointSet
    {
        private final Map<UUID, UUID> parent = new HashMap<>();
        DisjointSet(final List<SettlementRecord> records) { records.forEach(record -> parent.put(record.id(), record.id())); }
        private UUID find(final UUID id) { final UUID p = parent.get(id); if (p == null || p.equals(id)) return id; final UUID root = find(p); parent.put(id, root); return root; }
        boolean connected(final UUID a, final UUID b) { return find(a).equals(find(b)); }
        void union(final UUID a, final UUID b) { final UUID ra = find(a); final UUID rb = find(b); if (!ra.equals(rb)) parent.put(ra.compareTo(rb) < 0 ? rb : ra, ra.compareTo(rb) < 0 ? ra : rb); }
    }
}
