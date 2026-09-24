package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.caravan.DirectShipmentPath;
import com.minecolonies.kingdoms.caravan.ShipmentPath;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class ShipmentPathResolver
{
    private final RoadRoutePlanner routes = new RoadRoutePlanner();

    public ShipmentPath resolve(final KingdomsSavedData data, final TradeShipment shipment)
    {
        return resolve(data, shipment.originColonyId(), shipment.destinationColonyId());
    }

    public ShipmentPath resolve(final KingdomsSavedData data, final UUID originId, final UUID destinationId)
    {
        final NPCColonyData origin = data.colony(originId).orElse(null);
        final NPCColonyData destination = data.colony(destinationId).orElse(null);
        if (origin == null || destination == null || !origin.dimension().equals(destination.dimension())) return null;
        final SettlementRecord first = data.settlements().get(origin.id()).orElse(null);
        final SettlementRecord second = data.settlements().get(destination.id()).orElse(null);
        if (first != null && second != null)
        {
            final var route = routes.shortest(data.roads(), first.id(), second.id());
            if (route.isPresent()) return new RoadShipmentPath(route.get(), data.roads());
        }
        return new DirectShipmentPath(origin.dimension(), Vec3.atCenterOf(origin.center()), Vec3.atCenterOf(destination.center()));
    }
}
