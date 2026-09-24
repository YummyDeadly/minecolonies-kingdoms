package com.minecolonies.kingdoms.world.settlement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementPlannerTest
{
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final TerrainSampler FLAT = (x, z) -> new TerrainSample(70, false, true);

    @Test
    void sameSeedProducesSameSettlement()
    {
        final SettlementPlanner planner = new SettlementPlanner();
        final SettlementRegion region = new SettlementRegion(OVERWORLD, 4, -7);
        final SettlementRecord first = planner.plan(123456L, region, settings(0), FLAT).orElseThrow();
        final SettlementRecord second = planner.plan(123456L, region, settings(0), FLAT).orElseThrow();
        assertEquals(first.id(), second.id());
        assertEquals(first.name(), second.name());
        assertEquals(first.anchor(), second.anchor());
        assertEquals(first.type(), second.type());
        assertEquals(first.orientation(), second.orientation());
    }

    @Test
    void visitationOrderDoesNotChangeLayout()
    {
        final SettlementPlanner planner = new SettlementPlanner();
        final List<SettlementRegion> regions = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) regions.add(new SettlementRegion(OVERWORLD, x, z));
        final Map<String, String> forward = layout(planner, regions);
        Collections.reverse(regions);
        assertEquals(forward, layout(planner, regions));
    }

    @Test
    void minimumDistanceIsEnforcedIndependently()
    {
        final SettlementPlanner planner = new SettlementPlanner();
        final SettlementSettings settings = settings(420);
        final List<SettlementRecord> records = new ArrayList<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
            planner.plan(991L, new SettlementRegion(OVERWORLD, x, z), settings, FLAT).ifPresent(records::add);
        for (int left = 0; left < records.size(); left++) for (int right = left + 1; right < records.size(); right++)
        {
            final double distance = Math.sqrt(records.get(left).anchor().distSqr(records.get(right).anchor()));
            assertTrue(distance >= settings.minimumDistance(), records.get(left).name() + " too close to " + records.get(right).name());
        }
    }

    @Test
    void invalidTerrainRejectsAndNamesAreStableUnique()
    {
        final SettlementPlanner planner = new SettlementPlanner();
        assertTrue(planner.plan(5L, new SettlementRegion(OVERWORLD, 0, 0), settings(0),
            (x, z) -> new TerrainSample(63, true, false)).isEmpty());
        final SettlementRecord a = planner.plan(5L, new SettlementRegion(OVERWORLD, 1, 1), settings(0), FLAT).orElseThrow();
        final SettlementRecord b = planner.plan(5L, new SettlementRegion(OVERWORLD, 2, 1), settings(0), FLAT).orElseThrow();
        assertFalse(a.name().isBlank());
        assertFalse(a.name().equals(b.name()));
        assertThrows(IllegalArgumentException.class, () -> new SettlementSettings(64, 101, 0, 1, 1, 1, 1,
            Map.of(SettlementType.VILLAGE, 1)));
    }

    private static Map<String, String> layout(final SettlementPlanner planner, final List<SettlementRegion> regions)
    {
        final Map<String, String> result = new LinkedHashMap<>();
        for (final SettlementRegion region : regions) planner.plan(42L, region, settings(420), FLAT)
            .ifPresent(record -> result.put(region.key(), record.id() + ":" + record.anchor() + ":" + record.type()));
        return result;
    }

    private static SettlementSettings settings(final int distance)
    {
        return new SettlementSettings(512, 100, distance, 8, 16, 8, 12,
            Map.of(SettlementType.VILLAGE, 4, SettlementType.TOWN, 2, SettlementType.TRADING_TOWN, 1,
                SettlementType.CASTLE, 1, SettlementType.FORT, 1));
    }
}
