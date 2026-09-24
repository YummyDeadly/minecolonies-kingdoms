package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;

import java.util.Comparator;
import java.util.List;

public final class SettlementGrowthEvaluator
{
    private static final List<NeedType> PRIORITY = List.of(
        NeedType.HOUSING_SHORTAGE, NeedType.STORAGE_SHORTAGE, NeedType.FOOD_SHORTAGE,
        NeedType.WOOD_SHORTAGE, NeedType.STONE_SHORTAGE, NeedType.IRON_SHORTAGE, NeedType.LABOR_SHORTAGE);
    private static final List<SettlementBuildingType> FOUNDATION = List.of(
        SettlementBuildingType.HOUSE, SettlementBuildingType.FARM, SettlementBuildingType.STOREHOUSE,
        SettlementBuildingType.LUMBER_YARD, SettlementBuildingType.QUARRY, SettlementBuildingType.SMITHY,
        SettlementBuildingType.MARKET, SettlementBuildingType.CIVIC);

    public GrowthDecision decide(final SettlementRecord settlement, final NPCColonyData colony,
        final List<SettlementBuildingRecord> buildings)
    {
        return decide(settlement, colony, buildings, type -> true);
    }

    /**
     * @param available whether the settlement's persisted style can actually build a type; a role without a
     *                  blueprint in the style is treated like a saturated one instead of blocking growth forever.
     */
    public GrowthDecision decide(final SettlementRecord settlement, final NPCColonyData colony,
        final List<SettlementBuildingRecord> buildings, final java.util.function.Predicate<SettlementBuildingType> available)
    {
        final ColonyNeed need = colony.needs().stream()
            .filter(value -> value.type() != NeedType.LABOR_SHORTAGE)
            .max(Comparator.comparing(ColonyNeed::severity)
                .thenComparing(ColonyNeed::shortageRatio)
                .thenComparingInt(value -> -PRIORITY.indexOf(value.type())))
            .orElse(null);
        String saturated = null;
        if (need != null)
        {
            final SettlementBuildingType type = forNeed(need.type());
            final String reason = need.severity().name().toLowerCase() + " " + need.type().name().toLowerCase();
            if (type != null && type.allowedFor(settlement.type()))
            {
                if (!available.test(type)) saturated = reason + " (no " + type.name().toLowerCase() + " blueprint in style)";
                else if (!saturated(type, buildings)) return new GrowthDecision(type, reason);
                else saturated = reason + " (" + type.name().toLowerCase() + " saturated)";
            }
        }
        final var tools = colony.economy().resource(EconomicResource.TOOLS).stockpile();
        if (tools.reserveShortage() > 0L && SettlementBuildingType.SMITHY.allowedFor(settlement.type())
            && available.test(SettlementBuildingType.SMITHY) && !saturated(SettlementBuildingType.SMITHY, buildings))
            return new GrowthDecision(SettlementBuildingType.SMITHY, "tools reserve shortage");
        for (final SettlementBuildingType type : FOUNDATION)
        {
            if (type.allowedFor(settlement.type()) && available.test(type) && buildings.stream().noneMatch(value -> value.type() == type))
                return new GrowthDecision(type, saturated == null ? "missing " + type.role()
                    : saturated + " -> missing " + type.role());
        }
        if (saturated != null)
        {
            // Every foundation role exists and the pressing need's building is saturated: grow the least represented
            // allowed role instead of repeating one blueprint (a structural deficit is not fixed by a 10th farm).
            final SettlementBuildingType least = FOUNDATION.stream().filter(type -> type.allowedFor(settlement.type()))
                .filter(available)
                .filter(type -> !saturated(type, buildings))
                .min(Comparator.comparingLong((SettlementBuildingType type) -> count(type, buildings))
                    .thenComparingInt(FOUNDATION::indexOf)).orElse(null);
            if (least != null) return new GrowthDecision(least, saturated + " -> diversify " + least.role());
            return GrowthDecision.blocked(saturated + "; every allowed role is saturated");
        }
        return GrowthDecision.blocked("no unmet strategic growth need");
    }

    /** At most 2 + total/4 buildings of one type, so growth stays varied while still answering needs first. */
    static boolean saturated(final SettlementBuildingType type, final List<SettlementBuildingRecord> buildings)
    {
        return count(type, buildings) >= 2L + buildings.size() / 4;
    }

    private static long count(final SettlementBuildingType type, final List<SettlementBuildingRecord> buildings)
    {
        return buildings.stream().filter(value -> value.type() == type).count();
    }

    public boolean populationAllowed(final NPCColonyData colony)
    {
        final var food = colony.economy().resource(EconomicResource.FOOD);
        return colony.housingCapacity() > colony.population()
            && food.stockpile().amount() >= food.stockpile().desiredReserve()
            && food.flow().netFlowPerDay() >= 0.0D
            && colony.needs().stream().noneMatch(value -> value.severity() == NeedSeverity.CRITICAL);
    }

    /**
     * The one population rule (used by growth and by Phase 11 settler events): room below the stage capacity and the
     * global hard cap, housing, food, and no critical need.
     */
    public static boolean populationRoom(final SettlementGrowthEvaluator evaluator, final NPCColonyData colony, final SettlementGrowthStage stage,
        final SettlementRecord settlement, final int hardCap)
    {
        final int stageCapacity = settlement.type().maximumPopulation() + stage.ordinal() * 8;
        return colony.population() < Math.min(hardCap, stageCapacity) && evaluator.populationAllowed(colony);
    }

    /** One new inhabitant if {@link #populationRoom} allows it; returns whether one arrived. */
    public static boolean growPopulation(final SettlementGrowthEvaluator evaluator, final NPCColonyData colony, final SettlementGrowthStage stage,
        final SettlementRecord settlement, final int hardCap)
    {
        if (!populationRoom(evaluator, colony, stage, settlement, hardCap)) return false;
        final int nextPopulation = colony.population() + 1;
        final int desiredWorkers = Math.min(nextPopulation - colony.soldiers(), Math.max(colony.workers(), nextPopulation * 2 / 3));
        colony.updatePopulation(nextPopulation, desiredWorkers, colony.soldiers());
        return true;
    }

    private static SettlementBuildingType forNeed(final NeedType type)
    {
        return switch (type)
        {
            case HOUSING_SHORTAGE -> SettlementBuildingType.HOUSE;
            case STORAGE_SHORTAGE -> SettlementBuildingType.STOREHOUSE;
            case FOOD_SHORTAGE -> SettlementBuildingType.FARM;
            case WOOD_SHORTAGE -> SettlementBuildingType.LUMBER_YARD;
            case STONE_SHORTAGE -> SettlementBuildingType.QUARRY;
            case IRON_SHORTAGE -> SettlementBuildingType.SMITHY;
            case LABOR_SHORTAGE -> null;
        };
    }
}
