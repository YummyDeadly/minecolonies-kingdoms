package com.minecolonies.kingdoms.trade;

public final class TradeProfiler
{
    private long matchingCycles;
    private double averageMatchingDurationNanos;
    private long maxMatchingDurationNanos;

    public void recordMatching(final long durationNanos)
    {
        matchingCycles++;
        averageMatchingDurationNanos += (durationNanos - averageMatchingDurationNanos) / matchingCycles;
        maxMatchingDurationNanos = Math.max(maxMatchingDurationNanos, durationNanos);
    }

    public void recordDelivery(final long amount)
    {
        // Delivery totals are reconstructed from persisted shipments in snapshot().
    }

    public TradeStats snapshot(final TradeLedger ledger)
    {
        return new TradeStats(
            ledger.routes().stream().filter(route -> route.status() == TradeRouteStatus.ACTIVE).count(),
            ledger.routes().stream().filter(route -> route.status() == TradeRouteStatus.PAUSED).count(),
            ledger.routes().stream().filter(route -> route.status() == TradeRouteStatus.BROKEN).count(),
            ledger.shipments().stream().filter(shipment -> shipment.status() == TradeShipmentStatus.PLANNED).count(),
            ledger.shipments().stream().filter(shipment -> shipment.status() == TradeShipmentStatus.IN_TRANSIT).count(),
            ledger.shipments().stream().filter(shipment -> shipment.status() == TradeShipmentStatus.DELIVERED).count(),
            ledger.shipments().stream().filter(shipment -> shipment.status() == TradeShipmentStatus.FAILED).count(),
            ledger.shipments().stream()
                .filter(shipment -> shipment.status() == TradeShipmentStatus.DELIVERED)
                .mapToLong(TradeShipment::amount)
                .reduce(0L, TradeProfiler::saturatingAdd),
            matchingCycles,
            averageMatchingDurationNanos,
            maxMatchingDurationNanos);
    }

    public void reset()
    {
        matchingCycles = 0L;
        averageMatchingDurationNanos = 0.0D;
        maxMatchingDurationNanos = 0L;
    }

    private static long saturatingAdd(final long left, final long right)
    {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
