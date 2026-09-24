package com.minecolonies.kingdoms.diplomacy;

import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only writer of player reputation ({@link ReputationRegistry}). A change spreads once (never recursively) to
 * factions related to the target faction, following {@link DiplomacyRules#spillover}; relations exist only between
 * diplomatic neighbours, so a change touches a bounded number of records. Every change is logged with its cause.
 */
public final class ReputationService
{
    private ReputationService() {}

    public record Change(UUID factionId, int before, int after)
    {
        public ReputationTier tierBefore() { return ReputationTier.of(before); }
        public ReputationTier tierAfter() { return ReputationTier.of(after); }
        public boolean tierChanged() { return tierBefore() != tierAfter(); }
    }

    /** The direct change plus any spillover changes; empty when the faction does not exist or nothing changed. */
    public record Result(List<Change> changes)
    {
        public static final Result NONE = new Result(List.of());
        public boolean isEmpty() { return changes.isEmpty(); }
        public Change primary() { return changes.getFirst(); }
    }

    public static int standing(final KingdomsSavedData data, final UUID playerId, final UUID factionId)
    {
        return data.reputation().value(playerId, factionId);
    }

    public static ReputationTier tier(final KingdomsSavedData data, final UUID playerId, final UUID factionId)
    {
        return ReputationTier.of(standing(data, playerId, factionId));
    }

    public static Result adjust(final KingdomsSavedData data, final UUID playerId, final UUID factionId, final int delta,
        final ReputationRegistry.Cause cause, final UUID reference, final long gameTime)
    {
        final Faction faction = data.faction(factionId).orElse(null);
        if (faction == null || delta == 0) return Result.NONE;
        final ReputationRegistry registry = data.reputation();
        final int before = registry.value(playerId, factionId);
        if (registry.change(playerId, factionId, delta, cause, reference, gameTime).isEmpty())
        {
            data.markChanged(); // counters may still have moved
            return Result.NONE;
        }
        final List<Change> changes = new ArrayList<>();
        changes.add(new Change(factionId, before, registry.value(playerId, factionId)));
        for (final Map.Entry<UUID, Integer> relation : faction.relations().entrySet())
        {
            final int spill = DiplomacyRules.spillover(delta, relation.getValue());
            if (spill == 0 || relation.getKey().equals(factionId) || data.faction(relation.getKey()).isEmpty()) continue;
            final int otherBefore = registry.value(playerId, relation.getKey());
            registry.change(playerId, relation.getKey(), spill, ReputationRegistry.Cause.SPILLOVER, factionId, gameTime)
                .ifPresent(event -> changes.add(new Change(relation.getKey(), otherBefore, event.after())));
        }
        data.markChanged();
        return new Result(List.copyOf(changes));
    }

    public static void set(final KingdomsSavedData data, final UUID playerId, final UUID factionId, final int value, final long gameTime)
    {
        if (data.faction(factionId).isEmpty()) return;
        data.reputation().set(playerId, factionId, value, ReputationRegistry.Cause.ADMIN_SET, gameTime);
        data.markChanged();
    }
}
