package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementRegistry;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNetworkTest
{
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final TerrainSampler FLAT = (x, z) -> new TerrainSample(64, false, true);

    @Test
    void planningIsDeterministicHasValidEndpointsAndNoDuplicates()
    {
        final SettlementRegistry settlements = registry();
        final RoadSettings settings = new RoadSettings(900, 1400, 3, 32, 128, 5000, 10, 100, 1);
        final RoadNetwork first = new RoadNetwork();
        final RoadNetwork second = new RoadNetwork();
        final RoadPlanner planner = new RoadPlanner();
        assertTrue(planner.planMissing(77L, settlements, first, FLAT, settings) > 0);
        planner.planMissing(77L, settlements, second, FLAT, settings);
        final Set<UUID> ids = first.roads().stream().map(RoadRecord::id).collect(Collectors.toSet());
        assertEquals(ids, second.roads().stream().map(RoadRecord::id).collect(Collectors.toSet()));
        assertEquals(first.roads().size(), ids.size());
        first.roads().forEach(road -> {
            assertTrue(settlements.get(road.firstSettlementId()).isPresent());
            assertTrue(settlements.get(road.secondSettlementId()).isPresent());
            assertNotEquals(road.firstSettlementId(), road.secondSettlementId());
            assertTrue(road.polyline().size() >= 2);
        });
        assertEquals(0, planner.planMissing(77L, settlements, first, FLAT, settings));
    }

    @Test
    void terrainPlannerAvoidsExpensiveWaterCorridor()
    {
        final TerrainSampler terrain = (x, z) -> new TerrainSample(64, Math.abs(z) < 20 && x > 50 && x < 270, true);
        final RoadSettings settings = new RoadSettings(1000, 2000, 4, 32, 160, 10000, 1, 1000, 0);
        final List<BlockPos> path = new RoadTerrainPlanner().findPath(new BlockPos(0, 64, 0),
            new BlockPos(320, 64, 0), terrain, settings).orElseThrow();
        assertTrue(path.stream().anyMatch(point -> Math.abs(point.getZ()) >= 32), path.toString());
    }

    @Test
    void shortestRouteUsesMultipleEdgesAndShipmentPathHasExactEnds()
    {
        final SettlementRecord a = settlement("a", 0, 0);
        final SettlementRecord b = settlement("b", 100, 0);
        final SettlementRecord c = settlement("c", 200, 0);
        final RoadNetwork network = new RoadNetwork();
        network.put(road("ab", a, b, List.of(a.gate(), b.gate())));
        network.put(road("bc", b, c, List.of(b.gate(), new BlockPos(150, 70, 20), c.gate())));
        network.put(new RoadRecord(UUID.nameUUIDFromBytes("ac".getBytes()), a.id(), c.id(), OVERWORLD, RoadType.TRAIL,
            List.of(a.gate(), new BlockPos(100, 70, 400), c.gate()), RoadStatus.PLANNED, 1));
        final RoadRoute route = new RoadRoutePlanner().shortest(network, a.id(), c.id()).orElseThrow();
        assertEquals(2, route.roadIds().size());
        final RoadShipmentPath path = new RoadShipmentPath(route, network);
        assertEquals(net.minecraft.world.phys.Vec3.atCenterOf(a.gate()), path.positionAt(0.0));
        assertEquals(net.minecraft.world.phys.Vec3.atCenterOf(c.gate()), path.positionAt(1.0));
        assertTrue(path.projectProgress(path.positionAt(0.5)) > 0.45);
        assertTrue(path.projectProgress(path.localWaypoint(0.5, 30)) > 0.5);
    }

    private static SettlementRegistry registry()
    {
        final SettlementRegistry registry = new SettlementRegistry();
        registry.put(settlement("a", 0, 0)); registry.put(settlement("b", 500, 50));
        registry.put(settlement("c", 950, 500)); registry.put(settlement("d", 100, 850));
        return registry;
    }
    private static SettlementRecord settlement(final String key, final int x, final int z)
    {
        final UUID id = UUID.nameUUIDFromBytes(("settlement-" + key).getBytes());
        return new SettlementRecord(id, "Town " + key, SettlementType.TOWN, OVERWORLD, new BlockPos(x, 64, z), 0,
            new BlockPos(x, 64, z), UUID.nameUUIDFromBytes(("faction-" + key).getBytes()), 20,
            SettlementPhysicalState.PLANNED, new SettlementRegion(OVERWORLD, x / 512, z / 512), null, 0);
    }
    private static RoadRecord road(final String key, final SettlementRecord a, final SettlementRecord b, final List<BlockPos> points)
    {
        return new RoadRecord(UUID.nameUUIDFromBytes(key.getBytes()), a.id(), b.id(), OVERWORLD, RoadType.STONE,
            points, RoadStatus.PLANNED, 1);
    }
}
