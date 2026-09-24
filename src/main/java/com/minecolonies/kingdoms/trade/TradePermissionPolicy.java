package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.faction.Faction;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@FunctionalInterface
public interface TradePermissionPolicy
{
    boolean allows(
        TradeOffer offer,
        TradeDemand demand,
        Function<UUID, Optional<Faction>> factionLookup);
}
