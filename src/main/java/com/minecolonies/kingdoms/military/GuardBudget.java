package com.minecolonies.kingdoms.military;

/**
 * Pure physical-guard budget (Phase 9). Guards represent a few soldiers of a garrison near players; the garrison's
 * strength stays the only truth. All numbers are hard-capped.
 */
public final class GuardBudget
{
    private GuardBudget() {}

    /** Patrolling guards for a settlement: one, plus one per four soldiers, never more than the soldiers or the cap. */
    public static int patrolTarget(final int strength, final int perSettlementCap)
    {
        if (strength <= 0 || perSettlementCap <= 0) return 0;
        return Math.min(Math.min(strength, perSettlementCap), 1 + strength / 4);
    }

    /** Responders a garrison sends to a nearby bandit fight: none below two soldiers, one per three, capped. */
    public static int responders(final int strength, final int maxResponders)
    {
        if (strength < 2 || maxResponders <= 0) return 0;
        return Math.min(maxResponders, Math.max(1, strength / 3));
    }

    /** How many guards may appear now under the global and per-player caps (never negative). */
    public static int allowance(final int missing, final int global, final int globalCap, final int forPlayer, final int playerCap)
    {
        return Math.max(0, Math.min(missing, Math.min(globalCap - global, playerCap - forPlayer)));
    }

    /** Hysteresis between the two radii. */
    public static boolean active(final boolean wasActive, final double nearestPlayer, final SecuritySettings settings)
    {
        return wasActive ? nearestPlayer <= settings.guardDematerializationRadius() : nearestPlayer <= settings.guardMaterializationRadius();
    }
}
