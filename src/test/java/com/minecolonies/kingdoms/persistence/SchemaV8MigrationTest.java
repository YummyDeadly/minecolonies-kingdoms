package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingOrigin;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.growth.StarterDistrictStatus;
import com.minecolonies.kingdoms.world.settlement.layout.LayoutPlanningDiagnostics;
import com.minecolonies.kingdoms.world.settlement.layout.LayoutRejectionReason;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetNetwork;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetSegment;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalysis;
import com.minecolonies.kingdoms.world.settlement.site.SiteRejectionReason;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import com.minecolonies.kingdoms.world.settlement.template.SettlementDistrictPlanner;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingPlanner;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SchemaV8MigrationTest
{
    private static final UUID FIRST = UUID.nameUUIDFromBytes("v7-first".getBytes());
    private static final UUID SECOND = UUID.nameUUIDFromBytes("v7-second".getBytes());
    private static final UUID ROAD = UUID.nameUUIDFromBytes("v7-road".getBytes());
    private static final UUID SHIPMENT = UUID.nameUUIDFromBytes("v7-shipment".getBytes());
    private static final UUID LEGACY_BUILDING = UUID.nameUUIDFromBytes("v7-legacy".getBytes());
    private static final UUID BLUEPRINT_BUILDING = UUID.nameUUIDFromBytes("v7-blueprint".getBytes());

    @Test
    void versionSevenSaveMigratesWithoutChangingIdentitiesOrTravelState()
    {
        final CompoundTag v7 = populated().save(new CompoundTag(), null);
        v7.putInt("dataVersion", 7);
        final CompoundTag growth = v7.getCompound("growth");
        growth.getList("buildings", Tag.TAG_COMPOUND).forEach(value -> ((CompoundTag) value).remove("origin"));
        growth.getList("layouts", Tag.TAG_COMPOUND).forEach(value -> {
            ((CompoundTag) value).remove("template");
            ((CompoundTag) value).remove("streetChunks");
            ((CompoundTag) value).remove("diagnostics");
        });
        growth.getList("states", Tag.TAG_COMPOUND).forEach(value -> {
            ((CompoundTag) value).remove("starterDistrictStatus");
            ((CompoundTag) value).remove("starterPlannerVersion");
            ((CompoundTag) value).remove("starterDiagnostics");
        });
        v7.getCompound("settlements").getList("records", Tag.TAG_COMPOUND)
            .forEach(value -> ((CompoundTag) value).remove("siteAnalysis"));

        final KingdomsSavedData loaded = KingdomsSavedData.load(v7, null);

        assertEquals(KingdomsSavedData.DATA_VERSION, loaded.save(new CompoundTag(), null).getInt("dataVersion"));
        assertEquals(FIRST, loaded.settlements().get(FIRST).orElseThrow().id());
        assertEquals(SECOND, loaded.settlements().get(SECOND).orElseThrow().id());
        assertTrue(loaded.settlements().get(FIRST).orElseThrow().siteAnalysis().isEmpty());
        assertEquals(ROAD, loaded.roads().get(ROAD).orElseThrow().id());
        final TradeShipment shipment = loaded.tradeLedger().shipment(SHIPMENT).orElseThrow();
        assertEquals(777, shipment.travelDurationTicks());
        assertEquals(797, shipment.arrivalAt());
        assertEquals(0.5D, shipment.progressAt(20 + 777 / 2 + 1), 0.01D);
        assertEquals(SettlementBuildingOrigin.LEGACY, loaded.growth().building(LEGACY_BUILDING).orElseThrow().origin());
        assertEquals(SettlementBuildingOrigin.GROWTH, loaded.growth().building(BLUEPRINT_BUILDING).orElseThrow().origin());
        assertFalse(loaded.growth().building(BLUEPRINT_BUILDING).orElseThrow().needsGeneration(99L));
        assertEquals("", loaded.growth().layout(FIRST).orElseThrow().templateId());
        assertEquals(StarterDistrictStatus.UNPLANNED, loaded.growth().state(FIRST).orElseThrow().starterDistrictStatus());
        assertEquals(0, loaded.growth().state(FIRST).orElseThrow().starterPlannerVersion());
    }

    @Test
    void schemaEightTerrainTemplateAndDiagnosticsStateRoundTrips()
    {
        final KingdomsSavedData source = populated();
        final var state = source.growth().stateOrCreate(SECOND);
        state.starterDiagnostics(new LayoutPlanningDiagnostics(3, 12, 9, 0, 0, 0, 1_000L,
            Map.of(LayoutRejectionReason.ROUGHNESS, 5, LayoutRejectionReason.WATER, 4), 17));
        state.starterDistrictBlocked("STARTER_TEMPLATE_BLOCKED:NO_VALID_STARTER_PAD:HOUSE[...]",
            SettlementDistrictPlanner.STARTER_PLANNER_VERSION);
        final CompoundTag saved = source.save(new CompoundTag(), null);
        final KingdomsSavedData loaded = KingdomsSavedData.load(saved, null);
        assertEquals(saved, loaded.save(new CompoundTag(), null), "second save must be byte-identical (no drift)");
        final var restored = loaded.growth().state(SECOND).orElseThrow();
        assertEquals(StarterDistrictStatus.BLOCKED, restored.starterDistrictStatus());
        assertEquals(17, restored.starterDiagnostics().positionsChecked());
        assertEquals(5, restored.starterDiagnostics().rejections().get(LayoutRejectionReason.ROUGHNESS));
        assertFalse(restored.resetStarterIfPlannedBefore(SettlementDistrictPlanner.STARTER_PLANNER_VERSION),
            "a starter blocked by the current planner is not re-planned on restart");
        final var building = loaded.growth().building(BLUEPRINT_BUILDING).orElseThrow();
        assertEquals(source.growth().building(BLUEPRINT_BUILDING).orElseThrow().terrainShaping(), building.terrainShaping());
        assertEquals(SettlementSiteAnalysis.SOUTH | SettlementSiteAnalysis.EAST,
            loaded.settlements().get(FIRST).orElseThrow().siteAnalysis().orElseThrow().approachMask());
        assertTrue(loaded.growth().layout(FIRST).orElseThrow().streetChunkGenerated(
            UUID.nameUUIDFromBytes("v7-street".getBytes()), 42L));
    }

    @Test
    void starterBlockedByOlderPlannerIsRetriedExactlyOnce()
    {
        final var state = new com.minecolonies.kingdoms.world.settlement.growth.SettlementGrowthState(FIRST);
        state.starterDistrictBlocked("STARTER_TEMPLATE_BLOCKED:NO_VALID_STARTER_PAD:HOUSE", 1);
        assertTrue(state.resetStarterIfPlannedBefore(SettlementDistrictPlanner.STARTER_PLANNER_VERSION));
        assertEquals(StarterDistrictStatus.UNPLANNED, state.starterDistrictStatus());
        assertNull(state.blocker());
        state.starterDistrictBlocked("STARTER_TEMPLATE_BLOCKED:again", SettlementDistrictPlanner.STARTER_PLANNER_VERSION);
        assertFalse(state.resetStarterIfPlannedBefore(SettlementDistrictPlanner.STARTER_PLANNER_VERSION));
    }

    private static KingdomsSavedData populated()
    {
        final var site = new SettlementSiteAnalysis(true, 289, 250, 240, 10, 0, 62, 70, 3, 2, 400L,
            SiteRejectionReason.NONE, SettlementSiteAnalysis.SOUTH | SettlementSiteAnalysis.EAST);
        final SettlementRecord first = new SettlementRecord(FIRST, "First", SettlementType.VILLAGE, SettlementFixtures.OVERWORLD,
            new BlockPos(0, 64, 0), 0, new BlockPos(0, 64, 28), UUID.nameUUIDFromBytes("f1".getBytes()), 14,
            SettlementPhysicalState.GENERATING, new SettlementRegion(SettlementFixtures.OVERWORLD, 0, 0), null, 5L, site);
        final SettlementRecord second = new SettlementRecord(SECOND, "Second", SettlementType.TOWN, SettlementFixtures.OVERWORLD,
            new BlockPos(600, 64, 0), 0, new BlockPos(600, 64, 40), UUID.nameUUIDFromBytes("f2".getBytes()), 30,
            SettlementPhysicalState.PLANNED, new SettlementRegion(SettlementFixtures.OVERWORLD, 0, 0), null, 6L, site);
        final KingdomsSavedData data = new KingdomsSavedData();
        data.settlements().put(first); data.settlements().put(second);
        data.roads().put(new RoadRecord(ROAD, FIRST, SECOND, SettlementFixtures.OVERWORLD, RoadType.STONE,
            List.of(first.gate(), second.gate()), RoadStatus.GENERATING, 2));
        final TradeShipment shipment = new TradeShipment(SHIPMENT, UUID.nameUUIDFromBytes("route".getBytes()),
            FIRST, SECOND, EconomicResource.FOOD, 50, 10);
        shipment.depart(20, 777);
        data.tradeLedger().putShipment(shipment);
        final SettlementBuildingRecord legacy = new SettlementBuildingRecord(LEGACY_BUILDING, FIRST,
            SettlementBuildingType.FARM, new BlockPos(60, 64, 60), 0, 0, 1, 10, SettlementBuildingStatus.COMPLETED);
        data.growth().put(legacy);
        final SettlementBuildingRecord blueprint = new SettlementBuildingRecord(BLUEPRINT_BUILDING, FIRST,
            SettlementBuildingType.HOUSE, new BlockPos(20, 64, 20), 90, 1, 3, 11, SettlementBuildingStatus.GENERATING,
            SettlementBuildingOrigin.GROWTH);
        final StructureFootprint footprint = new StructureFootprint(16, 16, 24, 24);
        blueprint.assignVisual("structurize:Colonial", "fundamentals/residence1.blueprint", "Colonial", false,
            new BlockPos(20, 64, 25), footprint);
        blueprint.assignTerrainShaping(new TerrainShapingPlanner().plan(footprint,
            (x, z) -> SettlementFixtures.land(64 + Math.floorMod(x, 2)), TerrainShapingSettings.defaults()).plan());
        blueprint.markGenerated(99L);
        data.growth().put(blueprint);
        final SettlementStreetNetwork streets = new SettlementStreetNetwork();
        final UUID streetId = UUID.nameUUIDFromBytes("v7-street".getBytes());
        streets.put(new SettlementStreetSegment(streetId, List.of(new BlockPos(0, 64, 28), new BlockPos(0, 64, 27)), 2, "gate-to-plaza"));
        final SettlementLayoutPlan layout = new SettlementLayoutPlan(FIRST, "Colonial", streets);
        layout.markStreetChunk(streetId, 42L);
        data.growth().layoutOrCreate(FIRST, () -> layout);
        data.growth().stateOrCreate(FIRST).decision("starter template village-green [Colonial]");
        return data;
    }
}
