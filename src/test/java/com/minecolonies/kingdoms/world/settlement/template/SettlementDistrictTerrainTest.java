package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.layout.LocalStreetPoint;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetSegment;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalysis;
import com.minecolonies.kingdoms.world.settlement.site.SiteRejectionReason;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureCatalog;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingSettings;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.minecolonies.kingdoms.world.settlement.SettlementFixtures.land;
import static com.minecolonies.kingdoms.world.settlement.SettlementFixtures.water;
import static org.junit.jupiter.api.Assertions.*;

class SettlementDistrictTerrainTest
{
    private final SettlementDistrictPlanner planner = new SettlementDistrictPlanner();

    @Test
    void moderateHillUsesSeveralLocalLevelsInsteadOfOneFlattenedPlatform()
    {
        // 1 block rise every 4 blocks eastward: ~25 blocks across the starter district.
        final TerrainSampler hill = (x, z) -> land(64 + Math.floorDiv(x, 4));
        final SettlementRecord settlement = SettlementFixtures.village("terrace-hill", 64);
        final var result = planner.plan(settlement, SettlementFixtures.catalog("style-a"), hill, new RoadNetwork(), 0);
        assertTrue(result.accepted(), result::blocker);
        final List<SettlementBuildingRecord> buildings = result.plan().buildings();
        assertTrue(buildings.size() >= 2 && buildings.size() <= 4);
        final Set<Integer> levels = buildings.stream().map(value -> value.terrainShaping().pad().targetHeight())
            .collect(Collectors.toSet());
        assertTrue(levels.size() >= 2, () -> "expected several terrace levels, got " + levels);
        final TerrainShapingSettings limits = TerrainShapingSettings.defaults();
        for (final SettlementBuildingRecord building : buildings)
        {
            final var pad = building.terrainShaping().pad();
            assertTrue(pad.maximumCutDepth() <= limits.maximumCutDepth());
            assertTrue(pad.maximumFillDepth() <= limits.maximumFillDepth());
        }
        for (final SettlementStreetSegment street : result.plan().layout().streets().segments())
        {
            final List<LocalStreetPoint> points = street.geometry();
            for (int index = 1; index < points.size(); index++)
                assertTrue(Math.abs(points.get(index).position().getY() - points.get(index - 1).position().getY()) <= 1,
                    "street " + street.purpose() + " must change level by at most one block per step");
        }
    }

    @Test
    void starterHasPlazaGateStreetAndBuildingsConnectedToIt()
    {
        final SettlementRecord settlement = SettlementFixtures.village("plaza-gate", 64);
        final var result = planner.plan(settlement, SettlementFixtures.catalog("style-a"), (x, z) -> land(64),
            new RoadNetwork(), 0);
        assertTrue(result.accepted(), result::blocker);
        final var layout = result.plan().layout();
        final var purposes = layout.streets().segments().stream().map(SettlementStreetSegment::purpose).toList();
        assertTrue(purposes.contains(SettlementLayoutPlanner.PLAZA_PURPOSE));
        assertTrue(purposes.contains(SettlementLayoutPlanner.ROOT_PURPOSE));
        final SettlementStreetSegment root = layout.streets().segments().stream()
            .filter(value -> SettlementLayoutPlanner.ROOT_PURPOSE.equals(value.purpose())).findFirst().orElseThrow();
        assertEquals(settlement.gate().getX(), root.points().getFirst().getX());
        assertEquals(settlement.gate().getZ(), root.points().getFirst().getZ());
        final List<BlockPos> network = layout.streets().points();
        for (final var lot : layout.lots())
            assertTrue(network.contains(lot.streetJoin()) && network.contains(lot.entrance()),
                "every starter lot is joined to the local network at its entrance");
    }

    @Test
    void narrowCoastAndSteepMountainAreBlockedWithActionableNumbers()
    {
        final SettlementStructureCatalog catalog = SettlementFixtures.catalog("style-a");
        // A 10-block strip of land between sea and a cliff wall.
        final TerrainSampler coast = (x, z) -> z > 12 ? water() : z < -2 ? land(64 + (-2 - z) * 3) : land(64);
        final var coastal = planner.plan(SettlementFixtures.village("coast", 64), catalog, coast, new RoadNetwork(), 0);
        assertFalse(coastal.accepted());
        assertTrue(coastal.blocker().contains("NO_VALID_STARTER_PAD") || coastal.blocker().startsWith("STREET_UNREACHABLE"),
            coastal.blocker());
        final TerrainSampler mountain = (x, z) -> land(64 + Math.abs(x) + Math.abs(z));
        final var steep = planner.plan(SettlementFixtures.village("mountain", 64), catalog, mountain, new RoadNetwork(), 0);
        assertFalse(steep.accepted());
        if (steep.blocker().contains("NO_VALID_STARTER_PAD"))
        {
            assertTrue(steep.blocker().contains("positions="), steep.blocker());
            assertTrue(steep.blocker().contains("rejected={"), steep.blocker());
            assertTrue(steep.diagnostics().candidatePadsChecked() + steep.diagnostics().positionsChecked() > 0);
        }
    }

    @Test
    void rejectedSiteAndMissingStructuresNeverFallBackToProceduralBoxes()
    {
        final UUID id = UUID.nameUUIDFromBytes("rejected-site".getBytes());
        final var site = new SettlementSiteAnalysis(false, 25, 2, 2, 20, 0, 60, 64, 1, 0, 0L, SiteRejectionReason.WATER, 0);
        final SettlementRecord rejected = new SettlementRecord(id, "Rejected", SettlementType.VILLAGE,
            SettlementFixtures.OVERWORLD, new BlockPos(0, 64, 0), 0, new BlockPos(0, 64, 28), UUID.randomUUID(), 12,
            SettlementPhysicalState.PLANNED, new SettlementRegion(SettlementFixtures.OVERWORLD, 0, 0), null, 0, site);
        final var siteResult = planner.plan(rejected, SettlementFixtures.catalog("style-a"), (x, z) -> land(64), new RoadNetwork(), 0);
        assertFalse(siteResult.accepted());
        assertEquals("SITE_REJECTED:WATER", siteResult.blocker());
        final var empty = planner.plan(SettlementFixtures.village("empty-catalog", 64), SettlementStructureCatalog.empty(),
            (x, z) -> land(64), new RoadNetwork(), 0);
        assertFalse(empty.accepted());
        assertEquals("NO_COHERENT_STRUCTURE_STYLE", empty.blocker());
        assertNull(empty.plan());
    }

    @Test
    void styleIsCoherentAcrossMultipleInstalledStylesAndPlanningIsBounded()
    {
        final AtomicInteger samples = new AtomicInteger();
        final TerrainSampler terrain = SettlementFixtures.counting((x, z) -> land(64 + Math.floorMod(x * 7 + z * 3, 2)), samples);
        final var result = planner.plan(SettlementFixtures.village("two-styles", 64),
            SettlementFixtures.catalog("style-a", "style-b"), terrain, new RoadNetwork(), 0);
        assertTrue(result.accepted(), result::blocker);
        final Set<String> styles = result.plan().buildings().stream().map(SettlementBuildingRecord::styleFamily)
            .collect(Collectors.toSet());
        assertEquals(Set.of(result.plan().styleFamily()), styles);
        assertTrue(samples.get() < 60_000, () -> "samples=" + samples.get());
    }
}
