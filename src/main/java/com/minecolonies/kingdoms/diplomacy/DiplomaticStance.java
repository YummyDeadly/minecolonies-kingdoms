package com.minecolonies.kingdoms.diplomacy;

/**
 * Stance between two factions, derived from the persisted symmetric value in {@code Faction.relations()}
 * (-100..100). Tense and hostile factions do not trade with each other; wars are a later phase.
 */
public enum DiplomaticStance
{
    HOSTILE(-100, "Hostile"),
    TENSE(-59, "Tense"),
    NEUTRAL(-19, "Neutral"),
    FRIENDLY(20, "Friendly"),
    ALLIED(60, "Allied");

    private final int minimum;
    private final String displayName;

    DiplomaticStance(final int minimum, final String displayName)
    {
        this.minimum = minimum;
        this.displayName = displayName;
    }

    public static DiplomaticStance of(final int value)
    {
        final DiplomaticStance[] stances = values();
        for (int index = stances.length - 1; index > 0; index--)
            if (value >= stances[index].minimum) return stances[index];
        return HOSTILE;
    }

    public int minimum() { return minimum; }
    public String displayName() { return displayName; }
    public boolean allowsTrade() { return compareTo(NEUTRAL) >= 0; }
}
