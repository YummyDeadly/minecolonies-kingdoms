package com.minecolonies.kingdoms.world.settlement.growth;

import java.util.UUID;

public record GrowthEvaluationResult(UUID settlementId, SettlementGrowthStage stage,
    UUID plannedBuildingId, SettlementBuildingType plannedBuildingType, boolean populationGrew, String detail)
{
}
