package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.world.settlement.SettlementType;

/**
 * Pure, transparent road threat formula (0..100). A road's threat moves towards a target by at most
 * {@code threatStep} per evaluation, so it never oscillates:
 *
 * <pre>
 * target = base + traffic + remoteness + raidMomentum - security - suppression      (clamped to 0..100)
 * traffic      = min(30, 6 x recent shipments on the road)       more caravans attract bandits
 * remoteness   = min(25, road length outside settlement zones / 40)
 * raidMomentum = min(25, momentum)          +15 per successful raid, x0.9 per evaluation
 * security     = min(40, 8 x (security of both endpoint settlements))
 * suppression  = 50 while the road was recently cleared
 * </pre>
 */
public final class ThreatRules
{
    public static final double MAXIMUM = 100.0D;
    public static final double RAID_MOMENTUM_GAIN = 15.0D;
    public static final double MOMENTUM_DECAY = 0.9D;
    public static final double TRAFFIC_DECAY = 0.8D;
    public static final double CLEAR_THREAT_DROP = 20.0D;
    /** Below this threat no ambush is ever planned. */
    public static final double AMBUSH_FLOOR = 15.0D;

    private ThreatRules() {}

    public record Contributors(double base, double traffic, double remoteness, double momentum, double security, double suppression)
    {
        public double target()
        {
            return clamp(base + traffic + remoteness + momentum - security - suppression);
        }
    }

    public static Contributors contributors(final double base, final double recentTraffic, final double remoteLength,
        final double raidMomentum, final int security, final boolean suppressed)
    {
        return new Contributors(base, Math.min(30.0D, 6.0D * Math.max(0.0D, recentTraffic)),
            Math.min(25.0D, Math.max(0.0D, remoteLength) / 40.0D), Math.min(25.0D, Math.max(0.0D, raidMomentum)),
            Math.min(40.0D, 8.0D * Math.max(0, security)), suppressed ? 50.0D : 0.0D);
    }

    /** Moves towards the target by at most {@code step}. */
    public static double step(final double current, final double target, final double step)
    {
        return clamp(current + Math.max(-step, Math.min(step, target - current)));
    }

    /** Security a settlement lends to its roads. */
    public static int security(final SettlementType type)
    {
        return switch (type)
        {
            case VILLAGE -> 1;
            case TOWN, TRADING_TOWN -> 2;
            case FORT -> 3;
            case CASTLE -> 4;
        };
    }

    /** Probability that a shipment crossing a road of this threat is ambushed. */
    public static double ambushChance(final double threat, final double chanceAtMaximum)
    {
        if (threat < AMBUSH_FLOOR) return 0.0D;
        return chanceAtMaximum * (Math.min(MAXIMUM, threat) - AMBUSH_FLOOR) / (MAXIMUM - AMBUSH_FLOOR);
    }

    /** Bandit group size: 2 at low threat up to 6 at maximum, capped by configuration. */
    public static int strength(final double threat, final int maximum)
    {
        return Math.max(1, Math.min(maximum, 2 + (int) Math.floor(Math.max(0.0D, threat) / 25.0D)));
    }

    public static double clamp(final double value)
    {
        return Math.max(0.0D, Math.min(MAXIMUM, value));
    }
}
