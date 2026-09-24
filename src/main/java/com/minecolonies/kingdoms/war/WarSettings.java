package com.minecolonies.kingdoms.war;

/**
 * Bounded war configuration (see the {@code [war]} config section).
 *
 * @param enabled                 armies march and battles are fought; existing wars keep their state when off
 * @param autonomous              hostile neighbours may declare war on their own (operator declarations always work)
 * @param evaluationIntervalTicks one war evaluation (hostility streaks, declarations, mobilization, ends) per interval
 * @param hostileEvaluations      consecutive hostile evaluations of a pair before it can go to war
 * @param maxWars                 open wars in the world
 * @param minimumArmy             smallest army; a settlement that cannot field it sends none
 * @param maxArmySize             largest army
 * @param mobilizationTicks       DECLARED to ACTIVE
 * @param maxDurationTicks        an active war ends by its score after this long
 * @param victoryScore            war score that ends a war
 * @param ceasefireTicks          a ceasefire turns into peace after this long
 * @param truceTicks              no new war between the pair after peace
 * @param armyTicksPerBlock       army travel time per effective road block
 * @param siegeTicks              how long a siege lasts before its abstract decision
 * @param armyCooldownTicks       minimum time between two armies of one war
 * @param sackedTicks             a sacked settlement's security is reduced this long
 * @param maxSoldiersPerArmy      physical soldiers per army near players
 * @param maxSoldiersGlobal       physical soldiers in the world
 * @param maxSoldiersPerPlayer    physical soldiers materialized for one player
 * @param materializationRadius   soldiers appear when a player is this close to the army
 * @param dematerializationRadius and leave beyond this distance (hysteresis)
 */
public record WarSettings(boolean enabled, boolean autonomous, int evaluationIntervalTicks, int hostileEvaluations, int maxWars, int minimumArmy,
    int maxArmySize, long mobilizationTicks, long maxDurationTicks, int victoryScore, long ceasefireTicks, long truceTicks,
    double armyTicksPerBlock, long siegeTicks, long armyCooldownTicks, long sackedTicks, int maxSoldiersPerArmy, int maxSoldiersGlobal,
    int maxSoldiersPerPlayer, int materializationRadius, int dematerializationRadius)
{
    public WarSettings
    {
        if (evaluationIntervalTicks < 200 || hostileEvaluations < 1 || maxWars < 0 || minimumArmy < 1 || maxArmySize < minimumArmy
            || mobilizationTicks < 0L || maxDurationTicks < 1_200L || victoryScore < 1 || victoryScore > WarRecord.SCORE_LIMIT
            || ceasefireTicks < 0L || truceTicks < 0L || !Double.isFinite(armyTicksPerBlock) || armyTicksPerBlock <= 0.0D || siegeTicks < 200L
            || armyCooldownTicks < 0L || sackedTicks < 0L || maxSoldiersPerArmy < 0 || maxSoldiersGlobal < 0 || maxSoldiersPerPlayer < 0
            || materializationRadius < 16 || dematerializationRadius <= materializationRadius)
            throw new IllegalArgumentException("Invalid war settings");
    }

    public static WarSettings defaults()
    {
        return new WarSettings(true, true, 24_000, 3, 2, 4, 40, 12_000L, 168_000L, 50, 24_000L, 240_000L, 3.0D, 6_000L, 24_000L, 72_000L,
            8, 32, 16, 64, 96);
    }
}
