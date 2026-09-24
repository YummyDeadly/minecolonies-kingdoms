package com.minecolonies.kingdoms.diplomacy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pure, bounded rules for faction relations and player reputation. All values are integers in -100..100.
 *
 * <p>Relations change only through explicit events: first contact (a stable, pair-specific starting value that is
 * never tense: -19..30), trade deliveries between the pair (at most +2 per evaluation), and operator edits. There is
 * no autonomous drift and no effect from ordinary shared resource demand: in the abstract economy almost every young
 * settlement is short of everything, so such a rule would turn all neighbours against each other. Hostile events
 * (raids, wars) belong to later phases.
 */
public final class DiplomacyRules
{
    public static final int MINIMUM = -100;
    public static final int MAXIMUM = 100;
    public static final int TRADE_BONUS_CAP = 2;
    public static final int BASELINE_MINIMUM = -30;
    public static final int BASELINE_MAXIMUM = 30;
    /** First contact never starts below neutral; rivalry needs an explicit event. */
    public static final int FIRST_CONTACT_MINIMUM = -19;

    private DiplomacyRules() {}

    public static int clamp(final int value)
    {
        return Math.max(MINIMUM, Math.min(MAXIMUM, value));
    }

    /** Order-independent disposition of a pair (-30..30). */
    public static int baseline(final UUID first, final UUID second)
    {
        final UUID low = first.compareTo(second) <= 0 ? first : second;
        final UUID high = low == first ? second : first;
        final long hash = UUID.nameUUIDFromBytes(("kingdoms-diplomacy:" + low + ':' + high)
            .getBytes(StandardCharsets.UTF_8)).getLeastSignificantBits();
        return BASELINE_MINIMUM + (int) Math.floorMod(hash, (long) (BASELINE_MAXIMUM - BASELINE_MINIMUM + 1));
    }

    /** Starting relation at first contact: the disposition, but never tense. */
    public static int firstContact(final UUID first, final UUID second)
    {
        return Math.max(FIRST_CONTACT_MINIMUM, baseline(first, second));
    }

    /** Relation gain for the shipments delivered between a pair in one evaluation window. */
    public static int tradeDelta(final int deliveries)
    {
        return Math.min(TRADE_BONUS_CAP, Math.max(0, deliveries));
    }

    /**
     * Reputation that spreads to a related faction: allies share a quarter of any change; a faction hostile to the
     * one you helped resents a quarter of the gain. Small changes do not spread.
     */
    public static int spillover(final int delta, final int relation)
    {
        final DiplomaticStance stance = DiplomaticStance.of(relation);
        if (stance == DiplomaticStance.ALLIED) return delta / 4;
        if (stance == DiplomaticStance.HOSTILE && delta > 0) return -(delta / 4);
        return 0;
    }
}
