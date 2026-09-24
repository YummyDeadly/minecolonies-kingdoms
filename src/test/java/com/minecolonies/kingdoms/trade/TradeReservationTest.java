package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeReservationTest
{
    @Test
    void plannedShipmentReducesRemainingExportCapacity()
    {
        final EconomyManager economy = new EconomyManager();
        final NPCColonyData exporter = NPCColonyData.createStrategicNpc(
            UUID.randomUUID(),
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            BlockPos.ZERO,
            "Exporter",
            UUID.randomUUID(),
            0L);
        economy.setStockpile(exporter, EconomicResource.FOOD, 1_000L);
        economy.setDesiredReserve(exporter, EconomicResource.FOOD, 500L);
        economy.setProduction(exporter, EconomicResource.FOOD, 100.0D);
        economy.setConsumption(exporter, EconomicResource.FOOD, 0.0D);
        final TradeLedger ledger = new TradeLedger();
        final UUID routeId = UUID.randomUUID();
        ledger.putShipment(new TradeShipment(
            UUID.randomUUID(), routeId, exporter.id(), UUID.randomUUID(), EconomicResource.FOOD, 300L, 0L));

        final long committed = ledger.reservedForExport(exporter.id(), EconomicResource.FOOD);
        final long remaining = economy.availableForExport(exporter, EconomicResource.FOOD, committed, 0.0D);

        assertEquals(300L, committed);
        assertEquals(200L, remaining);
        assertEquals(300L, ledger.committedForImport(
            ledger.shipments().iterator().next().destinationColonyId(), EconomicResource.FOOD));
    }
}
