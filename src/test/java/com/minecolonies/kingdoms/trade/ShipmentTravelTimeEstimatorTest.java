package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.caravan.DirectShipmentPath;
import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadRoute;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentTravelTimeEstimatorTest
{
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final TradeSettings SETTINGS = new TradeSettings(8, 4096, 10, 300, 200, 2.0D, 3, 10);

    @Test
    void directPathRetainsBaselineFormula()
    {
        final DirectShipmentPath path = new DirectShipmentPath(OVERWORLD, new Vec3(0, 64, 0), new Vec3(100, 64, 0));
        assertEquals(400L, new ShipmentTravelTimeEstimator().estimate(path, SETTINGS));
    }

    @Test
    void stoneRoadIsFasterThanEquivalentDirectPath()
    {
        final UUID a = UUID.randomUUID(); final UUID b = UUID.randomUUID();
        final RoadNetwork network = new RoadNetwork();
        final RoadRecord road = road("stone", a, b, 0, 130, RoadType.STONE);
        network.put(road);
        final RoadShipmentPath path = new RoadShipmentPath(new RoadRoute(a, b, List.of(a, b), List.of(road.id()), road.length()), network);
        final long roadTicks = new ShipmentTravelTimeEstimator().estimate(path, SETTINGS);
        final long directTicks = new ShipmentTravelTimeEstimator().estimate(
            new DirectShipmentPath(OVERWORLD, Vec3.atCenterOf(new BlockPos(0, 64, 0)), Vec3.atCenterOf(new BlockPos(130, 64, 0))), SETTINGS);
        assertTrue(roadTicks < directTicks);
        assertEquals(400L, roadTicks);
    }

    @Test
    void multiEdgeEffectiveDistanceUsesEveryEdgeMultiplier()
    {
        final UUID a = UUID.randomUUID(); final UUID b = UUID.randomUUID(); final UUID c = UUID.randomUUID();
        final RoadNetwork network = new RoadNetwork();
        final RoadRecord ab = road("ab", a, b, 0, 115, RoadType.DIRT);
        final RoadRecord bc = road("bc", b, c, 115, 245, RoadType.STONE);
        network.put(ab); network.put(bc);
        final RoadShipmentPath path = new RoadShipmentPath(new RoadRoute(a, c, List.of(a, b, c), List.of(ab.id(), bc.id()),
            ab.length() + bc.length()), network);
        assertEquals(200.0D, path.effectiveDistance(), 0.0001D);
        assertEquals(600L, new ShipmentTravelTimeEstimator().estimate(path, SETTINGS));
    }

    private static RoadRecord road(final String key, final UUID first, final UUID second, final int from, final int to, final RoadType type)
    {
        return new RoadRecord(UUID.nameUUIDFromBytes(key.getBytes()), first, second, OVERWORLD, type,
            List.of(new BlockPos(from, 64, 0), new BlockPos(to, 64, 0)), RoadStatus.PLANNED, 1);
    }
}
