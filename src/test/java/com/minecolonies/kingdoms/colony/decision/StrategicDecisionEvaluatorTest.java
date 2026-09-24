package com.minecolonies.kingdoms.colony.decision;

import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.economy.EconomicResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategicDecisionEvaluatorTest
{
    @Test
    void highFoodShortageSelectsFoodProduction()
    {
        final ColonyNeed need = new ColonyNeed(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 4.0D, 10.0D, 20L);

        final StrategicDecision decision = new StrategicDecisionEvaluator().decide(List.of(need), 40L);

        assertEquals(StrategicActionType.PRODUCE_MORE_FOOD, decision.action());
        assertEquals(EconomicResource.FOOD, decision.resource());
        assertEquals(NeedType.FOOD_SHORTAGE, decision.reason());
    }
}
