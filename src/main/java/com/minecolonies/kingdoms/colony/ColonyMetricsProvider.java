package com.minecolonies.kingdoms.colony;

import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

@FunctionalInterface
public interface ColonyMetricsProvider
{
    Optional<ColonyMetrics> observe(ServerLevel level, NPCColonyData colony);
}
