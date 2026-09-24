package com.minecolonies.kingdoms.integration.minecolonies.economy;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.kingdoms.colony.ColonyMetrics;
import com.minecolonies.kingdoms.colony.ColonyMetricsProvider;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Optional;

public final class MineColoniesColonyMetricsAdapter implements ColonyMetricsProvider
{
    @Override
    public Optional<ColonyMetrics> observe(final ServerLevel level, final NPCColonyData colonyData)
    {
        if (colonyData.mineColoniesColonyId().isEmpty())
        {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, colonyData.dimension());
        final IColony colony = IColonyManager.getInstance().getColonyByDimension(
            colonyData.mineColoniesColonyId().getAsInt(), dimension);
        if (colony == null)
        {
            return Optional.empty();
        }

        final int population = colony.getCitizenManager().getCurrentCitizenCount();
        final int workers = (int) colony.getCitizenManager().getCitizens().stream()
            .map(ICitizenData::getJob)
            .filter(job -> job != null && !job.isGuard())
            .count();
        final int soldiers = (int) colony.getCitizenManager().getCitizens().stream()
            .map(ICitizenData::getJob)
            .filter(job -> job != null && job.isGuard())
            .count();
        return Optional.of(new ColonyMetrics(
            population,
            workers,
            soldiers,
            colony.getCitizenManager().getMaxCitizens()));
    }
}
