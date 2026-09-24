package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementPlotPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GrowthMigrationV6Test
{
    @Test
    void versionFiveMigrationAddsEmptyGrowthAndPreservesAllExistingIdsAndTiming()
    {
        final ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        final UUID firstId = UUID.nameUUIDFromBytes("v5-first".getBytes());
        final UUID secondId = UUID.nameUUIDFromBytes("v5-second".getBytes());
        final SettlementRecord first = settlement(firstId, dimension, 0);
        final SettlementRecord second = settlement(secondId, dimension, 200);
        final UUID roadId = UUID.nameUUIDFromBytes("v5-road".getBytes());
        final RoadRecord road = new RoadRecord(roadId, firstId, secondId, dimension, RoadType.STONE,
            List.of(first.gate(), second.gate()), RoadStatus.GENERATING, 1);
        final UUID shipmentId = UUID.nameUUIDFromBytes("v5-shipment".getBytes());
        final UUID routeId = UUID.nameUUIDFromBytes("v5-route".getBytes());
        final TradeShipment shipment = new TradeShipment(shipmentId, routeId, firstId, secondId, EconomicResource.FOOD, 50, 10);
        shipment.depart(20, 777);
        final KingdomsSavedData source = new KingdomsSavedData();
        source.settlements().put(first); source.settlements().put(second); source.roads().put(road);
        source.tradeLedger().putShipment(shipment);
        final CompoundTag v5 = source.save(new CompoundTag(), null);
        v5.putInt("dataVersion", 5);
        v5.remove("growth");

        final KingdomsSavedData loaded = KingdomsSavedData.load(v5, null);

        assertEquals(KingdomsSavedData.DATA_VERSION, loaded.save(new CompoundTag(), null).getInt("dataVersion"));
        assertTrue(loaded.growth().states().isEmpty());
        assertTrue(loaded.growth().buildings().isEmpty());
        assertEquals(firstId, loaded.settlements().get(firstId).orElseThrow().id());
        assertEquals(roadId, loaded.roads().get(roadId).orElseThrow().id());
        final TradeShipment restored = loaded.tradeLedger().shipment(shipmentId).orElseThrow();
        assertEquals(shipmentId, restored.id());
        assertEquals(777, restored.travelDurationTicks());
        assertEquals(797, restored.arrivalAt());
    }

    @Test
    void growthStateAndBuildingRoundTripWithoutDuplicate()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final UUID settlementId = UUID.nameUUIDFromBytes("growth-roundtrip".getBytes());
        data.growth().stateOrCreate(settlementId).decision("housing shortage -> house");
        data.growth().stateOrCreate(settlementId).starterDistrictReady(com.minecolonies.kingdoms.world.settlement.template.SettlementDistrictPlanner.STARTER_PLANNER_VERSION);
        final UUID buildingId = SettlementPlotPlanner.stableBuildingId(settlementId, 0, SettlementBuildingType.HOUSE, 1);
        final SettlementBuildingRecord building = new SettlementBuildingRecord(buildingId, settlementId,
            SettlementBuildingType.HOUSE, new BlockPos(80, 64, 80), 90, 0, 1, 100, SettlementBuildingStatus.READY);
        building.markGenerated(123L);
        assertTrue(data.growth().put(building));

        final KingdomsSavedData loaded = KingdomsSavedData.load(data.save(new CompoundTag(), null), null);

        assertEquals("housing shortage -> house", loaded.growth().state(settlementId).orElseThrow().lastDecision());
        assertEquals(com.minecolonies.kingdoms.world.settlement.growth.StarterDistrictStatus.READY,
            loaded.growth().state(settlementId).orElseThrow().starterDistrictStatus());
        assertEquals(buildingId, loaded.growth().building(buildingId).orElseThrow().id());
        assertFalse(loaded.growth().building(buildingId).orElseThrow().needsGeneration(123L));
        assertFalse(loaded.growth().put(building));
    }

    private static SettlementRecord settlement(final UUID id, final ResourceLocation dimension, final int x)
    {
        return new SettlementRecord(id, "Legacy " + x, SettlementType.TOWN, dimension, new BlockPos(x, 64, 0), 0,
            new BlockPos(x + 40, 64, 0), UUID.nameUUIDFromBytes(("faction" + x).getBytes()), 24,
            SettlementPhysicalState.GENERATED, new SettlementRegion(dimension, x / 512, 0), null, 0);
    }
}
