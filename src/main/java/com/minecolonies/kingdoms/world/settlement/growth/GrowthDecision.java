package com.minecolonies.kingdoms.world.settlement.growth;

public record GrowthDecision(SettlementBuildingType buildingType, String reason)
{
    public boolean plansBuilding() { return buildingType != null; }
    public static GrowthDecision blocked(final String reason) { return new GrowthDecision(null, reason); }
}
