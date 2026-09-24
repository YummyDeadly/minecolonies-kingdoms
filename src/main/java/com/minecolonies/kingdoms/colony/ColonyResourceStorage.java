package com.minecolonies.kingdoms.colony;

import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

@FunctionalInterface
public interface ColonyResourceStorage
{
    Optional<ResourceStorageObservation> observe(ServerLevel level, NPCColonyData colony);
}
