package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.economy.EconomyManager;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

public record ColonyUpdateContext(
    ServerLevel level,
    NPCColonyData colony,
    long gameTime,
    int economyIntervalTicks,
    ColonyMetricsProvider metricsProvider,
    ColonyResourceStorage resourceStorage,
    EconomyManager economyManager)
{
    public ColonyUpdateContext
    {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(colony, "colony");
        Objects.requireNonNull(metricsProvider, "metricsProvider");
        Objects.requireNonNull(resourceStorage, "resourceStorage");
        Objects.requireNonNull(economyManager, "economyManager");
        if (gameTime < 0L || economyIntervalTicks <= 0)
        {
            throw new IllegalArgumentException("Invalid update timing");
        }
    }
}
