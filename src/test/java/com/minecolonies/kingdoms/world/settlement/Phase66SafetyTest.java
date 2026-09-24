package com.minecolonies.kingdoms.world.settlement;

import com.minecolonies.kingdoms.world.settlement.growth.FreshChunkTracker;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalysis;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalyzer;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteRequirements;
import com.minecolonies.kingdoms.world.settlement.site.SiteRejectionReason;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingPlanner;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingRejection;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.minecolonies.kingdoms.world.settlement.SettlementFixtures.land;
import static com.minecolonies.kingdoms.world.settlement.SettlementFixtures.water;
import static org.junit.jupiter.api.Assertions.*;

class Phase66SafetyTest
{
    private static final StructureFootprint PAD = new StructureFootprint(-4, -4, 4, 4);

    @Test
    void freshChunkTrackerIsBoundedAndDimensionScoped()
    {
        final ResourceKey<Level> overworld = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        final ResourceKey<Level> nether = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));
        final FreshChunkTracker tracker = new FreshChunkTracker(3);
        tracker.add(overworld, 1L); tracker.add(overworld, 2L); tracker.add(overworld, 3L);
        assertTrue(tracker.contains(overworld, 1L));
        assertFalse(tracker.contains(nether, 1L), "a chunk key in another dimension is not owned");
        tracker.add(overworld, 4L);
        assertEquals(3, tracker.size());
        assertFalse(tracker.contains(overworld, 1L), "oldest entry evicted: protection only becomes stricter");
        tracker.clear();
        assertFalse(tracker.contains(overworld, 4L));
    }

    @Test
    void cutAndFillLimitsReportTheirSpecificReason()
    {
        final TerrainShapingPlanner planner = new TerrainShapingPlanner();
        final var cut = planner.plan(PAD, (x, z) -> land(x == 0 && z == 0 ? 68 : 64), new TerrainShapingSettings(1, 1, 8, 5, 4096, 1));
        assertEquals(TerrainShapingRejection.EXCESSIVE_CUT, cut.rejection());
        final var fill = planner.plan(PAD, (x, z) -> land(x == 0 && z == 0 ? 60 : 64), new TerrainShapingSettings(1, 1, 8, 5, 4096, 1));
        assertEquals(TerrainShapingRejection.EXCESSIVE_FILL, fill.rejection());
        final var cliff = planner.plan(PAD, (x, z) -> land(x < 0 ? 60 : 72), TerrainShapingSettings.defaults());
        assertEquals(TerrainShapingRejection.EXCESSIVE_HEIGHT_VARIANCE, cliff.rejection());
    }

    @Test
    void fillPadSupportsEveryColumnWithoutFloatingCornersOrGiantColumns()
    {
        final TerrainShapingSettings settings = TerrainShapingSettings.defaults();
        final var plan = new TerrainShapingPlanner().plan(PAD, (x, z) -> land(64 - Math.max(0, x) / 2), settings).plan();
        final var pad = plan.pad();
        int expectedFill = 0;
        int expectedCut = 0;
        for (int x = PAD.minX(); x <= PAD.maxX(); x++) for (int z = PAD.minZ(); z <= PAD.maxZ(); z++)
        {
            final int height = 64 - Math.max(0, x) / 2;
            expectedFill += Math.max(0, pad.targetHeight() - height);
            expectedCut += Math.max(0, height - pad.targetHeight());
        }
        assertEquals(expectedFill, pad.fillVolume(), "every lower column (including corners) is filled to the pad");
        assertEquals(expectedCut, pad.cutVolume());
        assertTrue(pad.maximumFillDepth() <= settings.maximumFillDepth(), "no giant fill columns");
        assertTrue(pad.retainingWalls().stream().allMatch(wall -> wall.topHeight() - wall.baseHeight() <= settings.maximumRetainingWallHeight()));
    }

    @Test
    void coarseSiteRejectionIsCheapAndOnlyForHopelessSites()
    {
        final SettlementSiteAnalyzer analyzer = new SettlementSiteAnalyzer();
        final SettlementSiteRequirements requirements = SettlementSiteRequirements.forType(SettlementType.TOWN, 32);
        final AtomicInteger samples = new AtomicInteger();
        assertEquals(SiteRejectionReason.WATER, analyzer.coarseReject(0, 0, requirements,
            SettlementFixtures.counting((x, z) -> water(), samples)));
        assertEquals(25, samples.get());
        assertEquals(SiteRejectionReason.NONE, analyzer.coarseReject(0, 0, requirements, (x, z) -> land(64)));
        final var flat = analyzer.analyze(0, 0, requirements, (x, z) -> land(64));
        assertTrue(flat.accepted());
        assertEquals(15, flat.approachMask());
    }

    @Test
    void shorelineAnalysisPointsTowardItsBuildableLandForBoundedRefinement()
    {
        final SettlementSiteAnalyzer analyzer = new SettlementSiteAnalyzer();
        final SettlementSiteRequirements requirements = SettlementSiteRequirements.forType(SettlementType.VILLAGE, 32);
        final TerrainSampler shore = (x, z) -> x < -10 ? water() : land(64);
        final var atShore = analyzer.analyzeDetailed(0, 0, requirements, shore);
        assertFalse(atShore.analysis().accepted());
        assertTrue(atShore.centroidX() > requirements.sampleSpacing(), () -> "centroid=" + atShore.centroidX());
        assertEquals(0, atShore.centroidZ(), 1);
        final var refined = analyzer.analyzeDetailed(atShore.centroidX(), atShore.centroidZ(), requirements, shore);
        assertTrue(refined.analysis().waterFraction() < atShore.analysis().waterFraction());
    }

    @Test
    void unloadedTerrainIsNeverGuessedAndStarterFailureIsTransient()
    {
        final TerrainSampler halfLoaded = (x, z) -> x > 20 ? TerrainSample.unknown() : land(64);
        final var pad = new TerrainShapingPlanner().plan(new StructureFootprint(18, 0, 26, 8), halfLoaded,
            TerrainShapingSettings.defaults());
        assertEquals(TerrainShapingRejection.UNLOADED_TERRAIN, pad.rejection());
        final var planner = new com.minecolonies.kingdoms.world.settlement.template.SettlementDistrictPlanner();
        final var unloaded = planner.plan(SettlementFixtures.village("unloaded", 64), SettlementFixtures.catalog("style-a"),
            (x, z) -> TerrainSample.unknown(), new com.minecolonies.kingdoms.world.road.RoadNetwork(), 0);
        assertFalse(unloaded.accepted());
        assertTrue(unloaded.unloadedTerrain(), unloaded.blocker());
    }

    @Test
    void memoizedPlanningIsOrderIndependentForAsynchronousCommit()
    {
        final var settings = new SettlementSettings(512, 100, 300, 4, 32, 10, 18,
            java.util.Map.of(SettlementType.VILLAGE, 1));
        final TerrainSampler terrain = (x, z) -> land(64 + Math.floorMod(x / 37 + z / 53, 3));
        final var regions = new java.util.ArrayList<SettlementRegion>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) regions.add(new SettlementRegion(SettlementFixtures.OVERWORLD, x, z));
        final SettlementPlanner reused = new SettlementPlanner();
        final var first = reused.planAll(99L, regions, settings, terrain);
        final var reversed = new java.util.ArrayList<>(regions);
        java.util.Collections.reverse(reversed);
        final var second = reused.planAll(99L, reversed, settings, terrain);
        final var fresh = new SettlementPlanner().planAll(99L, reversed, settings, terrain);
        for (final SettlementRegion region : regions)
        {
            assertEquals(first.get(region).map(value -> value.save().toString()), second.get(region).map(value -> value.save().toString()));
            assertEquals(first.get(region).map(value -> value.save().toString()), fresh.get(region).map(value -> value.save().toString()));
        }
    }

    @Test
    void gateOrientationAvoidsUnapproachableSides()
    {
        assertEquals(0, SettlementPlanner.orientation(0, SettlementSiteAnalysis.SOUTH));
        assertEquals(270, SettlementPlanner.orientation(0, SettlementSiteAnalysis.EAST));
        assertEquals(180, SettlementPlanner.orientation(1, SettlementSiteAnalysis.NORTH | SettlementSiteAnalysis.EAST));
        assertEquals(90, SettlementPlanner.orientation(1, 15), "preferred side kept when approachable");
    }

    @Test
    void operatorRetryKeepsCompletedChunkMarkersAndAwaitingNotesDoNotChangeStatus()
    {
        final UUID id = UUID.nameUUIDFromBytes("retry".getBytes());
        final SettlementBuildingRecord building = new SettlementBuildingRecord(id, UUID.randomUUID(), SettlementBuildingType.HOUSE,
            new BlockPos(0, 64, 0), 0, 0, 3, 0, SettlementBuildingStatus.READY);
        building.awaiting("EXISTING_CHUNK_PROTECTED");
        assertEquals(SettlementBuildingStatus.READY, building.status());
        assertEquals("EXISTING_CHUNK_PROTECTED", building.blocker());
        building.markGenerated(7L);
        building.blocked("BLOCK_ENTITY: block entity inside terrain-shaping footprint");
        assertTrue(building.retryAfterBlock());
        assertEquals(SettlementBuildingStatus.GENERATING, building.status());
        assertFalse(building.needsGeneration(7L), "already written slice is never written twice");
        assertFalse(building.retryAfterBlock());
    }
}
