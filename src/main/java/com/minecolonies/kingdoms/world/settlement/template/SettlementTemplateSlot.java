package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;

import java.util.Objects;

public record SettlementTemplateSlot(String role, SettlementBuildingType buildingType, boolean required,
    int minimumDistance, int maximumDistance)
{
    public SettlementTemplateSlot
    {
        role = Objects.requireNonNull(role).trim();
        Objects.requireNonNull(buildingType);
        if (role.isEmpty() || minimumDistance < 0 || maximumDistance < minimumDistance)
            throw new IllegalArgumentException("Invalid settlement template slot");
    }
}
