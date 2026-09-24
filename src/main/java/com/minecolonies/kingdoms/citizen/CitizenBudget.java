package com.minecolonies.kingdoms.citizen;

/** Pure representation budget and activation policy (hysteresis between the two radii). */
public final class CitizenBudget
{
    private CitizenBudget() {}

    /**
     * Physical representatives for a settlement: grows with the square root of the population (about 5 at 5,
     * 9 at 20, 11 at 37, 17 at 100), never above the population, the roster, or the per-settlement cap.
     */
    public static int target(final int population, final int rosterSize, final int perSettlementCap)
    {
        if (population <= 0 || rosterSize <= 0 || perSettlementCap <= 0) return 0;
        final int curve = (int) Math.round(2.0D + 1.5D * Math.sqrt(population));
        return Math.min(Math.min(curve, population), Math.min(rosterSize, perSettlementCap));
    }

    /** How many more may be spawned this cycle under every cap. */
    public static int spawnAllowance(final int target, final int physicalHere, final int physicalGlobal,
        final int physicalForPlayer, final CitizenSettings settings)
    {
        final int missing = target - physicalHere;
        final int global = settings.maxGlobal() - physicalGlobal;
        final int player = settings.maxPerPlayer() - physicalForPlayer;
        return Math.max(0, Math.min(Math.min(missing, settings.spawnsPerCycle()), Math.min(global, player)));
    }

    public static boolean active(final boolean wasActive, final double nearestPlayerDistance, final CitizenSettings settings)
    {
        return wasActive ? nearestPlayerDistance <= settings.dematerializationRadius()
            : nearestPlayerDistance <= settings.materializationRadius();
    }
}
