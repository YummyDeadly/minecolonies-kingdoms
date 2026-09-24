package com.minecolonies.kingdoms.trade;

public record TradeStats(
    long activeRoutes,
    long pausedRoutes,
    long brokenRoutes,
    long plannedShipments,
    long shipmentsInTransit,
    long shipmentsDelivered,
    long shipmentsFailed,
    long resourcesMoved,
    long matchingCycles,
    double averageMatchingDurationNanos,
    long maxMatchingDurationNanos)
{
}
