package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TradeLedger
{
    private final Map<UUID, TradeRoute> routes = new LinkedHashMap<>();
    private final Map<UUID, TradeShipment> shipments = new LinkedHashMap<>();

    public Collection<TradeRoute> routes()
    {
        return Collections.unmodifiableCollection(routes.values());
    }

    public Collection<TradeShipment> shipments()
    {
        return Collections.unmodifiableCollection(shipments.values());
    }

    public Optional<TradeRoute> route(final UUID id)
    {
        return Optional.ofNullable(routes.get(id));
    }

    public Optional<TradeShipment> shipment(final UUID id)
    {
        return Optional.ofNullable(shipments.get(id));
    }

    public Optional<TradeRoute> route(final TradeRouteKey key)
    {
        return routes.values().stream().filter(route -> route.key().equals(key)).findFirst();
    }

    public void putRoute(final TradeRoute route)
    {
        final Optional<TradeRoute> duplicate = route(route.key());
        if (duplicate.isPresent() && !duplicate.orElseThrow().id().equals(route.id()))
        {
            throw new IllegalArgumentException("Duplicate trade route key " + route.key());
        }
        routes.put(route.id(), route);
    }

    public void putShipment(final TradeShipment shipment)
    {
        shipments.put(shipment.id(), shipment);
    }

    public long reservedForExport(final UUID colonyId, final EconomicResource resource)
    {
        return shipments.values().stream()
            .filter(shipment -> shipment.status() == TradeShipmentStatus.PLANNED)
            .filter(shipment -> shipment.originColonyId().equals(colonyId) && shipment.resource() == resource)
            .mapToLong(TradeShipment::amount)
            .reduce(0L, TradeLedger::saturatingAdd);
    }

    public boolean hasOpenShipment(final UUID routeId)
    {
        return shipments.values().stream().anyMatch(shipment -> shipment.routeId().equals(routeId)
            && (shipment.status() == TradeShipmentStatus.PLANNED
                || shipment.status() == TradeShipmentStatus.IN_TRANSIT));
    }

    public long committedForImport(final UUID colonyId, final EconomicResource resource)
    {
        return shipments.values().stream()
            .filter(shipment -> shipment.status() == TradeShipmentStatus.PLANNED
                || shipment.status() == TradeShipmentStatus.IN_TRANSIT)
            .filter(shipment -> shipment.destinationColonyId().equals(colonyId) && shipment.resource() == resource)
            .mapToLong(TradeShipment::amount)
            .reduce(0L, TradeLedger::saturatingAdd);
    }

    public long routeCountFor(final UUID colonyId)
    {
        return routes.values().stream()
            .filter(route -> route.status() != TradeRouteStatus.BROKEN)
            .filter(route -> route.originColonyId().equals(colonyId) || route.destinationColonyId().equals(colonyId))
            .count();
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag routeTags = new ListTag();
        routes.values().forEach(route -> routeTags.add(route.save()));
        tag.put("routes", routeTags);
        final ListTag shipmentTags = new ListTag();
        shipments.values().forEach(shipment -> shipmentTags.add(shipment.save()));
        tag.put("shipments", shipmentTags);
        return tag;
    }

    public static TradeLedger load(final CompoundTag tag)
    {
        final TradeLedger ledger = new TradeLedger();
        tag.getList("routes", Tag.TAG_COMPOUND).forEach(value -> {
            final TradeRoute route = TradeRoute.load((CompoundTag) value);
            ledger.routes.put(route.id(), route);
        });
        tag.getList("shipments", Tag.TAG_COMPOUND).forEach(value -> {
            final TradeShipment shipment = TradeShipment.load((CompoundTag) value);
            ledger.shipments.put(shipment.id(), shipment);
        });
        return ledger;
    }

    private static long saturatingAdd(final long left, final long right)
    {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
