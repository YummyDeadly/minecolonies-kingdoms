package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;

import java.util.List;

public record SettlementDistrictPlan(String templateId, String styleFamily, SettlementLayoutPlan layout,
    List<SettlementBuildingRecord> buildings, int terrainWorkVolume)
{
    public SettlementDistrictPlan
    {
        buildings = List.copyOf(buildings);
        if (buildings.size() < 2 || buildings.size() > 4)
            throw new IllegalArgumentException("Starter district must contain 2-4 buildings");
    }
}
