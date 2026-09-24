package com.minecolonies.kingdoms.trade;

public enum ShipmentFailureReason
{
    CARAVAN_DESTROYED,
    DESTINATION_REMOVED,
    ORIGIN_REMOVED,
    ROUTE_REMOVED,
    TECHNICAL_FAILURE,
    EXPORT_CAPACITY_LOST,
    /** All cargo taken in a bandit encounter (Phase 8). */
    BANDIT_RAID
}
