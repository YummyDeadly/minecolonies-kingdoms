package com.minecolonies.kingdoms.contract;

/** Bounded contract configuration (see the {@code [contracts]} config section). */
public record ContractSettings(boolean enabled, int maxOpenPerSettlement, int maxActivePerPlayer, long offerLifetimeTicks,
    long contractDurationTicks, long offerRefreshTicks, long resourceCooldownTicks, long unacceptedCooldownTicks,
    int interactionRadius, int failPenalty, int abandonPenalty, int killPenalty, long historyRetentionTicks, int maxHistory)
{
    public ContractSettings
    {
        if (maxOpenPerSettlement < 0 || maxActivePerPlayer < 0 || offerLifetimeTicks <= 0L || contractDurationTicks <= 0L
            || offerRefreshTicks < 0L || resourceCooldownTicks < 0L || unacceptedCooldownTicks < 0L || interactionRadius <= 0
            || failPenalty < 0 || abandonPenalty < 0 || killPenalty < 0 || historyRetentionTicks < 0L || maxHistory < 0)
            throw new IllegalArgumentException("Invalid contract settings");
    }

    public static ContractSettings defaults()
    {
        return new ContractSettings(true, 3, 3, 48_000L, 72_000L, 2_400L, 12_000L, 24_000L, 96, 6, 4, 10, 168_000L, 256);
    }
}
