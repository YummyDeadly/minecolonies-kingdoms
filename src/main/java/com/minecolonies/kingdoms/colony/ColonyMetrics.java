package com.minecolonies.kingdoms.colony;

public record ColonyMetrics(int population, int workers, int soldiers, int housingCapacity)
{
    public ColonyMetrics
    {
        if (population < 0 || workers < 0 || soldiers < 0 || housingCapacity < 0 || workers + soldiers > population)
        {
            throw new IllegalArgumentException("Invalid colony metrics");
        }
    }
}
