package com.minecolonies.kingdoms.citizen;

public record CitizenSettings(boolean enabled, int materializationRadius, int dematerializationRadius,
    int maxPerSettlement, int maxGlobal, int maxPerPlayer, int updateIntervalTicks, int maxRosterPerSettlement,
    int spawnsPerCycle, int respawnCooldownTicks, int stuckTimeoutTicks)
{
    public CitizenSettings
    {
        if (materializationRadius < 8 || dematerializationRadius <= materializationRadius || maxPerSettlement < 0
            || maxGlobal < 0 || maxPerPlayer < 0 || updateIntervalTicks < 1 || maxRosterPerSettlement < 1
            || spawnsPerCycle < 1 || respawnCooldownTicks < 0 || stuckTimeoutTicks < 20)
            throw new IllegalArgumentException("Invalid citizen settings");
    }

    public static CitizenSettings defaults()
    {
        return new CitizenSettings(true, 64, 96, 12, 64, 24, 20, 16, 2, 1200, 160);
    }
}
