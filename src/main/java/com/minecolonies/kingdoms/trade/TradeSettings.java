package com.minecolonies.kingdoms.trade;

public record TradeSettings(
    int maxRoutesPerColony,
    double maxDistance,
    long minimumShipmentAmount,
    long maximumShipmentAmount,
    long baseTravelTicks,
    double travelTicksPerBlock,
    int routePauseGraceCycles,
    double exportSafetyBufferPercent)
{
    public TradeSettings
    {
        if (maxRoutesPerColony <= 0
            || !Double.isFinite(maxDistance) || maxDistance <= 0.0D
            || minimumShipmentAmount <= 0L
            || maximumShipmentAmount < minimumShipmentAmount
            || baseTravelTicks <= 0L
            || !Double.isFinite(travelTicksPerBlock) || travelTicksPerBlock < 0.0D
            || routePauseGraceCycles <= 0
            || !Double.isFinite(exportSafetyBufferPercent) || exportSafetyBufferPercent < 0.0D)
        {
            throw new IllegalArgumentException("Invalid trade settings");
        }
    }
}
