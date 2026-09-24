package com.minecolonies.kingdoms.citizen;

/**
 * Deterministic daily schedule from Minecraft time of day (0 = 06:00, 6000 = noon, 12000 = 18:00, 18000 = midnight).
 * Guards keep their post at all hours; everyone else commutes in the morning, works or strolls during the day,
 * meets on the plaza or market in the evening, and goes home for the night. Representatives without an assigned
 * house spend the night "at home" too: their shelter is the plaza (inn, relatives), where they leave the world.
 */
public final class CitizenSchedule
{
    private CitizenSchedule() {}

    public static CitizenActivity activity(final CitizenRole role, final boolean hasWork, final boolean hasHome,
        final long dayTime, final long seed)
    {
        final int time = (int) Math.floorMod(dayTime, 24000L);
        final int personalShift = (int) Math.floorMod(seed, 600L);
        if (role == CitizenRole.GUARD) return time < 1000 + personalShift ? CitizenActivity.GO_TO_WORK : CitizenActivity.WORK;
        if (time >= 23000 || time < 500 + personalShift) return CitizenActivity.HOME;
        if (time < 1500 + personalShift) return hasWork ? CitizenActivity.GO_TO_WORK : CitizenActivity.GO_TO_PLAZA;
        if (time < 9000 + personalShift)
        {
            if (hasWork) return CitizenActivity.WORK;
            return Math.floorMod(seed + time / 2000, 2) == 0 ? CitizenActivity.WANDER : CitizenActivity.GO_TO_PLAZA;
        }
        if (time < 11500 + personalShift)
            return Math.floorMod(seed >>> 3, 3) == 0 ? CitizenActivity.VISIT_MARKET : CitizenActivity.SOCIALIZE;
        if (time < 12500 + personalShift) return hasHome ? CitizenActivity.RETURN_HOME : CitizenActivity.SOCIALIZE;
        return CitizenActivity.HOME;
    }

    /** Where an activity takes place. */
    public enum Destination { HOME, WORK, PLAZA, MARKET, STAY }

    public static Destination destination(final CitizenActivity activity)
    {
        return switch (activity)
        {
            case HOME, RETURN_HOME -> Destination.HOME;
            case GO_TO_WORK, WORK -> Destination.WORK;
            case GO_TO_PLAZA, WANDER, SOCIALIZE -> Destination.PLAZA;
            case VISIT_MARKET -> Destination.MARKET;
            case IDLE -> Destination.STAY;
        };
    }

    /** Activities during which a representative mills around its destination instead of standing still. */
    public static boolean wandersAtDestination(final CitizenActivity activity)
    {
        return activity == CitizenActivity.WORK || activity == CitizenActivity.WANDER
            || activity == CitizenActivity.SOCIALIZE || activity == CitizenActivity.VISIT_MARKET
            || activity == CitizenActivity.GO_TO_PLAZA || activity == CitizenActivity.IDLE;
    }
}
