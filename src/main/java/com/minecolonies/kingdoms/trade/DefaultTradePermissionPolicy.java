package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.diplomacy.DiplomaticStance;
import com.minecolonies.kingdoms.faction.Faction;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class DefaultTradePermissionPolicy implements TradePermissionPolicy
{
    @Override
    public boolean allows(
        final TradeOffer offer,
        final TradeDemand demand,
        final Function<UUID, Optional<Faction>> factionLookup)
    {
        if (offer.factionId().equals(demand.factionId()))
        {
            return true;
        }
        final Faction originFaction = factionLookup.apply(offer.factionId()).orElse(null);
        final Faction destinationFaction = factionLookup.apply(demand.factionId()).orElse(null);
        if (originFaction == null || destinationFaction == null)
        {
            return false;
        }
        // Tense or hostile neighbours do not trade (Phase 7 diplomacy); neutral or better do.
        return DiplomaticStance.of(originFaction.relations().getOrDefault(destinationFaction.id(), 0)).allowsTrade()
            && DiplomaticStance.of(destinationFaction.relations().getOrDefault(originFaction.id(), 0)).allowsTrade();
    }
}
