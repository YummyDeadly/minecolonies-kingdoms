package com.minecolonies.kingdoms.worldevent;

/**
 * Phase 11 world-event settings (config {@code [events]}).
 *
 * @param evaluationIntervalTicks ticks between evaluations; each evaluation plans at most one event
 * @param eventChance             chance that an evaluation plans an event at all
 * @param maxActiveGlobal         open (planned or active) events in the world
 * @param maxActivePerSettlement  open events involving one settlement
 * @param cooldownTicks           minimum time between two events of one type on one subject
 * @param noticeTicks             time between planning (rumour) and activation
 * @param durationTicks           how long an event stays active
 * @param historyLimit            world-history entries kept
 * @param populationHardCap       the growth population hard cap (migration never exceeds it)
 */
public record WorldEventSettings(boolean enabled, long evaluationIntervalTicks, double eventChance, int maxActiveGlobal,
    int maxActivePerSettlement, long cooldownTicks, long noticeTicks, long durationTicks, int historyLimit, int populationHardCap)
{
    public WorldEventSettings
    {
        if (evaluationIntervalTicks < 20L || !Double.isFinite(eventChance) || eventChance < 0.0D || eventChance > 1.0D || maxActiveGlobal < 0
            || maxActivePerSettlement < 0 || cooldownTicks < 0L || noticeTicks < 0L || durationTicks < 20L || historyLimit < 0
            || populationHardCap < 1)
            throw new IllegalArgumentException("Invalid world event settings");
    }

    public static WorldEventSettings defaults()
    {
        return new WorldEventSettings(true, 12_000L, 0.5D, 4, 1, 72_000L, 2_400L, 36_000L, 256, 120);
    }
}
