package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.ResourceEconomy;
import com.minecolonies.kingdoms.economy.ResourceFlow;
import com.minecolonies.kingdoms.economy.ResourceStockpile;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NPCColonyDataTest
{
    @Test
    void aggregatePopulationRoundTripsThroughNbt()
    {
        final NPCColonyData colony = new NPCColonyData(
            UUID.randomUUID(),
            14,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            "Ravenport",
            UUID.randomUUID());
        colony.updatePopulation(74, 51, 12);
        colony.setSimulationMode(SimulationMode.ACTIVE);
        colony.setLastSimulationGameTime(42_000L);

        final NPCColonyData loaded = NPCColonyData.load(colony.save());

        assertEquals(74, loaded.population());
        assertEquals(51, loaded.workers());
        assertEquals(12, loaded.soldiers());
        assertEquals(SimulationMode.ACTIVE, loaded.simulationMode());
        assertEquals(42_000L, loaded.lastSimulationGameTime());
    }

    @Test
    void invalidPopulationAggregateIsRejected()
    {
        final NPCColonyData colony = new NPCColonyData(
            UUID.randomUUID(),
            1,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            "Test",
            UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> colony.updatePopulation(10, 8, 4));
    }

    @Test
    void economyRoundTripsThroughNbt()
    {
        final NPCColonyData colony = new NPCColonyData(
            UUID.randomUUID(), 2,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            "Market", UUID.randomUUID());
        colony.economy().set(EconomicResource.FOOD, new ResourceEconomy(
            new ResourceStockpile(45L, 30L), new ResourceFlow(12.0D, 8.0D)));

        final NPCColonyData loaded = NPCColonyData.load(colony.save());
        final ResourceEconomy food = loaded.economy().resource(EconomicResource.FOOD);

        assertEquals(45L, food.stockpile().amount());
        assertEquals(30L, food.stockpile().desiredReserve());
        assertEquals(4.0D, food.flow().netFlowPerDay());
    }

    @Test
    void abstractNpcHasNoMineColoniesAssociationAndRoundTrips()
    {
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(
            UUID.randomUUID(),
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            new net.minecraft.core.BlockPos(12, 70, -4),
            "Greyholm",
            UUID.randomUUID(),
            123L);

        final NPCColonyData loaded = NPCColonyData.load(colony.save());

        assertEquals(ColonyKind.NPC_ABSTRACT, loaded.kind());
        assertTrue(loaded.mineColoniesColonyId().isEmpty());
        assertEquals(123L, loaded.createdAt());
    }
}
