package com.minecolonies.kingdoms.military;

/**
 * Bounded security and garrison configuration (see the {@code [security]} config section).
 *
 * @param guardMaterializationRadius   guards appear when a player is this close to a settlement anchor
 * @param guardDematerializationRadius and leave beyond this distance (hysteresis)
 * @param maxGuardsPerSettlement       patrolling guards per settlement (responders are counted separately)
 * @param responseRadius               bandit fights within this distance of a settlement get responders
 * @param maxResponders                responders per fight
 * @param patrolRadius                 garrisons send patrols against bandit camps within this distance
 * @param sortieCooldownTicks          minimum time between two patrol sorties of one garrison
 * @param sortieMinimumStrength        a garrison needs at least this many soldiers to send a patrol
 * @param recruitmentMultiplier        scales recruitment speed
 */
public record SecuritySettings(boolean enabled, int evaluationIntervalTicks, boolean guardsEnabled, int guardMaterializationRadius,
    int guardDematerializationRadius, int maxGuardsPerSettlement, int maxGuardsGlobal, int maxGuardsPerPlayer, int responseRadius,
    int maxResponders, int patrolRadius, long sortieCooldownTicks, int sortieMinimumStrength, double recruitmentMultiplier)
{
    public SecuritySettings
    {
        if (evaluationIntervalTicks < 200 || guardMaterializationRadius < 16 || guardDematerializationRadius <= guardMaterializationRadius
            || maxGuardsPerSettlement < 0 || maxGuardsGlobal < 0 || maxGuardsPerPlayer < 0 || responseRadius < 0 || maxResponders < 0
            || patrolRadius < 0 || sortieCooldownTicks < 0L || sortieMinimumStrength < 1 || recruitmentMultiplier < 0.0D)
            throw new IllegalArgumentException("Invalid security settings");
    }

    public static SecuritySettings defaults()
    {
        return new SecuritySettings(true, 2_400, true, 96, 128, 4, 24, 12, 320, 3, 640, 48_000L, 4, 1.0D);
    }
}
