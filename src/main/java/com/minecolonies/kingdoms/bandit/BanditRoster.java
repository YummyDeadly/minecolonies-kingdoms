package com.minecolonies.kingdoms.bandit;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime bookkeeping of physical bandits; nothing here is saved. It records which entities represent which
 * encounter, whom they were materialized for, and the materialization timers (operator holds, suppression after
 * leaving or stalling, retry after a failed spawn). It is pure, so the cap, duplicate, and orphan rules have unit tests.
 *
 * <p>An entity represents an encounter only while this roster lists it under that encounter's current presence.
 * Everything else (a bandit from an earlier materialization, from before a restart, or from another encounter) is an
 * orphan and removes itself.
 */
final class BanditRoster
{
    /** One materialization of one encounter. */
    static final class Presence
    {
        private final UUID encounterId;
        private final UUID observer;
        private final long materializedAt;
        private final Set<UUID> entities = new LinkedHashSet<>();
        private long lastProgressAt;
        private boolean chiefSpawned;
        private int spawnedEver;

        private Presence(final UUID encounterId, final UUID observer, final long gameTime)
        {
            this.encounterId = encounterId;
            this.observer = observer;
            this.materializedAt = gameTime;
            this.lastProgressAt = gameTime;
        }

        UUID encounterId() { return encounterId; }
        UUID observer() { return observer; }
        long materializedAt() { return materializedAt; }
        long lastProgressAt() { return lastProgressAt; }
        boolean chiefSpawned() { return chiefSpawned; }
        int spawnedEver() { return spawnedEver; }
        int size() { return entities.size(); }
        List<UUID> entities() { return List.copyOf(entities); }
        void progress(final long gameTime) { lastProgressAt = Math.max(lastProgressAt, gameTime); }
        void chiefSpawned(final boolean value) { chiefSpawned |= value; }
    }

    private final Map<UUID, Presence> presences = new LinkedHashMap<>();
    private final Map<UUID, UUID> entityIndex = new HashMap<>();
    private final Map<UUID, Long> heldUntil = new HashMap<>();
    private final Map<UUID, Long> suppressedUntil = new HashMap<>();
    private final Map<UUID, Long> retryAt = new HashMap<>();

    // ------------------------------------------------------------------------------------------------ presences

    /** Starts a materialization. An encounter has at most one: a second one would duplicate its bandits. */
    Presence begin(final UUID encounterId, final UUID observer, final long gameTime)
    {
        if (presences.containsKey(encounterId)) throw new IllegalStateException("Encounter " + encounterId + " is already physical");
        final Presence presence = new Presence(encounterId, observer, gameTime);
        presences.put(encounterId, presence);
        return presence;
    }

    /** Records a spawned entity; an entity already listed (for any encounter) is not added again. */
    boolean add(final UUID encounterId, final UUID entityId)
    {
        final Presence presence = presences.get(encounterId);
        if (presence == null) throw new IllegalStateException("Encounter " + encounterId + " is not physical");
        if (entityIndex.putIfAbsent(entityId, encounterId) != null) return false;
        presence.entities.add(entityId);
        presence.spawnedEver++;
        return true;
    }

    /** An entity no longer represents its encounter (died, fled, or unloaded). Returns its encounter, if it had one. */
    Optional<UUID> drop(final UUID entityId)
    {
        final UUID encounterId = entityIndex.remove(entityId);
        if (encounterId == null) return Optional.empty();
        final Presence presence = presences.get(encounterId);
        if (presence != null) presence.entities.remove(entityId);
        return Optional.of(encounterId);
    }

    /** Ends a materialization and returns the entities the caller must discard (empty if it was not physical). */
    List<UUID> end(final UUID encounterId)
    {
        final Presence presence = presences.remove(encounterId);
        if (presence == null) return List.of();
        final List<UUID> entities = List.copyOf(presence.entities);
        entities.forEach(entityIndex::remove);
        presence.entities.clear();
        return entities;
    }

    /** Whether this entity is one of the current bandits of the encounter it claims; anything else is an orphan. */
    boolean isCurrent(final UUID entityId, final UUID claimedEncounter)
    {
        final UUID encounterId = entityIndex.get(entityId);
        return encounterId != null && encounterId.equals(claimedEncounter) && presences.containsKey(encounterId);
    }

    Optional<Presence> presence(final UUID encounterId) { return Optional.ofNullable(presences.get(encounterId)); }
    boolean physical(final UUID encounterId) { return presences.containsKey(encounterId); }
    Collection<Presence> presences() { return List.copyOf(presences.values()); }
    List<UUID> encounterIds() { return List.copyOf(presences.keySet()); }
    int encounters() { return presences.size(); }
    int bandits() { return entityIndex.size(); }

    /** How many more bandits may appear for this observer, bounded by the encounter, per-player, and global caps. */
    int allowance(final UUID observer, final int remainingStrength, final BanditSettings settings)
    {
        int forPlayer = 0;
        if (observer != null)
            for (final Presence presence : presences.values()) if (observer.equals(presence.observer)) forPlayer += presence.entities.size();
        return BanditCaps.allowance(remainingStrength, settings.maxBanditsPerEncounter(), settings.maxPhysicalBanditsGlobal() - bandits(),
            observer == null ? Integer.MAX_VALUE : settings.maxPhysicalBanditsPerPlayer() - forPlayer);
    }

    // ------------------------------------------------------------------------------------------------ timers

    void hold(final UUID encounterId, final long until) { heldUntil.put(encounterId, until); }
    void release(final UUID encounterId) { heldUntil.remove(encounterId); }
    boolean held(final UUID encounterId, final long gameTime) { return heldUntil.getOrDefault(encounterId, Long.MIN_VALUE) > gameTime; }

    void suppress(final UUID encounterId, final long until) { suppressedUntil.merge(encounterId, until, Math::max); }
    void unsuppress(final UUID encounterId) { suppressedUntil.remove(encounterId); retryAt.remove(encounterId); }
    void retry(final UUID encounterId, final long at) { retryAt.put(encounterId, at); }

    /** Not suppressed (left, stalled, or dematerialized by an operator) and not waiting after a failed spawn. */
    boolean mayMaterialize(final UUID encounterId, final long gameTime)
    {
        return suppressedUntil.getOrDefault(encounterId, Long.MIN_VALUE) <= gameTime && retryAt.getOrDefault(encounterId, Long.MIN_VALUE) <= gameTime;
    }

    /** Drops expired timers and those of encounters that are gone, so the maps stay as small as the open encounters. */
    void sweep(final long gameTime, final Set<UUID> openEncounters)
    {
        heldUntil.entrySet().removeIf(entry -> entry.getValue() <= gameTime || !openEncounters.contains(entry.getKey()));
        suppressedUntil.entrySet().removeIf(entry -> entry.getValue() <= gameTime || !openEncounters.contains(entry.getKey()));
        retryAt.entrySet().removeIf(entry -> entry.getValue() <= gameTime || !openEncounters.contains(entry.getKey()));
    }

    int timers() { return heldUntil.size() + suppressedUntil.size() + retryAt.size(); }

    void clear()
    {
        presences.clear();
        entityIndex.clear();
        heldUntil.clear();
        suppressedUntil.clear();
        retryAt.clear();
    }

    // ------------------------------------------------------------------------------------------------ presence rules

    /**
     * Bandits are hostile mobs: on peaceful the game would remove them every tick and the manager would respawn them
     * every cycle. On peaceful they therefore never appear; encounters simply stay abstract.
     */
    static boolean mayAppear(final net.minecraft.world.Difficulty difficulty)
    {
        return difficulty != net.minecraft.world.Difficulty.PEACEFUL;
    }

    /** Hysteresis: bandits appear within the materialization radius and stay until the dematerialization radius. */
    static boolean inMaterializationRange(final double nearestPlayer, final boolean held, final BanditSettings settings)
    {
        return held || nearestPlayer <= settings.materializationRadius();
    }

    static boolean shouldLeave(final double nearestPlayer, final boolean held, final BanditSettings settings)
    {
        return !held && nearestPlayer > settings.dematerializationRadius();
    }

    /**
     * Stuck recovery: nobody fought for {@link BanditManager#STALL_TICKS}, or a roadblock outlived its lifetime and
     * nobody fought for {@link BanditManager#EXPIRY_GRACE_TICKS}. The encounter then goes abstract and stays abstract
     * long enough for its abstract rules to settle it.
     */
    static boolean stalled(final Presence presence, final BanditEncounter encounter, final long gameTime)
    {
        final long idle = gameTime - presence.lastProgressAt;
        if (idle > BanditManager.STALL_TICKS) return true;
        return encounter.kind() == BanditEncounter.Kind.ROADBLOCK && gameTime >= encounter.expiresAt() && idle > BanditManager.EXPIRY_GRACE_TICKS;
    }

    /** How long a stalled encounter stays abstract: past its deferred abstract resolution, plus a margin of two cycles. */
    static long stallSuppression(final BanditSettings settings)
    {
        return settings.abstractResolveTicks() / 2 + 2L * BanditManager.CYCLE_TICKS;
    }
}
