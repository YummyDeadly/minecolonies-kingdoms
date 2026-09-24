package com.minecolonies.kingdoms.diplomacy;

import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;

import java.util.Optional;

/**
 * The only writer of faction relations. Every change keeps both directions equal and is logged in
 * {@link DiplomacyState} with its cause and evidence, so each relation value can be explained from its events.
 */
public final class DiplomacyService
{
    private DiplomacyService() {}

    /** Symmetric value; if the directions ever disagree (edits made before auditing), their average is used. */
    public static int relation(final Faction first, final Faction second)
    {
        final Integer forward = first.relations().get(second.id());
        final Integer backward = second.relations().get(first.id());
        if (forward == null && backward == null) return 0;
        if (forward == null) return backward;
        if (backward == null) return forward;
        return Math.floorDiv(forward + backward, 2);
    }

    public static boolean related(final Faction first, final Faction second)
    {
        return first.relations().containsKey(second.id()) && second.relations().containsKey(first.id());
    }

    /** Sets the relation of a pair to {@code value} and logs the change; empty if nothing changed. */
    public static Optional<DiplomacyState.Event> set(final KingdomsSavedData data, final Faction a, final Faction b, final int value,
        final DiplomacyState.Cause cause, final long evidence, final long gameTime)
    {
        if (a.id().equals(b.id())) throw new IllegalArgumentException("A faction has no relation with itself");
        final Faction first = a.id().compareTo(b.id()) <= 0 ? a : b;
        final Faction second = first == a ? b : a;
        final boolean known = related(first, second);
        final int before = relation(first, second);
        final int after = DiplomacyRules.clamp(value);
        if (known && before == after && first.relations().get(second.id()) == after && second.relations().get(first.id()) == after)
            return Optional.empty();
        first.setRelation(second.id(), after);
        second.setRelation(first.id(), after);
        final DiplomacyState.Event event = new DiplomacyState.Event(gameTime, first.id(), second.id(), before, after, cause, evidence);
        data.diplomacy().record(event);
        data.markChanged();
        return Optional.of(event);
    }

    public static Optional<DiplomacyState.Event> change(final KingdomsSavedData data, final Faction a, final Faction b, final int delta,
        final DiplomacyState.Cause cause, final long evidence, final long gameTime)
    {
        if (delta == 0) return Optional.empty();
        return set(data, a, b, relation(a, b) + delta, cause, evidence, gameTime);
    }
}
