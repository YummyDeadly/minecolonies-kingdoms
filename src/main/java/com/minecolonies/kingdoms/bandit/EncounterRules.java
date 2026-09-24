package com.minecolonies.kingdoms.bandit;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic encounter decisions. Every random choice is a pure function of the encounter seed and a fixed salt,
 * so a restart, a chunk reload, or a repeated evaluation always reaches the same decision.
 */
public final class EncounterRules
{
    public static final long SALT_AMBUSH = 1L;
    public static final long SALT_POINT = 2L;
    public static final long SALT_OUTCOME = 3L;
    public static final long SALT_LOSS = 4L;
    public static final long SALT_DELAY = 5L;
    public static final long SALT_OVERRUN = 6L;

    private EncounterRules() {}

    public enum Outcome
    {
        /** The caravan got away; nothing lost. */
        CARAVAN_ESCAPED,
        /** The caravan got away but lost time. */
        CARAVAN_DELAYED,
        /** Bandits took part of the cargo. */
        PARTIAL_LOSS,
        /** Bandits took everything (rare; only very strong groups). */
        TOTAL_LOSS,
        /** The bandits were beaten (by a player or by the caravan guards). */
        BANDITS_DEFEATED;

        public boolean banditsWon() { return this == PARTIAL_LOSS || this == TOTAL_LOSS; }
    }

    public record Decision(Outcome outcome, double lossFraction, long delayTicks) {}

    public static UUID ambushId(final UUID shipmentId, final UUID roadId)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-encounter:" + shipmentId + ':' + roadId).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID roadblockId(final UUID roadId, final int ordinal)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-roadblock:" + roadId + ':' + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    public static long seed(final UUID id)
    {
        return id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
    }

    /** A uniform value in [0, 1) for a seed and salt (SplitMix64 finalizer). */
    public static double unit(final long seed, final long salt)
    {
        long z = seed + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    /**
     * Outcome of an unobserved ambush. Bandit victory chance grows with the remaining bandit strength and shrinks with
     * the security of the road's settlements; a victory usually takes 20-60% of the cargo, and only a group of six or
     * more can (rarely) take everything.
     */
    public static Decision decideAbstract(final long seed, final int remainingStrength, final int security)
    {
        final double banditWin = Math.max(0.05D, Math.min(0.75D, 0.2D + 0.08D * remainingStrength - 0.05D * security));
        final double roll = unit(seed, SALT_OUTCOME);
        final double second = unit(seed, SALT_LOSS);
        if (remainingStrength <= 0) return new Decision(Outcome.BANDITS_DEFEATED, 0.0D, 0L);
        if (roll < banditWin)
        {
            if (remainingStrength >= 6 && second > 0.95D) return new Decision(Outcome.TOTAL_LOSS, 1.0D, 0L);
            return new Decision(Outcome.PARTIAL_LOSS, 0.2D + 0.4D * Math.min(1.0D, second / 0.95D), 0L);
        }
        // the caravan holds: the rest of the range splits 40% delayed, 35% bandits beaten off, 25% escaped
        final double rest = 1.0D - banditWin;
        if (roll < banditWin + 0.40D * rest) return new Decision(Outcome.CARAVAN_DELAYED, 0.0D, 1_200L + (long) (unit(seed, SALT_DELAY) * 2_400L));
        if (roll < banditWin + 0.75D * rest) return new Decision(Outcome.BANDITS_DEFEATED, 0.0D, 0L);
        return new Decision(Outcome.CARAVAN_ESCAPED, 0.0D, 0L);
    }

    /** Share of the cargo lost when bandits kill the caravan's leader or carrier in a physical fight (30-60%). */
    public static double overrunFraction(final long seed)
    {
        return 0.3D + 0.3D * unit(seed, SALT_OVERRUN);
    }

    /** Units lost for a fraction: at least one for any loss, never more than the cargo. */
    public static long lostUnits(final long amount, final double fraction)
    {
        if (fraction <= 0.0D || amount <= 0L) return 0L;
        if (fraction >= 1.0D) return amount;
        return Math.max(1L, Math.min(amount, Math.round(amount * fraction)));
    }
}
