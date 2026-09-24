package com.minecolonies.kingdoms.military;

import com.minecolonies.kingdoms.bandit.EncounterRules;
import com.minecolonies.kingdoms.world.settlement.SettlementType;

/**
 * Pure, transparent settlement security and garrison rules (Phase 9). Security is computed from strategic state only
 * (never from loaded guards):
 *
 * <pre>
 * security = archetype + garrison + civic + defences - roadDanger - sacked        (clamped to 0..100)
 * archetype  = VILLAGE 10, TOWN/TRADING_TOWN 20, FORT 35, CASTLE 40
 * garrison   = 40 x strength / capacity            (soldiers at home)
 * civic      = 5 per completed CIVIC building (at most 2)
 * defences   = 3 per recent successful defence (at most 10), fading one per day
 * roadDanger = worst threat of the settlement's roads / 5 (at most 15)
 * sacked     = 25 while the settlement is recovering from a sack (Phase 10)
 * </pre>
 *
 * Roads read it as a level 0..5 ({@code security / 20}); two endpoints together can lower a road's threat target by at
 * most 40, so even two castles never make a long remote road perfectly safe.
 */
public final class SecurityRules
{
    public static final long SALT_SORTIE = 41L;
    public static final long SALT_SORTIE_LOSSES = 42L;
    public static final long DAY_TICKS = 24_000L;

    private SecurityRules() {}

    public record Breakdown(int archetype, int garrison, int civic, int defences, int roadDanger, int sacked)
    {
        public int total()
        {
            return Math.max(0, Math.min(100, archetype + garrison + civic + defences - roadDanger - sacked));
        }
    }

    public static int archetype(final SettlementType type)
    {
        return switch (type)
        {
            case VILLAGE -> 10;
            case TOWN, TRADING_TOWN -> 20;
            case FORT -> 35;
            case CASTLE -> 40;
        };
    }

    /**
     * Soldiers a settlement can keep: a base by archetype, one per ten citizens, two per completed CIVIC building (at
     * most two count), never more than a third of the population.
     */
    public static int capacity(final SettlementType type, final int population, final int civicBuildings)
    {
        final int base = switch (type)
        {
            case VILLAGE -> 2;
            case TOWN, TRADING_TOWN -> 4;
            case FORT -> 8;
            case CASTLE -> 10;
        };
        final int wanted = base + Math.max(0, population) / 10 + 2 * Math.min(2, Math.max(0, civicBuildings));
        return Math.max(0, Math.min(wanted, Math.max(0, population) / 3));
    }

    /** New soldiers per day: one, plus one per completed CIVIC building (at most two); none while food is critical. */
    public static double recruitPerDay(final int civicBuildings, final boolean foodCritical, final double multiplier)
    {
        if (foodCritical) return 0.0D;
        return (1.0D + Math.min(2, Math.max(0, civicBuildings))) * Math.max(0.0D, multiplier);
    }

    public static Breakdown security(final SettlementType type, final int strength, final int capacity, final int civicBuildings,
        final int recentDefences, final double worstRoadThreat, final boolean sacked)
    {
        final int garrison = capacity <= 0 ? 0 : (int) Math.round(40.0D * Math.min(1.0D, Math.max(0, strength) / (double) capacity));
        return new Breakdown(archetype(type), garrison, 5 * Math.min(2, Math.max(0, civicBuildings)), Math.min(10, 3 * Math.max(0, recentDefences)),
            (int) Math.min(15.0D, Math.max(0.0D, worstRoadThreat) / 5.0D), sacked ? 25 : 0);
    }

    /** The level a settlement lends to its roads (0..5). */
    public static int level(final int security)
    {
        return Math.max(0, Math.min(5, security / 20));
    }

    /** A patrol sortie against a bandit camp: its seeded outcome and the soldiers it costs. */
    public record Sortie(boolean success, int losses) {}

    /**
     * Deterministic sortie outcome for one (camp, garrison, attempt): success grows with the soldiers' advantage over the
     * camp's bandits and with security; a failed sortie costs more. Never re-rolled: the attempt number is persisted
     * before the outcome is applied.
     */
    public static Sortie sortie(final long seed, final int strength, final int bandits, final int security)
    {
        final double chance = Math.max(0.1D, Math.min(0.85D, 0.35D + 0.08D * (strength - 2 * bandits) + 0.003D * security));
        final boolean success = EncounterRules.unit(seed, SALT_SORTIE) < chance;
        final double roll = EncounterRules.unit(seed, SALT_SORTIE_LOSSES);
        final int losses = success ? (roll < 0.5D ? 0 : roll < 0.85D ? 1 : 2) : (roll < 0.4D ? 1 : roll < 0.8D ? 2 : 3);
        return new Sortie(success, Math.min(losses, Math.max(0, strength)));
    }

    /** Seed of one sortie: the camp's encounter, the garrison's settlement, and the garrison's attempt number. */
    public static long sortieSeed(final java.util.UUID encounterId, final java.util.UUID settlementId, final int attempt)
    {
        return EncounterRules.seed(encounterId) ^ Long.rotateLeft(EncounterRules.seed(settlementId), 23) ^ (attempt * 0x9E3779B97F4A7C15L);
    }
}
