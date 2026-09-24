package com.minecolonies.kingdoms.colony.decision;

import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.economy.EconomicResource;

import java.util.Comparator;
import java.util.List;

public final class StrategicDecisionEvaluator
{
    private static final Comparator<ColonyNeed> PRIORITY = Comparator
        .comparing(ColonyNeed::severity)
        .thenComparing(ColonyNeed::shortageRatio)
        .thenComparing(need -> -need.type().ordinal());

    public StrategicDecision decide(final List<ColonyNeed> needs, final long gameTime)
    {
        return needs.stream().max(PRIORITY)
            .map(need -> fromNeed(need, gameTime))
            .orElseGet(() -> StrategicDecision.none(gameTime));
    }

    private static StrategicDecision fromNeed(final ColonyNeed need, final long gameTime)
    {
        final EconomicResource resource = resourceFor(need.type());
        if (resource != null && need.severity() == NeedSeverity.CRITICAL)
        {
            return new StrategicDecision(StrategicActionType.IMPORT_RESOURCE, resource, need.type(), gameTime);
        }
        return new StrategicDecision(actionFor(need.type()), resource, need.type(), gameTime);
    }

    private static StrategicActionType actionFor(final NeedType type)
    {
        return switch (type)
        {
            case FOOD_SHORTAGE -> StrategicActionType.PRODUCE_MORE_FOOD;
            case WOOD_SHORTAGE -> StrategicActionType.PRODUCE_MORE_WOOD;
            case STONE_SHORTAGE -> StrategicActionType.PRODUCE_MORE_STONE;
            case IRON_SHORTAGE -> StrategicActionType.PRODUCE_MORE_IRON;
            case HOUSING_SHORTAGE -> StrategicActionType.EXPAND_HOUSING;
            case STORAGE_SHORTAGE -> StrategicActionType.EXPAND_STORAGE;
            case LABOR_SHORTAGE -> StrategicActionType.NONE;
        };
    }

    private static EconomicResource resourceFor(final NeedType type)
    {
        return switch (type)
        {
            case FOOD_SHORTAGE -> EconomicResource.FOOD;
            case WOOD_SHORTAGE -> EconomicResource.WOOD;
            case STONE_SHORTAGE -> EconomicResource.STONE;
            case IRON_SHORTAGE -> EconomicResource.IRON;
            default -> null;
        };
    }
}
