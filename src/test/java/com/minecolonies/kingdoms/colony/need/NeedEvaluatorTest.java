package com.minecolonies.kingdoms.colony.need;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.ResourceEconomy;
import com.minecolonies.kingdoms.economy.ResourceFlow;
import com.minecolonies.kingdoms.economy.ResourceStockpile;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedEvaluatorTest
{
    @Test
    void lowFoodCreatesFoodShortage()
    {
        final NPCColonyData colony = colony();
        colony.economy().set(EconomicResource.FOOD, new ResourceEconomy(
            new ResourceStockpile(4L, 20L), new ResourceFlow(0.0D, 0.0D)));

        final List<ColonyNeed> needs = new NeedEvaluator().evaluate(colony, 100L);

        assertTrue(needs.stream().anyMatch(need -> need.type() == NeedType.FOOD_SHORTAGE));
    }

    @Test
    void sufficientHousingCreatesNoHousingShortage()
    {
        final NPCColonyData colony = colony();
        colony.updatePopulation(10, 6, 0);
        colony.updateCapacities(11, 20);

        final List<ColonyNeed> needs = new NeedEvaluator().evaluate(colony, 100L);

        assertFalse(needs.stream().anyMatch(need -> need.type() == NeedType.HOUSING_SHORTAGE));
    }

    private static NPCColonyData colony()
    {
        return new NPCColonyData(
            UUID.randomUUID(),
            1,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            "Test Colony",
            UUID.randomUUID());
    }
}
