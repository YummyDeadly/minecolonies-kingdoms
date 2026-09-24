package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.world.road.geometry.RoadGeometry;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPlanner;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoadGeometryV2Test
{
    private static RoadSettings settings()
    {
        return new RoadSettings(1000, 2000, 4, 32, 128, 10000, 5, 100, 1,
            1.0D, 24, 3, 5, 4096, 3, false);
    }

    @Test
    void waterAndRavineReceiveBoundedBridgeGeometry()
    {
        final TerrainSampler water = (x, z) -> new TerrainSample(64, x >= 20 && x <= 28, true);
        final RoadGeometry acrossWater = new RoadGeometryPlanner().plan(new BlockPos(0, 64, 0),
            new BlockPos(64, 64, 0), water, settings()).orElseThrow();
        assertTrue(acrossWater.points().stream().anyMatch(point -> point.kind() == RoadGeometryKind.BRIDGE));
        assertTrue(acrossWater.points().stream().filter(point -> point.kind() == RoadGeometryKind.BRIDGE)
            .allMatch(point -> point.position().getY() == 64));

        final TerrainSampler ravine = (x, z) -> new TerrainSample(x >= 20 && x <= 27 ? 50 : 64, false, true);
        final RoadGeometry acrossRavine = new RoadGeometryPlanner().plan(new BlockPos(0, 64, 0),
            new BlockPos(64, 64, 0), ravine, settings()).orElseThrow();
        assertTrue(acrossRavine.points().stream().anyMatch(point -> point.kind() == RoadGeometryKind.BRIDGE));
    }

    @Test
    void unsafeGradeHasNoDirectPhysicalFallback()
    {
        final TerrainSampler cliff = (x, z) -> new TerrainSample(64 + x * 2, false, true);
        assertTrue(new RoadGeometryPlanner().plan(new BlockPos(0, 64, 0), new BlockPos(64, 192, 0),
            cliff, settings()).isEmpty());
    }

    @Test
    void localTerrainStepIsSpreadOverBothSidesWithinGradeAndBankBounds()
    {
        // A 5-block rise within one coarse cell: the old forward-only clamp lagged 4 blocks behind and failed.
        final TerrainSampler step = (x, z) -> new TerrainSample(x < 30 ? 64 : 69, false, true);
        final RoadGeometry geometry = new RoadGeometryPlanner().plan(new BlockPos(0, 64, 0),
            new BlockPos(64, 69, 0), step, settings()).orElseThrow();
        final var points = geometry.points();
        for (int index = 1; index < points.size(); index++)
            assertTrue(Math.abs(points.get(index).position().getY() - points.get(index - 1).position().getY()) <= 1);
        for (final var point : points)
        {
            final int ground = point.position().getX() < 30 ? 64 : 69;
            assertTrue(Math.abs(point.position().getY() - ground) <= 3, () -> "deviation at " + point.position());
        }
        assertTrue(points.stream().anyMatch(point -> point.kind() == RoadGeometryKind.GRADED));
    }

    @Test
    void exactGeometryKindsAndChunkPortalsRoundTrip()
    {
        final TerrainSampler flat = (x, z) -> new TerrainSample(64, false, true);
        final RoadGeometry geometry = new RoadGeometryPlanner().plan(new BlockPos(0, 64, 0),
            new BlockPos(40, 64, 0), flat, settings()).orElseThrow();
        final RoadGeometry loaded = RoadGeometry.load(geometry.save());
        assertEquals(geometry.points(), loaded.points());
        assertFalse(loaded.portals(new ChunkPos(0, 0)).isEmpty());
        assertFalse(loaded.intersecting(new ChunkPos(1, 0), 4).isEmpty());
        assertTrue(loaded.points().stream().allMatch(point -> point.kind() == RoadGeometryKind.GROUND));
    }
}
