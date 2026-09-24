package com.minecolonies.kingdoms.economy;

public record ResourceStockpile(long amount, long desiredReserve)
{
    public ResourceStockpile
    {
        if (amount < 0L || desiredReserve < 0L)
        {
            throw new IllegalArgumentException("Stockpile values must be non-negative");
        }
    }

    public long reserveShortage()
    {
        return Math.max(0L, desiredReserve - amount);
    }

    public long exportableStock()
    {
        return Math.max(0L, amount - desiredReserve);
    }
}
