package com.minecolonies.kingdoms.economy;

import java.util.Objects;

public record ResourceEconomy(ResourceStockpile stockpile, ResourceFlow flow)
{
    public ResourceEconomy
    {
        Objects.requireNonNull(stockpile, "stockpile");
        Objects.requireNonNull(flow, "flow");
    }

    public static ResourceEconomy empty()
    {
        return new ResourceEconomy(new ResourceStockpile(0L, 0L), new ResourceFlow(0.0D, 0.0D));
    }
}
