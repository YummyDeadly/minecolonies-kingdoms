package com.minecolonies.kingdoms.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceEconomyTest
{
    @Test
    void productionAboveConsumptionProducesSurplus()
    {
        final ResourceFlow flow = new ResourceFlow(18.0D, 11.5D);

        assertEquals(6.5D, flow.netFlowPerDay());
        assertEquals(6.5D, flow.surplusPerDay());
        assertEquals(0.0D, flow.deficitPerDay());
    }

    @Test
    void consumptionAboveProductionProducesDeficit()
    {
        final ResourceFlow flow = new ResourceFlow(4.0D, 10.0D);

        assertEquals(-6.0D, flow.netFlowPerDay());
        assertEquals(0.0D, flow.surplusPerDay());
        assertEquals(6.0D, flow.deficitPerDay());
    }

    @Test
    void stockBelowReserveReportsShortage()
    {
        final ResourceStockpile stockpile = new ResourceStockpile(12L, 20L);

        assertEquals(8L, stockpile.reserveShortage());
        assertEquals(0L, stockpile.exportableStock());
    }
}
