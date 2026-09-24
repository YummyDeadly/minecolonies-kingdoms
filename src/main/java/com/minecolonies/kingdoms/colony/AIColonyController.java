package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.colony.decision.StrategicDecisionEvaluator;
import com.minecolonies.kingdoms.colony.need.NeedEvaluator;
import com.minecolonies.kingdoms.economy.EconomyUpdateResult;

import java.util.Objects;

public final class AIColonyController implements ColonyController
{
    private final NeedEvaluator needEvaluator;
    private final StrategicDecisionEvaluator decisionEvaluator;

    public AIColonyController(final NeedEvaluator needEvaluator, final StrategicDecisionEvaluator decisionEvaluator)
    {
        this.needEvaluator = Objects.requireNonNull(needEvaluator, "needEvaluator");
        this.decisionEvaluator = Objects.requireNonNull(decisionEvaluator, "decisionEvaluator");
    }

    @Override
    public ColonyControllerResult tick(final ColonyUpdateContext context)
    {
        final EconomyUpdateResult economy = ControllerSupport.observeAndUpdate(context);
        context.colony().replaceNeeds(needEvaluator.evaluate(context.colony(), context.gameTime()));
        context.colony().setLastDecision(decisionEvaluator.decide(context.colony().needs(), context.gameTime()));
        return new ColonyControllerResult(economy.updated(), true);
    }
}
