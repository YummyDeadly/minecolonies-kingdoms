package com.minecolonies.kingdoms.caravan;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.ShipmentFailureReason;
import com.minecolonies.kingdoms.trade.ShipmentRepresentation;
import com.minecolonies.kingdoms.trade.TradeManager;
import com.minecolonies.kingdoms.trade.TradeMatcher;
import com.minecolonies.kingdoms.trade.TradeSettings;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaravanCoreTest
{
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final TradeSettings TRADE_SETTINGS =
        new TradeSettings(8, 4_096.0D, 1L, 300L, 200L, 0.0D, 3, 0.0D);
    private static final CaravanSettings CARAVAN_SETTINGS =
        new CaravanSettings(true, 128.0D, 192.0D, 8, 2, 10, 64.0D, 400, 40, false);

    @Test
    void representationConversionPreservesProgressAndRecomputesArrival()
    {
        final TradeShipment shipment = shipment();
        shipment.depart(100L, 200L);

        shipment.materialize(150L);
        assertEquals(ShipmentRepresentation.PHYSICAL, shipment.representation());
        assertEquals(0.25D, shipment.progressAt(999L), 0.0001D);
        shipment.updatePhysicalProgress(0.5D, 180L);
        shipment.dematerialize(180L);

        assertEquals(ShipmentRepresentation.ABSTRACT, shipment.representation());
        assertEquals(280L, shipment.arrivalAt());
        assertEquals(0.75D, shipment.progressAt(230L), 0.0001D);
    }

    @Test
    void hysteresisAndCapsControlMaterialization()
    {
        assertTrue(CaravanEligibility.shouldMaterialize(ShipmentRepresentation.ABSTRACT, 127.0D, 0, 0, CARAVAN_SETTINGS));
        assertFalse(CaravanEligibility.shouldMaterialize(ShipmentRepresentation.ABSTRACT, 129.0D, 0, 0, CARAVAN_SETTINGS));
        assertFalse(CaravanEligibility.shouldMaterialize(ShipmentRepresentation.ABSTRACT, 10.0D, 8, 0, CARAVAN_SETTINGS));
        assertFalse(CaravanEligibility.shouldMaterialize(ShipmentRepresentation.ABSTRACT, 10.0D, 0, 2, CARAVAN_SETTINGS));
        assertFalse(CaravanEligibility.shouldDematerialize(ShipmentRepresentation.PHYSICAL, 150.0D, CARAVAN_SETTINGS));
        assertTrue(CaravanEligibility.shouldDematerialize(ShipmentRepresentation.PHYSICAL, 193.0D, CARAVAN_SETTINGS));
    }

    @Test
    void directPathInterpolatesAndProjectsHalfway()
    {
        final DirectShipmentPath path = new DirectShipmentPath(OVERWORLD, new Vec3(0, 64, 0), new Vec3(100, 64, 0));
        assertEquals(new Vec3(50, 64, 0), path.positionAt(0.5D));
        assertEquals(0.5D, path.projectProgress(new Vec3(50, 80, 12)), 0.0001D);
        assertEquals(new Vec3(75, 64, 0), path.localWaypoint(0.5D, 25.0D));
    }

    @Test
    void restartNormalizesPhysicalShipmentAndClearsOrphanRegistry()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final TradeShipment shipment = shipment();
        shipment.depart(10L, 200L);
        shipment.materialize(60L);
        data.tradeLedger().putShipment(shipment);
        data.caravanLedger().put(new CaravanInstance(UUID.randomUUID(), shipment.id(), OVERWORLD,
            Vec3.ZERO, new Vec3(100, 0, 0), new Vec3(25, 0, 0), 0.25D, 60L, null, 0L));

        assertEquals(1, CaravanManager.normalizeAfterRestart(data, 80L));
        assertEquals(ShipmentRepresentation.ABSTRACT, shipment.representation());
        assertTrue(data.caravanLedger().instances().isEmpty());
        assertEquals(230L, shipment.arrivalAt());
    }

    @Test
    void destroyedCaravanLosesCargoAndCannotDeliver()
    {
        final Scenario scenario = scenario();
        scenario.manager.runMatching(scenario.data, 10L, TRADE_SETTINGS);
        scenario.manager.processShipments(scenario.data, 10L, TRADE_SETTINGS);
        final TradeShipment shipment = scenario.data.tradeLedger().shipments().iterator().next();
        shipment.materialize(20L);

        assertTrue(scenario.manager.failDestroyedCaravan(scenario.data, shipment.id()));
        assertFalse(scenario.manager.completePhysicalShipment(scenario.data, shipment.id(), 210L));
        assertEquals(TradeShipmentStatus.FAILED, shipment.status());
        assertEquals(ShipmentFailureReason.CARAVAN_DESTROYED, shipment.failureReason());
        assertEquals(1_700L, stock(scenario.exporter));
        assertEquals(100L, stock(scenario.importer));
    }

    @Test
    void abstractArrivalClockCannotDeliverWhileShipmentIsPhysical()
    {
        final Scenario scenario = scenario();
        scenario.manager.runMatching(scenario.data, 10L, TRADE_SETTINGS);
        scenario.manager.processShipments(scenario.data, 10L, TRADE_SETTINGS);
        final TradeShipment shipment = scenario.data.tradeLedger().shipments().iterator().next();
        shipment.materialize(20L);

        assertEquals(0, scenario.manager.processShipments(scenario.data, 10_000L, TRADE_SETTINGS).delivered());
        assertEquals(TradeShipmentStatus.IN_TRANSIT, shipment.status());
        assertEquals(100L, stock(scenario.importer));
    }

    @Test
    void physicalArrivalDepositsExactlyOnce()
    {
        final Scenario scenario = scenario();
        scenario.manager.runMatching(scenario.data, 10L, TRADE_SETTINGS);
        scenario.manager.processShipments(scenario.data, 10L, TRADE_SETTINGS);
        final TradeShipment shipment = scenario.data.tradeLedger().shipments().iterator().next();
        shipment.materialize(20L);

        assertTrue(scenario.manager.completePhysicalShipment(scenario.data, shipment.id(), 50L));
        assertFalse(scenario.manager.completePhysicalShipment(scenario.data, shipment.id(), 51L));
        assertEquals(400L, stock(scenario.importer));
        assertEquals(TradeShipmentStatus.DELIVERED, shipment.status());
    }

    private static TradeShipment shipment()
    {
        return new TradeShipment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            EconomicResource.FOOD, 50L, 0L);
    }

    private static Scenario scenario()
    {
        final EconomyManager economy = new EconomyManager();
        final TradeManager manager = new TradeManager(economy, new TradeMatcher(), (offer, demand, lookup) -> true);
        final KingdomsSavedData data = new KingdomsSavedData();
        final NPCColonyData exporter = colony("Exporter", 0);
        final NPCColonyData importer = colony("Importer", 100);
        data.putFaction(faction(exporter));
        data.putFaction(faction(importer));
        data.putColony(exporter);
        data.putColony(importer);
        economy.setStockpile(exporter, EconomicResource.FOOD, 2_000L);
        economy.setDesiredReserve(exporter, EconomicResource.FOOD, 800L);
        economy.setProduction(exporter, EconomicResource.FOOD, 300.0D);
        economy.setConsumption(exporter, EconomicResource.FOOD, 100.0D);
        economy.setStockpile(importer, EconomicResource.FOOD, 100L);
        economy.setDesiredReserve(importer, EconomicResource.FOOD, 900L);
        economy.setProduction(importer, EconomicResource.FOOD, 50.0D);
        economy.setConsumption(importer, EconomicResource.FOOD, 180.0D);
        return new Scenario(data, manager, exporter, importer);
    }

    private static NPCColonyData colony(final String name, final int x)
    {
        return NPCColonyData.createStrategicNpc(UUID.randomUUID(), OVERWORLD, new BlockPos(x, 64, 0),
            name, UUID.randomUUID(), 0L);
    }

    private static Faction faction(final NPCColonyData colony)
    {
        final Faction faction = new Faction(colony.factionId(), colony.name(), FactionType.CITY_STATE);
        faction.setCapitalColonyId(colony.id());
        return faction;
    }

    private static long stock(final NPCColonyData colony)
    {
        return colony.economy().resource(EconomicResource.FOOD).stockpile().amount();
    }

    private record Scenario(
        KingdomsSavedData data,
        TradeManager manager,
        NPCColonyData exporter,
        NPCColonyData importer) {}
}
