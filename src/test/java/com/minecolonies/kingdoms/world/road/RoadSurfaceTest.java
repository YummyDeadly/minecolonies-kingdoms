package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.world.road.geometry.RoadGeometry;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPlanner;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPoint;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoadSurfaceTest
{
    private static RoadRecord road(final RoadType type, final List<RoadGeometryPoint> points)
    {
        return new RoadRecord(UUID.nameUUIDFromBytes("surface".getBytes()), UUID.randomUUID(), UUID.randomUUID(),
            ResourceLocation.withDefaultNamespace("overworld"), type, new RoadGeometry(points), RoadStatus.PLANNED, 2);
    }

    @Test
    void diagonalRoadIsASolidBandWithoutCheckerboardHoles()
    {
        // Client acceptance: diagonal stretches were paved only on every other cell (lateral offsets ran along the
        // anti-diagonal), which read as a checkerboard.
        final List<RoadGeometryPoint> points = new ArrayList<>();
        for (int i = -4; i <= 24; i++) points.add(new RoadGeometryPoint(new BlockPos(i, 64, i), RoadGeometryKind.GROUND));
        for (final RoadType type : RoadType.values())
        {
            final var cells = RoadChunkGenerator.coverage(new ChunkPos(0, 0), road(type, points));
            for (int i = 1; i < 15; i++)
            {
                assertTrue(cells.containsKey(BlockPos.asLong(i, 0, i)), type + " centre");
                assertTrue(cells.containsKey(BlockPos.asLong(i + 1, 0, i)), type + " diagonal neighbour");
                assertTrue(cells.containsKey(BlockPos.asLong(i, 0, i + 1)), type + " diagonal neighbour");
            }
            final double limit = type.width() + 0.5D;
            for (final var cell : cells.values())
            {
                double nearest = Double.MAX_VALUE;
                for (final var point : points)
                    nearest = Math.min(nearest, Math.hypot(point.position().getX() - cell.x(), point.position().getZ() - cell.z()));
                assertTrue(nearest <= limit + 1.0E-9, type + " band too wide at " + cell);
            }
        }
    }

    @Test
    void roadsAreNarrowerThanBeforeAndEveryColumnTakesItsNearestCentreHeight()
    {
        assertEquals(1, RoadType.TRAIL.width());
        assertEquals(1, RoadType.DIRT.width());
        assertEquals(2, RoadType.STONE.width());
        assertEquals(2, RoadType.ROYAL.width());
        final List<RoadGeometryPoint> points = new ArrayList<>();
        for (int x = 0; x < 16; x++) points.add(new RoadGeometryPoint(new BlockPos(x, x < 8 ? 64 : 65, 8), RoadGeometryKind.GROUND));
        final var cells = RoadChunkGenerator.coverage(new ChunkPos(0, 0), road(RoadType.ROYAL, points));
        for (int z = 6; z <= 10; z++)
        {
            assertEquals(64, cells.get(BlockPos.asLong(3, 0, z)).walkingY());
            assertEquals(65, cells.get(BlockPos.asLong(12, 0, z)).walkingY());
        }
        assertFalse(cells.containsKey(BlockPos.asLong(3, 0, 5)), "5-wide royal road");
        assertTrue(cells.get(BlockPos.asLong(7, 0, 8)).ramp(), "the lower row of a step gets a half-slab ramp");
        assertFalse(cells.get(BlockPos.asLong(7, 0, 10)).ramp(), "curbs stay full blocks");
    }

    @Test
    void roadLeavesThroughTheGateApproachAndGoesAroundTheTownInsteadOfThroughIt()
    {
        // Client acceptance: a west-facing gate with the destination to the east sent the royal road straight
        // through the village plaza and pond.
        final var flat = (com.minecolonies.kingdoms.world.settlement.TerrainSampler) (x, z) -> new TerrainSample(64, false, true);
        final var settings = new RoadSettings(2000, 3000, 4, 16, 256, 20000, 5, 100, 1, 1.0D, 24, 3, 5, 8192, 3, false);
        final BlockPos gate = new BlockPos(-28, 64, 0);
        final BlockPos approach = new BlockPos(-60, 64, 0);
        final BlockPos farGate = new BlockPos(572, 64, 0);
        final BlockPos farApproach = new BlockPos(540, 64, 0);
        final RoadGeometry geometry = new RoadGeometryPlanner().plan(gate, approach, farApproach, farGate,
            List.of(new RoadTerrainPlanner.Obstacle(0, 0, 36), new RoadTerrainPlanner.Obstacle(600, 0, 36)), flat, settings)
            .orElseThrow();
        final var points = geometry.points();
        assertEquals(gate, points.getFirst().position());
        assertEquals(farGate, points.getLast().position());
        int approachIndex = -1;
        for (int index = 0; index < points.size() && approachIndex < 0; index++)
            if (points.get(index).position().getX() == approach.getX() && points.get(index).position().getZ() == 0) approachIndex = index;
        assertTrue(approachIndex > 0, "the road first runs straight out of the gate to its approach point");
        for (int index = approachIndex; index < points.size(); index++)
        {
            final BlockPos point = points.get(index).position();
            if (point.getX() > 500) break;
            assertTrue(Math.hypot(point.getX(), point.getZ()) > 30, () -> "road crosses the town core at " + point);
        }
    }

    @Test
    void smoothingKeepsEndpointsAndRoundsCorners()
    {
        final List<BlockPos> corner = List.of(new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), new BlockPos(64, 64, 64));
        final List<BlockPos> smoothed = RoadGeometryPlanner.smooth(corner, 2);
        assertEquals(corner.getFirst(), smoothed.getFirst());
        assertEquals(corner.getLast(), smoothed.getLast());
        assertFalse(smoothed.contains(new BlockPos(64, 64, 0)), "the sharp corner is cut");
        assertTrue(smoothed.size() > corner.size());
        final var flat = (com.minecolonies.kingdoms.world.settlement.TerrainSampler) (x, z) -> new TerrainSample(64, false, true);
        final var settings = new RoadSettings(1000, 2000, 4, 16, 128, 10000, 5, 100, 1, 1.0D, 24, 3, 5, 4096, 3, false);
        final RoadGeometry geometry = new RoadGeometryPlanner().plan(new BlockPos(0, 64, 0), new BlockPos(64, 64, 40), flat, settings)
            .orElseThrow();
        for (int index = 1; index < geometry.points().size(); index++)
        {
            final BlockPos a = geometry.points().get(index - 1).position();
            final BlockPos b = geometry.points().get(index).position();
            assertTrue(Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1, "8-connected centre line");
        }
    }
}
