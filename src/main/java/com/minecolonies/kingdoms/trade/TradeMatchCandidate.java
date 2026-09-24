package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.economy.EconomicResource;

import java.util.Objects;
import java.util.UUID;

public record TradeMatchCandidate(
    UUID originColonyId,
    UUID destinationColonyId,
    EconomicResource resource,
    long amount,
    double distance,
    int priority)
{
    public TradeMatchCandidate
    {
        Objects.requireNonNull(originColonyId, "originColonyId");
        Objects.requireNonNull(destinationColonyId, "destinationColonyId");
        Objects.requireNonNull(resource, "resource");
        if (originColonyId.equals(destinationColonyId)
            || amount <= 0L
            || !Double.isFinite(distance)
            || distance < 0.0D)
        {
            throw new IllegalArgumentException("Invalid trade match candidate");
        }
    }

    public TradeRouteKey routeKey()
    {
        return new TradeRouteKey(originColonyId, destinationColonyId, resource);
    }
}
