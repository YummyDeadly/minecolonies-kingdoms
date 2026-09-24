package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.economy.EconomicResource;

import java.util.Objects;
import java.util.UUID;

public record TradeRouteKey(UUID originColonyId, UUID destinationColonyId, EconomicResource resource)
{
    public TradeRouteKey
    {
        Objects.requireNonNull(originColonyId, "originColonyId");
        Objects.requireNonNull(destinationColonyId, "destinationColonyId");
        Objects.requireNonNull(resource, "resource");
        if (originColonyId.equals(destinationColonyId))
        {
            throw new IllegalArgumentException("A trade route requires two different colonies");
        }
    }
}
