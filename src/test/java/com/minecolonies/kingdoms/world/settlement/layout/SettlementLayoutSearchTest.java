package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureCatalog;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.minecolonies.kingdoms.world.settlement.SettlementFixtures.land;
import static com.minecolonies.kingdoms.world.settlement.SettlementFixtures.water;
import static org.junit.jupiter.api.Assertions.*;

class SettlementLayoutSearchTest
{
    private final SettlementLayoutPlanner planner = new SettlementLayoutPlanner();
    private final SettlementStructureCatalog catalog = SettlementFixtures.catalog("style-a");

    @Test
    void growthFindsLotWhenNearSideIsWaterInsteadOfStarvingOnFirstPositions()
    {
        // Phase 6.5 regression: the old search spent its whole pad budget on descriptor x rotation at the first
        // two or three ring positions. Here most of the ring is lake, only the east side is buildable.
        final SettlementRecord settlement = SettlementFixtures.village("lake-side", 64);
        final TerrainSampler terrain = (x, z) -> x < 40 && Math.abs(z) > 20 ? water() : land(64);
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "style-a", (x, z) -> land(64)).orElseThrow();
        final var placement = planner.plan(settlement, id("farm"), catalog.candidates(settlement.id(),
            SettlementBuildingType.FARM, 0, "style-a", 6), layout, 96, terrain, new RoadNetwork());
        assertTrue(placement.isPresent(), () -> layout.lastSearch().summary());
        final LayoutPlanningDiagnostics search = layout.lastSearch();
        assertTrue(search.positionsChecked() <= SettlementLayoutPlanner.MAX_POSITIONS);
        assertTrue(search.candidatePadsChecked() <= SettlementLayoutPlanner.MAX_CANDIDATE_PADS);
        assertTrue(search.acceptedCandidates() >= 1);
    }

    @Test
    void streetExtensionConnectsLotWithoutExistingFrontage()
    {
        final SettlementRecord settlement = SettlementFixtures.village("extension", 64);
        final TerrainSampler flat = (x, z) -> land(64);
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "style-a", flat).orElseThrow();
        final List<BlockPos> before = layout.streets().points();
        final var placement = planner.plan(settlement, id("house"), catalog.candidates(settlement.id(),
            SettlementBuildingType.HOUSE, 0, "style-a", 6), layout, 96, flat, new RoadNetwork()).orElseThrow();
        final SettlementStreetSegment street = placement.streetExtension();
        assertTrue(street.points().size() >= 2);
        assertEquals(placement.lot().entrance(), street.points().getFirst(), "fixture door is outside its footprint");
        assertTrue(before.contains(street.points().getLast()), "extension must end on the existing local network");
        assertFalse(before.contains(street.points().getFirst()), "lot did not need an existing frontage");
    }

    @Test
    void recessedDoorInsideFootprintStillGetsAStreetThatStartsOutsideTheBuilding()
    {
        // Real MineColonies residences have porches: the Phase 6.5 residence entrance lay inside its own footprint.
        final SettlementRecord settlement = SettlementFixtures.village("recessed", 64);
        final TerrainSampler flat = (x, z) -> land(64);
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "style-a", flat).orElseThrow();
        final var recessed = new com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureDescriptor(
            "fixture:porch", "porch.blueprint", SettlementBuildingType.HOUSE, "style-a", 1, 9, 8, 9,
            new com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint(-4, -4, 4, 4),
            new BlockPos(0, 0, 2), net.minecraft.core.Direction.SOUTH,
            java.util.Set.of(new com.minecolonies.kingdoms.world.settlement.structure.StructureTransform(0, false),
                new com.minecolonies.kingdoms.world.settlement.structure.StructureTransform(180, false)),
            com.minecolonies.kingdoms.world.settlement.structure.TerrainPlacementConstraints.settlementDefault());
        final var selection = new com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureSelection(recessed,
            new com.minecolonies.kingdoms.world.settlement.structure.StructureTransform(0, false));
        final var placement = planner.plan(settlement, id("porch"), List.of(selection), layout, 96, flat, new RoadNetwork());
        assertTrue(placement.isPresent(), () -> layout.lastSearch().summary());
        final var value = placement.orElseThrow();
        assertTrue(value.lot().footprint().contains(value.lot().entrance().getX(), value.lot().entrance().getZ()));
        final BlockPos first = value.streetExtension().points().getFirst();
        assertFalse(value.lot().footprint().contains(first.getX(), first.getZ()), "street never paves the building floor");
    }

    @Test
    void growthPlacesSeveralStructuresWithoutBuildingOrStreetCollisions()
    {
        final SettlementRecord settlement = SettlementFixtures.village("several", 64);
        final TerrainSampler gentle = (x, z) -> land(64 + Math.floorDiv(x, 24));
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "style-a", gentle).orElseThrow();
        final List<LayoutPlacement> placed = new ArrayList<>();
        final SettlementBuildingType[] types = {SettlementBuildingType.HOUSE, SettlementBuildingType.FARM,
            SettlementBuildingType.STOREHOUSE, SettlementBuildingType.HOUSE, SettlementBuildingType.SMITHY};
        for (int index = 0; index < types.length; index++)
        {
            final var placement = planner.plan(settlement, id("several-" + index), catalog.candidates(settlement.id(),
                types[index], index, "style-a", 6), layout, 112, gentle, new RoadNetwork());
            assertTrue(placement.isPresent(), () -> layout.lastSearch().summary());
            final LayoutPlacement value = placement.orElseThrow();
            for (final SettlementStreetSegment street : layout.streets().segments())
                assertFalse(street.intersects(value.lot().footprint(), street.width()),
                    "lot " + value.lot().footprint() + " overlaps street " + street.purpose());
            for (final LayoutPlacement other : placed)
                assertFalse(other.lot().footprint().expand(2).intersects(value.lot().footprint()));
            layout.streets().put(value.streetExtension());
            layout.putLot(value.lot());
            placed.add(value);
        }
        assertEquals(types.length, layout.lots().size());
    }

    @Test
    void hopelessTerrainIsRejectedWithBoundedSamplingAndActionableCounts()
    {
        final SettlementRecord settlement = SettlementFixtures.village("ocean", 64);
        final TerrainSampler flat = (x, z) -> land(64);
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "style-a", flat).orElseThrow();
        final AtomicInteger samples = new AtomicInteger();
        final TerrainSampler ocean = SettlementFixtures.counting((x, z) -> Math.abs(x) < 8 && Math.abs(z) < 8 ? land(64) : water(), samples);
        final var placement = planner.plan(settlement, id("ocean"), catalog.candidates(settlement.id(),
            SettlementBuildingType.FARM, 0, "style-a", 6), layout, 160, ocean, new RoadNetwork());
        assertTrue(placement.isEmpty());
        final LayoutPlanningDiagnostics search = layout.lastSearch();
        assertEquals(SettlementLayoutPlanner.MAX_POSITIONS, search.positionsChecked());
        assertTrue(search.rejections().getOrDefault(LayoutRejectionReason.WATER, 0) > 0);
        assertEquals(0, search.candidatePadsChecked(), "prefilter must avoid full-footprint sampling on water");
        assertTrue(samples.get() <= SettlementLayoutPlanner.MAX_POSITIONS * 6 * 5, () -> "samples=" + samples.get());
        assertTrue(search.summary().contains("WATER="));
    }

    @Test
    void globalRoadAndPlazaAreReserved()
    {
        final SettlementRecord settlement = SettlementFixtures.village("reserved", 64);
        final TerrainSampler flat = (x, z) -> land(64);
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "style-a", flat).orElseThrow();
        final var onPlaza = new com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint(-2, -2, 2, 2);
        assertEquals(LayoutRejectionReason.STARTER_EXCLUSION,
            SettlementLayoutPlanner.reserved(settlement, onPlaza, layout, List.of(), false));
        // Fixture gate is at (0, 28), south of the anchor: the corridor continues south of the gate.
        final var inFrontOfGate = new com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint(-4, 40, 4, 48);
        assertEquals(LayoutRejectionReason.GATE_APPROACH,
            SettlementLayoutPlanner.reserved(settlement, inFrontOfGate, layout, List.of(), true));
        final var nearRoad = new com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint(40, 40, 48, 48);
        assertEquals(LayoutRejectionReason.GLOBAL_ROAD, SettlementLayoutPlanner.reserved(settlement, nearRoad, layout,
            List.<int[]>of(new int[] {50, 44, 4}), false));
        final BlockPos streetPoint = layout.streets().segments().stream()
            .filter(value -> SettlementLayoutPlanner.ROOT_PURPOSE.equals(value.purpose())).findFirst().orElseThrow()
            .points().get(12);
        final var onStreet = new com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint(
            streetPoint.getX() - 1, streetPoint.getZ() - 1, streetPoint.getX() + 1, streetPoint.getZ() + 1);
        assertEquals(LayoutRejectionReason.LOCAL_STREET_COLLISION,
            SettlementLayoutPlanner.reserved(settlement, onStreet, layout, List.of(), false));
    }

    @Test
    void stairsAreBuiltInTheLowerColumnFacingUphill()
    {
        final List<LocalStreetPoint> points = List.of(
            new LocalStreetPoint(new BlockPos(0, 64, 0), LocalStreetKind.GROUND),
            new LocalStreetPoint(new BlockPos(0, 65, 1), LocalStreetKind.STAIRS),
            new LocalStreetPoint(new BlockPos(0, 65, 2), LocalStreetKind.GROUND),
            new LocalStreetPoint(new BlockPos(0, 64, 3), LocalStreetKind.STAIRS));
        final var facings = SettlementStreetChunkGenerator.stairFacings(points);
        assertEquals(net.minecraft.core.Direction.SOUTH, facings[0], "ascending: lower first column faces the climb");
        assertNull(facings[1]);
        assertNull(facings[2]);
        assertEquals(net.minecraft.core.Direction.NORTH, facings[3], "descending: lower last column faces back uphill");
    }

    @Test
    void streetChunkMarkersMakeSlicesIdempotentAndRoundTrip()
    {
        final SettlementRecord settlement = SettlementFixtures.village("markers", 64);
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "style-a", (x, z) -> land(64)).orElseThrow();
        final SettlementStreetSegment root = layout.streets().segments().stream()
            .filter(value -> SettlementLayoutPlanner.ROOT_PURPOSE.equals(value.purpose())).findFirst().orElseThrow();
        final var chunks = SettlementStreetChunkGenerator.chunks(root);
        assertFalse(chunks.isEmpty());
        final long first = chunks.iterator().next();
        assertFalse(layout.streetChunkGenerated(root.id(), first));
        layout.markStreetChunk(root.id(), first);
        layout.markStreetChunk(root.id(), first);
        assertEquals(1, layout.generatedStreetChunkMarkers());
        final SettlementLayoutPlan loaded = SettlementLayoutPlan.load(layout.save());
        assertTrue(loaded.streetChunkGenerated(root.id(), first));
        assertEquals(layout.save(), loaded.save());
    }

    private static UUID id(final String value) { return UUID.nameUUIDFromBytes(value.getBytes()); }
}
