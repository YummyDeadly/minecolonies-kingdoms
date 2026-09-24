package com.minecolonies.kingdoms.world.settlement;

public enum SettlementType
{
    VILLAGE(12, 22, 28, 2),
    TOWN(24, 42, 40, 3),
    TRADING_TOWN(28, 48, 44, 4),
    CASTLE(36, 60, 52, 4),
    FORT(20, 36, 36, 3);

    private final int minimumPopulation;
    private final int maximumPopulation;
    private final int footprintRadius;
    private final int desiredConnections;

    SettlementType(final int minimumPopulation, final int maximumPopulation, final int footprintRadius,
        final int desiredConnections)
    {
        this.minimumPopulation = minimumPopulation;
        this.maximumPopulation = maximumPopulation;
        this.footprintRadius = footprintRadius;
        this.desiredConnections = desiredConnections;
    }

    public int minimumPopulation() { return minimumPopulation; }
    public int maximumPopulation() { return maximumPopulation; }
    public int footprintRadius() { return footprintRadius; }
    public int desiredConnections() { return desiredConnections; }
}
