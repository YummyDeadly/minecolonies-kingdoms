package com.minecolonies.kingdoms.caravan;

import com.minecolonies.kingdoms.trade.ShipmentRepresentation;

public final class CaravanEligibility
{
    private CaravanEligibility()
    {
    }

    public static boolean shouldMaterialize(
        final ShipmentRepresentation representation,
        final double nearestPlayerDistance,
        final int globalPhysicalCount,
        final int playerPhysicalCount,
        final CaravanSettings settings)
    {
        return settings.enabled()
            && representation == ShipmentRepresentation.ABSTRACT
            && nearestPlayerDistance <= settings.materializationRadius()
            && globalPhysicalCount < settings.maxPhysicalCaravans()
            && playerPhysicalCount < settings.maxPhysicalCaravansPerPlayer();
    }

    public static boolean shouldDematerialize(
        final ShipmentRepresentation representation,
        final double nearestPlayerDistance,
        final CaravanSettings settings)
    {
        return representation == ShipmentRepresentation.PHYSICAL
            && nearestPlayerDistance > settings.dematerializationRadius();
    }
}
