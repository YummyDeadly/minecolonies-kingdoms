package com.minecolonies.kingdoms.worldevent;

import java.util.Locale;

/**
 * The world events of Phase 11. Each one acts only through the authority that owns what it changes (see
 * {@link WorldEventService}); none creates entities, value, or contracts of its own.
 */
public enum WorldEventType
{
    /** Food stock spoiled and harvests fail for a while (economy). */
    HARVEST_FAILURE(Scope.SETTLEMENT, true, 3, "Harvest failure"),
    /** A market fair: the settlement's main product is made faster for a while (economy, then trade). */
    TRADE_FAIR(Scope.SETTLEMENT, true, 3, "Trade fair"),
    /** More bandits on one road for a while (road threat target). */
    BANDIT_SURGE(Scope.ROAD, true, 3, "Bandit surge"),
    /** Sickness in the barracks: some soldiers are lost once (garrison). */
    GARRISON_FEVER(Scope.SETTLEMENT, false, 2, "Garrison fever"),
    /** Volunteers join an endangered settlement's garrison once (garrison). */
    MILITIA_MUSTER(Scope.SETTLEMENT, false, 2, "Militia muster"),
    /** A clash at the border worsens the relation of two neighbouring factions once (diplomacy). */
    BORDER_INCIDENT(Scope.PAIR, false, 2, "Border incident"),
    /** Envoys improve the relation of two neighbouring factions once (diplomacy). */
    ENVOY_VISIT(Scope.PAIR, false, 2, "Envoy visit"),
    /** People leave a struggling settlement for a neighbour with room, once (population). */
    MIGRATION(Scope.MIGRATION, false, 3, "Migration");

    /** What an event is about: one settlement, one road, a pair of factions, or a source and destination settlement. */
    public enum Scope { SETTLEMENT, ROAD, PAIR, MIGRATION }

    private final Scope scope;
    private final boolean reversible;
    private final int weight;
    private final String displayName;

    WorldEventType(final Scope scope, final boolean reversible, final int weight, final String displayName)
    {
        this.scope = scope;
        this.reversible = reversible;
        this.weight = weight;
        this.displayName = displayName;
    }

    public Scope scope() { return scope; }
    /** Reversible events change a rate while they are active and give it back when they end; the others act once. */
    public boolean reversible() { return reversible; }
    public int weight() { return weight; }
    public String displayName() { return displayName; }

    public static java.util.Optional<WorldEventType> parse(final String text)
    {
        try { return java.util.Optional.of(valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'))); }
        catch (IllegalArgumentException exception) { return java.util.Optional.empty(); }
    }
}
