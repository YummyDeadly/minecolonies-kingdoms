package com.minecolonies.kingdoms.world.road;

import java.util.List;
import java.util.UUID;

public record RoadRoute(UUID originSettlementId, UUID destinationSettlementId,
    List<UUID> settlementIds, List<UUID> roadIds, double length)
{
    public RoadRoute
    {
        settlementIds = List.copyOf(settlementIds);
        roadIds = List.copyOf(roadIds);
        if (settlementIds.size() != roadIds.size() + 1 || settlementIds.isEmpty() || length < 0.0D)
            throw new IllegalArgumentException("Invalid road route");
        if (!settlementIds.getFirst().equals(originSettlementId) || !settlementIds.getLast().equals(destinationSettlementId))
            throw new IllegalArgumentException("Road route endpoints do not match");
    }
}
