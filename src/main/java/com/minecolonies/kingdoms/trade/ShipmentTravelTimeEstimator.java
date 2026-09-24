package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.caravan.ShipmentPath;

public final class ShipmentTravelTimeEstimator
{
    public long estimate(final ShipmentPath path, final TradeSettings settings)
    {
        if (path == null) throw new IllegalArgumentException("Shipment path is required");
        final double variable = Math.ceil(Math.max(0.0D, path.effectiveDistance()) * settings.travelTicksPerBlock());
        if (!Double.isFinite(variable) || variable >= Long.MAX_VALUE - settings.baseTravelTicks()) return Long.MAX_VALUE;
        return Math.max(1L, settings.baseTravelTicks() + (long) variable);
    }
}
