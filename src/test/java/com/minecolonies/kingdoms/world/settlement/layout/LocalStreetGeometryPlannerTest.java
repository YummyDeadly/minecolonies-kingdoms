package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalStreetGeometryPlannerTest
{
    private static final StructureFootprint BUILDING = new StructureFootprint(-3, -3, 3, 3);

    @Test
    void boundedHillUsesStairsAndNeverExceedsOneBlockGrade()
    {
        final TerrainSampler hill = (x, z) -> new TerrainSample(64 + Math.max(0, z - 5) / 3, false, true);
        final var street = new LocalStreetGeometryPlanner().plan(UUID.randomUUID(), new BlockPos(0, 64, 4),
            Direction.SOUTH, BUILDING, List.of(new BlockPos(0, 68, 18)), List.of(), hill,
            LocalStreetSettings.defaults()).orElseThrow();
        assertTrue(street.geometry().stream().anyMatch(value -> value.kind() == LocalStreetKind.STAIRS));
        for (int index = 1; index < street.points().size(); index++)
            assertTrue(Math.abs(street.points().get(index).getY() - street.points().get(index - 1).getY()) <= 1);
    }

    @Test
    void boundedWaterSpanUsesBridgeAndLongSpanIsRejected()
    {
        final TerrainSampler river = (x, z) -> new TerrainSample(64, z >= 8 && z <= 10, true);
        final var accepted = new LocalStreetGeometryPlanner().plan(UUID.randomUUID(), new BlockPos(0, 64, 4),
            Direction.SOUTH, BUILDING, List.of(new BlockPos(0, 64, 15)), List.of(), river,
            LocalStreetSettings.defaults()).orElseThrow();
        assertTrue(accepted.geometry().stream().anyMatch(value -> value.kind() == LocalStreetKind.BRIDGE));
        final TerrainSampler lake = (x, z) -> new TerrainSample(64, z >= 7 && z <= 20, true);
        assertTrue(new LocalStreetGeometryPlanner().plan(UUID.randomUUID(), new BlockPos(0, 64, 4),
            Direction.SOUTH, BUILDING, List.of(new BlockPos(0, 64, 24)), List.of(), lake,
            new LocalStreetSettings(2, 512, 2, 3, 64, 1)).isEmpty());
    }

    @Test
    void diagonalConnectionIsBuiltFromStraightRunsNotAZigZagStaircase()
    {
        final TerrainSampler flat = (x, z) -> new TerrainSample(64, false, true);
        final var street = new LocalStreetGeometryPlanner().connection(UUID.randomUUID(), new BlockPos(0, 64, 0),
            List.of(new BlockPos(20, 64, 20)), List.of(), null, flat, LocalStreetSettings.defaults(), 1, "test").orElseThrow();
        int turns = 0;
        final var points = street.points();
        for (int index = 2; index < points.size(); index++)
        {
            final int ax = points.get(index - 1).getX() - points.get(index - 2).getX();
            final int bx = points.get(index).getX() - points.get(index - 1).getX();
            if (ax != bx) turns++;
        }
        assertTrue(turns <= 2, () -> "turns=" + points);
    }

    @Test
    void geometryKindsSurviveRoundTrip()
    {
        final var segment = SettlementStreetSegment.withGeometry(UUID.randomUUID(), List.of(
            new LocalStreetPoint(new BlockPos(1, 64, 1), LocalStreetKind.GROUND),
            new LocalStreetPoint(new BlockPos(1, 65, 2), LocalStreetKind.STAIRS),
            new LocalStreetPoint(new BlockPos(1, 65, 3), LocalStreetKind.BRIDGE)), 1, "test");
        assertEquals(segment.geometry(), SettlementStreetSegment.load(segment.save()).geometry());
    }
}
