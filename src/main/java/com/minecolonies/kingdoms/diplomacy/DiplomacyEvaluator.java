package com.minecolonies.kingdoms.diplomacy;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One bounded diplomacy evaluation. Only diplomatic neighbours are related: factions whose settlements share a road
 * edge (routed or not) or a trade route. With the road graph's degree limit this is linear in the number of
 * settlements. Only NPC factions take part; player standing is expressed through reputation instead.
 */
public final class DiplomacyEvaluator
{
    private static final Set<FactionType> PARTICIPANTS = EnumSet.of(FactionType.CITY_STATE, FactionType.KINGDOM, FactionType.TRIBE);

    private DiplomacyEvaluator() {}

    /** An unordered faction pair, stored with the smaller UUID first. */
    public record Pair(UUID first, UUID second)
    {
        public static Pair of(final UUID a, final UUID b)
        {
            return a.compareTo(b) <= 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }

    public record Report(int pairs, List<DiplomacyState.Event> events)
    {
        public long stanceChanges() { return events.stream().filter(event -> event.stanceBefore() != event.stanceAfter()).count(); }
    }

    /**
     * Emits explicit events only: {@code FIRST_CONTACT} for neighbour pairs without a relation yet, and one
     * {@code TRADE_DELIVERIES} event per pair that traded during the window since the previous evaluation.
     */
    public static Report evaluate(final KingdomsSavedData data, final long gameTime)
    {
        final DiplomacyState state = data.diplomacy();
        final long since = state.lastEvaluatedAt() < 0L ? Long.MIN_VALUE : state.lastEvaluatedAt();
        final Set<Pair> pairs = neighbourPairs(data);
        final Map<Pair, Integer> deliveries = deliveries(data, since, gameTime);
        final List<DiplomacyState.Event> events = new ArrayList<>();
        for (final Pair pair : pairs)
        {
            final Faction first = data.faction(pair.first()).orElse(null);
            final Faction second = data.faction(pair.second()).orElse(null);
            if (first == null || second == null) continue;
            if (!DiplomacyService.related(first, second))
                DiplomacyService.set(data, first, second, DiplomacyRules.firstContact(first.id(), second.id()),
                    DiplomacyState.Cause.FIRST_CONTACT, 0L, gameTime).ifPresent(events::add);
            final int count = deliveries.getOrDefault(pair, 0);
            DiplomacyService.change(data, first, second, DiplomacyRules.tradeDelta(count), DiplomacyState.Cause.TRADE_DELIVERIES,
                count, gameTime).ifPresent(events::add);
        }
        state.setLastEvaluatedAt(gameTime);
        data.markChanged();
        return new Report(pairs.size(), List.copyOf(events));
    }

    public static int relation(final Faction first, final Faction second)
    {
        return DiplomacyService.relation(first, second);
    }

    public static Set<Pair> neighbourPairs(final KingdomsSavedData data)
    {
        final Set<Pair> pairs = new TreeSet<>((a, b) -> {
            final int first = a.first().compareTo(b.first());
            return first != 0 ? first : a.second().compareTo(b.second());
        });
        data.roads().roads().forEach(road -> add(data, pairs, road.firstSettlementId(), road.secondSettlementId()));
        data.tradeLedger().routes().forEach(route -> add(data, pairs, route.originColonyId(), route.destinationColonyId()));
        return pairs;
    }

    private static void add(final KingdomsSavedData data, final Set<Pair> pairs, final UUID firstColony, final UUID secondColony)
    {
        final UUID first = participant(data, firstColony);
        final UUID second = participant(data, secondColony);
        if (first != null && second != null && !first.equals(second)) pairs.add(Pair.of(first, second));
    }

    private static UUID participant(final KingdomsSavedData data, final UUID colonyId)
    {
        final NPCColonyData colony = data.colony(colonyId).orElse(null);
        if (colony == null) return null;
        return data.faction(colony.factionId()).filter(faction -> PARTICIPANTS.contains(faction.type()))
            .map(Faction::id).orElse(null);
    }

    private static Map<Pair, Integer> deliveries(final KingdomsSavedData data, final long since, final long now)
    {
        final Map<Pair, Integer> counts = new HashMap<>();
        for (final TradeShipment shipment : data.tradeLedger().shipments())
        {
            if (shipment.status() != TradeShipmentStatus.DELIVERED || shipment.arrivalAt() <= since || shipment.arrivalAt() > now) continue;
            final UUID first = participant(data, shipment.originColonyId());
            final UUID second = participant(data, shipment.destinationColonyId());
            if (first != null && second != null && !first.equals(second)) counts.merge(Pair.of(first, second), 1, Integer::sum);
        }
        return counts;
    }
}
