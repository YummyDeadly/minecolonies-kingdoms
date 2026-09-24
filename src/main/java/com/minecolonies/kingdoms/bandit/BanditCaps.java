package com.minecolonies.kingdoms.bandit;

/** Pure physical-bandit cap arithmetic (per encounter, per observing player, global). */
public final class BanditCaps
{
    private BanditCaps() {}

    /** How many bandits may appear now: never negative, never above any remaining cap or the encounter's strength. */
    public static int allowance(final int remainingStrength, final int perEncounter, final int globalLeft, final int playerLeft)
    {
        return Math.max(0, Math.min(Math.min(remainingStrength, perEncounter), Math.min(globalLeft, playerLeft)));
    }
}
