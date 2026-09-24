package com.minecolonies.kingdoms.colony.need;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.ResourceEconomy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class NeedEvaluator
{
    private static final Map<EconomicResource, NeedType> RESOURCE_NEEDS = new EnumMap<>(EconomicResource.class);

    static
    {
        RESOURCE_NEEDS.put(EconomicResource.FOOD, NeedType.FOOD_SHORTAGE);
        RESOURCE_NEEDS.put(EconomicResource.WOOD, NeedType.WOOD_SHORTAGE);
        RESOURCE_NEEDS.put(EconomicResource.STONE, NeedType.STONE_SHORTAGE);
        RESOURCE_NEEDS.put(EconomicResource.IRON, NeedType.IRON_SHORTAGE);
    }

    public List<ColonyNeed> evaluate(final NPCColonyData colony, final long gameTime)
    {
        final Map<NeedType, Long> existingCreatedAt = new EnumMap<>(NeedType.class);
        colony.needs().forEach(need -> existingCreatedAt.put(need.type(), need.createdAt()));
        final List<ColonyNeed> needs = new ArrayList<>();

        RESOURCE_NEEDS.forEach((resource, type) -> {
            final ResourceEconomy economy = colony.economy().resource(resource);
            addIfShort(needs, type, economy.stockpile().amount(), economy.stockpile().desiredReserve(), gameTime, existingCreatedAt);
        });

        final int housingTarget = colony.population() == 0
            ? 0
            : colony.population() + Math.max(1, (int) Math.ceil(colony.population() * 0.10D));
        addIfShort(needs, NeedType.HOUSING_SHORTAGE, colony.housingCapacity(), housingTarget, gameTime, existingCreatedAt);

        final int storageTarget = colony.population() * 2;
        addIfShort(needs, NeedType.STORAGE_SHORTAGE, colony.storageCapacity(), storageTarget, gameTime, existingCreatedAt);

        final int laborTarget = (int) Math.ceil(colony.population() * 0.60D);
        addIfShort(needs, NeedType.LABOR_SHORTAGE, colony.workers(), laborTarget, gameTime, existingCreatedAt);
        return List.copyOf(needs);
    }

    private static void addIfShort(
        final List<ColonyNeed> needs,
        final NeedType type,
        final double current,
        final double target,
        final long gameTime,
        final Map<NeedType, Long> existingCreatedAt)
    {
        if (target <= 0.0D || current >= target)
        {
            return;
        }
        final double shortageRatio = (target - current) / target;
        needs.add(new ColonyNeed(
            type,
            NeedSeverity.fromShortageRatio(shortageRatio),
            Math.max(0.0D, current),
            target,
            existingCreatedAt.getOrDefault(type, gameTime)));
    }
}
