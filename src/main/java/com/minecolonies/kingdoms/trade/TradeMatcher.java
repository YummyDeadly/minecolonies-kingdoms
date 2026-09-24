package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.faction.Faction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class TradeMatcher
{
    private static final Comparator<TradeDemand> DEMAND_ORDER = Comparator
        .comparingInt(TradeDemand::priority).reversed()
        .thenComparing(demand -> demand.resource().ordinal())
        .thenComparing(TradeDemand::colonyId);

    public List<TradeMatchCandidate> match(
        final Collection<TradeOffer> offers,
        final Collection<TradeDemand> demands,
        final TradePermissionPolicy permissionPolicy,
        final Function<UUID, Optional<Faction>> factionLookup,
        final double maximumDistance,
        final long minimumShipmentAmount,
        final long maximumShipmentAmount)
    {
        if (!Double.isFinite(maximumDistance) || maximumDistance <= 0.0D
            || minimumShipmentAmount <= 0L || maximumShipmentAmount < minimumShipmentAmount)
        {
            throw new IllegalArgumentException("Invalid matching limits");
        }

        final List<TradeOffer> sortedOffers = offers.stream()
            .sorted(Comparator.comparing((TradeOffer offer) -> offer.resource().ordinal())
                .thenComparing(TradeOffer::colonyId))
            .toList();
        final Map<TradeOffer, Long> remainingOffers = new HashMap<>();
        sortedOffers.forEach(offer -> remainingOffers.put(offer, offer.amount()));
        final List<TradeMatchCandidate> result = new ArrayList<>();

        for (final TradeDemand demand : demands.stream().sorted(DEMAND_ORDER).toList())
        {
            long remainingDemand = demand.amount();
            final List<OfferDistance> candidates = sortedOffers.stream()
                .filter(offer -> offer.resource() == demand.resource())
                .filter(offer -> !offer.colonyId().equals(demand.colonyId()))
                .filter(offer -> offer.dimension().equals(demand.dimension()))
                .filter(offer -> remainingOffers.getOrDefault(offer, 0L) >= minimumShipmentAmount)
                .filter(offer -> permissionPolicy.allows(offer, demand, factionLookup))
                .map(offer -> new OfferDistance(offer, distance(offer, demand)))
                .filter(candidate -> candidate.distance() <= maximumDistance)
                .sorted(Comparator.comparingDouble(OfferDistance::distance)
                    .thenComparing(candidate -> candidate.offer().colonyId()))
                .toList();

            for (final OfferDistance candidate : candidates)
            {
                if (remainingDemand < minimumShipmentAmount)
                {
                    break;
                }
                final long available = remainingOffers.getOrDefault(candidate.offer(), 0L);
                final long amount = Math.min(maximumShipmentAmount, Math.min(available, remainingDemand));
                if (amount < minimumShipmentAmount)
                {
                    continue;
                }
                result.add(new TradeMatchCandidate(
                    candidate.offer().colonyId(),
                    demand.colonyId(),
                    demand.resource(),
                    amount,
                    candidate.distance(),
                    Math.max(candidate.offer().priority(), demand.priority())));
                remainingOffers.put(candidate.offer(), available - amount);
                remainingDemand -= amount;
            }
        }
        return List.copyOf(result);
    }

    private static double distance(final TradeOffer offer, final TradeDemand demand)
    {
        return Math.sqrt(offer.center().distSqr(demand.center()));
    }

    private record OfferDistance(TradeOffer offer, double distance)
    {
    }
}
