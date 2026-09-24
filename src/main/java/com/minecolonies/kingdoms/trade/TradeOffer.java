package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record TradeOffer(
    UUID colonyId,
    UUID factionId,
    ResourceLocation dimension,
    BlockPos center,
    EconomicResource resource,
    long amount,
    int priority)
{
    public TradeOffer
    {
        Objects.requireNonNull(colonyId, "colonyId");
        Objects.requireNonNull(factionId, "factionId");
        Objects.requireNonNull(dimension, "dimension");
        center = Objects.requireNonNull(center, "center").immutable();
        Objects.requireNonNull(resource, "resource");
        if (amount <= 0L)
        {
            throw new IllegalArgumentException("Offer amount must be positive");
        }
    }
}
