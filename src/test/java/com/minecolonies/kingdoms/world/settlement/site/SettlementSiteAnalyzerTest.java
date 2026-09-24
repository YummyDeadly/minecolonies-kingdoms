package com.minecolonies.kingdoms.world.settlement.site;

import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementSiteAnalyzerTest
{
    private final SettlementSiteAnalyzer analyzer = new SettlementSiteAnalyzer();

    @Test
    void acceptsConnectedFlatLand()
    {
        final var result = analyze(SettlementType.TOWN, (x, z) -> land(64));
        assertTrue(result.accepted());
        assertEquals(result.totalSamples(), result.largestConnectedSamples());
        assertEquals(4, result.gateApproaches());
    }

    @Test
    void rejectsTinyIslandAndNarrowShorelineForTown()
    {
        final var island = analyze(SettlementType.VILLAGE,
            (x, z) -> Math.abs(x) <= 12 && Math.abs(z) <= 12 ? land(64) : water());
        assertFalse(island.accepted());
        assertTrue(island.rejection() == SiteRejectionReason.WATER
            || island.rejection() == SiteRejectionReason.INSUFFICIENT_BUILDABLE_AREA);
        final var shore = analyze(SettlementType.TOWN, (x, z) -> z < 6 ? water() : land(64));
        assertFalse(shore.accepted());
    }

    @Test
    void rejectsSteepMountainButAcceptsBoundedTerraceHill()
    {
        final var mountain = analyze(SettlementType.VILLAGE, (x, z) -> land(64 + Math.abs(x) / 2));
        assertFalse(mountain.accepted());
        assertTrue(mountain.rejection() == SiteRejectionReason.SLOPE
            || mountain.rejection() == SiteRejectionReason.CLIFF
            || mountain.rejection() == SiteRejectionReason.INSUFFICIENT_BUILDABLE_AREA);
        final var hill = analyze(SettlementType.VILLAGE, (x, z) -> land(64 + Math.floorDiv(x + 24, 24)));
        assertTrue(hill.accepted(), () -> hill.toString());
        assertTrue(hill.elevationSpan() > 0);
    }

    @Test
    void analysisIsDeterministicAndBounded()
    {
        final int[] samples = {0};
        final TerrainSampler terrain = (x, z) -> { samples[0]++; return land(64 + Math.floorMod(x + z, 3)); };
        final var first = analyze(SettlementType.CASTLE, terrain);
        assertTrue(samples[0] <= SettlementSiteAnalyzer.MAX_SAMPLES);
        samples[0] = 0;
        assertEquals(first, analyze(SettlementType.CASTLE, terrain));
        assertTrue(samples[0] <= SettlementSiteAnalyzer.MAX_SAMPLES);
    }

    private SettlementSiteAnalysis analyze(final SettlementType type, final TerrainSampler terrain)
    {
        return analyzer.analyze(0, 0, SettlementSiteRequirements.forType(type, 32), terrain);
    }
    private static TerrainSample land(final int height) { return new TerrainSample(height, false, true); }
    private static TerrainSample water() { return new TerrainSample(62, true, true); }
}
