package com.minecolonies.kingdoms.world.settlement.growth;

public enum SettlementBuildingStatus
{
    PLANNED,
    READY,
    GENERATING,
    COMPLETED,
    BLOCKED,
    FAILED;

    public boolean contributesEffects()
    {
        return this == READY || this == GENERATING || this == COMPLETED;
    }
}
