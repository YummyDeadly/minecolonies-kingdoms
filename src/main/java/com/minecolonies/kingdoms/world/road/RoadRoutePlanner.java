package com.minecolonies.kingdoms.world.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

public final class RoadRoutePlanner
{
    public Optional<RoadRoute> shortest(final RoadNetwork network, final UUID origin, final UUID destination)
    {
        if (origin.equals(destination)) return Optional.empty();
        final Map<UUID, Double> distance = new HashMap<>();
        final Map<UUID, UUID> previousSettlement = new HashMap<>();
        final Map<UUID, UUID> previousRoad = new HashMap<>();
        final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::distance).thenComparing(Entry::settlement));
        distance.put(origin, 0.0D);
        open.add(new Entry(origin, 0.0D));
        while (!open.isEmpty())
        {
            final Entry entry = open.remove();
            if (entry.distance > distance.getOrDefault(entry.settlement, Double.POSITIVE_INFINITY)) continue;
            if (entry.settlement.equals(destination)) break;
            for (final RoadRecord road : network.incident(entry.settlement))
            {
                if (!road.hasPhysicalGeometry() || road.status() == RoadStatus.UNROUTABLE || road.status() == RoadStatus.FAILED) continue;
                final UUID next = road.other(entry.settlement);
                final double candidate = entry.distance + road.length() / road.type().speedMultiplier();
                if (candidate < distance.getOrDefault(next, Double.POSITIVE_INFINITY))
                {
                    distance.put(next, candidate);
                    previousSettlement.put(next, entry.settlement);
                    previousRoad.put(next, road.id());
                    open.add(new Entry(next, candidate));
                }
            }
        }
        if (!distance.containsKey(destination)) return Optional.empty();
        final List<UUID> settlements = new ArrayList<>();
        final List<UUID> roads = new ArrayList<>();
        UUID current = destination;
        settlements.add(current);
        while (!current.equals(origin))
        {
            roads.add(previousRoad.get(current));
            current = previousSettlement.get(current);
            settlements.add(current);
        }
        final List<UUID> orderedSettlements = settlements.reversed();
        final List<UUID> orderedRoads = roads.reversed();
        double actualLength = 0.0D;
        for (final UUID road : orderedRoads) actualLength += network.get(road).orElseThrow().length();
        return Optional.of(new RoadRoute(origin, destination, orderedSettlements, orderedRoads, actualLength));
    }

    private record Entry(UUID settlement, double distance) {}
}
