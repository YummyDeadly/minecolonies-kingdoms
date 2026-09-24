package com.minecolonies.kingdoms.world.settlement.terrain;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainShapingPlannerTest
{
    private static final StructureFootprint FOOTPRINT = new StructureFootprint(-3, -3, 3, 3);
    private final TerrainShapingPlanner planner = new TerrainShapingPlanner();

    @Test
    void plansCutAndFillWithoutFloatingCorners()
    {
        final TerrainSampler hill = (x, z) -> land(64 + Integer.signum(x));
        final var result = planner.plan(FOOTPRINT, hill, TerrainShapingSettings.defaults());
        assertTrue(result.accepted(), result.rejection().name());
        final BuildingPad pad = result.acceptedPlan().orElseThrow().pad();
        assertTrue(pad.cutVolume() > 0);
        assertTrue(pad.fillVolume() > 0);
        assertTrue(pad.maximumFillDepth() <= TerrainShapingSettings.defaults().maximumFillDepth());
        assertEquals(TerrainShapingMode.CUT_AND_FILL, pad.mode());
    }

    @Test
    void plansPureCutAndPureFill()
    {
        final var cut = planner.plan(FOOTPRINT, (x, z) -> land(x == 0 && z == 0 ? 66 : 64),
            new TerrainShapingSettings(3, 0, 4, 5, 100, 1));
        assertTrue(cut.accepted()); assertEquals(TerrainShapingMode.CUT, cut.plan().pad().mode());
        final var fill = planner.plan(FOOTPRINT, (x, z) -> land(x == 0 && z == 0 ? 62 : 64),
            new TerrainShapingSettings(0, 3, 4, 5, 100, 1));
        assertTrue(fill.accepted()); assertEquals(TerrainShapingMode.FILL, fill.plan().pad().mode());
    }

    @Test
    void enforcesDepthAndVolumeLimits()
    {
        final var depth = planner.plan(FOOTPRINT, (x, z) -> land(x < 0 ? 58 : 70),
            TerrainShapingSettings.defaults());
        assertFalse(depth.accepted());
        final var volume = planner.plan(FOOTPRINT, (x, z) -> land(x < 0 ? 63 : 65),
            new TerrainShapingSettings(4, 4, 8, 5, 4, 1));
        assertEquals(TerrainShapingRejection.EXCESSIVE_TERRAIN_VOLUME, volume.rejection());
    }

    @Test
    void planIsDeterministicAndRoundTrips()
    {
        final TerrainSampler terrain = (x, z) -> land(64 + Math.floorMod(x + z, 3));
        final TerrainShapingPlan first = planner.plan(FOOTPRINT, terrain, TerrainShapingSettings.defaults()).plan();
        final TerrainShapingPlan second = planner.plan(FOOTPRINT, terrain, TerrainShapingSettings.defaults()).plan();
        assertEquals(first, second);
        assertEquals(first, TerrainShapingPlan.load(first.save()));
    }

    private static TerrainSample land(final int height) { return new TerrainSample(height, false, true); }
}
