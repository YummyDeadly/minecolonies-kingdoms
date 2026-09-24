package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.trade.TradeManager;
import com.minecolonies.kingdoms.trade.TradeMatcher;
import com.minecolonies.kingdoms.trade.TradeSettings;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeRestartSafetyTest
{
    private static final TradeSettings SETTINGS = new TradeSettings(8, 4_096.0D, 1L, 300L, 100L, 0.0D, 3, 0.0D);

    @Test
    void inTransitCargoSurvivesRestartAndIsDeliveredExactlyOnce()
    {
        final EconomyManager economy = new EconomyManager();
        final TradeManager manager = new TradeManager(economy, new TradeMatcher(), (offer, demand, lookup) -> true);
        final KingdomsSavedData data = scenario(economy);

        assertEquals(1, manager.runMatching(data, 10L, SETTINGS).shipmentsPlanned());
        assertEquals(1, manager.processShipments(data, 10L, SETTINGS).departed());
        final NPCColonyData exporter = data.colonies().stream().filter(colony -> colony.name().equals("Greyholm")).findFirst().orElseThrow();
        assertEquals(1_700L, exporter.economy().resource(EconomicResource.FOOD).stockpile().amount());

        final CompoundTag serialized = data.save(new CompoundTag(), null);
        final KingdomsSavedData restarted = KingdomsSavedData.load(serialized, null);
        final TradeShipment shipment = restarted.tradeLedger().shipments().iterator().next();
        assertEquals(TradeShipmentStatus.IN_TRANSIT, shipment.status());
        assertEquals(0, manager.processShipments(restarted, 109L, SETTINGS).delivered());
        assertEquals(1, manager.processShipments(restarted, 110L, SETTINGS).delivered());
        assertEquals(0, manager.processShipments(restarted, 111L, SETTINGS).delivered());

        final NPCColonyData importer = restarted.colonies().stream()
            .filter(colony -> colony.name().equals("Ravenport")).findFirst().orElseThrow();
        assertEquals(400L, importer.economy().resource(EconomicResource.FOOD).stockpile().amount());
        assertEquals(TradeShipmentStatus.DELIVERED, shipment.status());
    }

    @Test
    void deletingDestinationReturnsInTransitCargoToOrigin()
    {
        final EconomyManager economy = new EconomyManager();
        final TradeManager manager = new TradeManager(economy, new TradeMatcher(), (offer, demand, lookup) -> true);
        final KingdomsSavedData data = scenario(economy);
        manager.runMatching(data, 10L, SETTINGS);
        manager.processShipments(data, 10L, SETTINGS);
        final NPCColonyData exporter = data.colonies().stream().filter(colony -> colony.name().equals("Greyholm")).findFirst().orElseThrow();
        final NPCColonyData importer = data.colonies().stream().filter(colony -> colony.name().equals("Ravenport")).findFirst().orElseThrow();

        manager.handleColonyDeletion(data, importer.id(), 20L);
        data.removeColony(importer.id());

        assertEquals(2_000L, exporter.economy().resource(EconomicResource.FOOD).stockpile().amount());
        assertEquals(TradeShipmentStatus.FAILED, data.tradeLedger().shipments().iterator().next().status());
    }

    private static KingdomsSavedData scenario(final EconomyManager economy)
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final NPCColonyData exporter = colony("Greyholm", new BlockPos(0, 64, 0));
        final NPCColonyData importer = colony("Ravenport", new BlockPos(100, 64, 0));
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
        return data;
    }

    private static NPCColonyData colony(final String name, final BlockPos center)
    {
        return NPCColonyData.createStrategicNpc(
            UUID.randomUUID(),
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            center,
            name,
            UUID.randomUUID(),
            0L);
    }

    private static Faction faction(final NPCColonyData colony)
    {
        final Faction faction = new Faction(colony.factionId(), colony.name(), FactionType.CITY_STATE);
        faction.setCapitalColonyId(colony.id());
        return faction;
    }
}
