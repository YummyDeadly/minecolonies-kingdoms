package com.minecolonies.kingdoms.simulation;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.SimulationMode;
import net.minecraft.server.level.ServerLevel;

public final class SimulationModeResolver
{
    public SimulationMode resolve(
        final ServerLevel level,
        final NPCColonyData colony,
        final int activationRadius,
        final int deactivationRadius)
    {
        final int radius = colony.simulationMode() == SimulationMode.ACTIVE
            ? Math.max(deactivationRadius, activationRadius + 1)
            : activationRadius;
        final long radiusSquared = (long) radius * radius;
        final boolean playerNearby = level.players().stream()
            .filter(player -> !player.isSpectator())
            .anyMatch(player -> colony.center().distSqr(player.blockPosition()) <= radiusSquared);
        return playerNearby ? SimulationMode.ACTIVE : SimulationMode.ABSTRACT;
    }
}
