package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.economy.ColonyEconomySnapshot;
import com.minecolonies.kingdoms.economy.EconomyUpdateResult;

import java.util.Optional;

final class ControllerSupport
{
    private ControllerSupport()
    {
    }

    static EconomyUpdateResult observeAndUpdate(final ColonyUpdateContext context)
    {
        context.metricsProvider().observe(context.level(), context.colony()).ifPresent(metrics -> {
            context.colony().updatePopulation(metrics.population(), metrics.workers(), metrics.soldiers());
            context.colony().updateCapacities(metrics.housingCapacity(), context.colony().storageCapacity());
        });

        Optional<ColonyEconomySnapshot> physicalSnapshot = Optional.empty();
        if (context.colony().simulationMode() == SimulationMode.ACTIVE)
        {
            final Optional<ResourceStorageObservation> observation = context.resourceStorage().observe(context.level(), context.colony());
            if (observation.isPresent())
            {
                context.colony().updateCapacities(context.colony().housingCapacity(), observation.orElseThrow().totalSlots());
                physicalSnapshot = Optional.of(observation.orElseThrow().economy());
            }
        }

        final EconomyUpdateResult result = context.economyManager().update(
            context.colony(),
            context.gameTime(),
            context.economyIntervalTicks(),
            physicalSnapshot);
        context.colony().setLastStrategicUpdate(context.gameTime());
        context.colony().setLastSimulationGameTime(context.gameTime());
        return result;
    }
}
