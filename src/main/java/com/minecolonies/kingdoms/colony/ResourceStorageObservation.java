package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.economy.ColonyEconomySnapshot;

import java.util.Objects;

public record ResourceStorageObservation(ColonyEconomySnapshot economy, int totalSlots)
{
    public ResourceStorageObservation
    {
        Objects.requireNonNull(economy, "economy");
        if (totalSlots < 0)
        {
            throw new IllegalArgumentException("totalSlots must be non-negative");
        }
    }
}
