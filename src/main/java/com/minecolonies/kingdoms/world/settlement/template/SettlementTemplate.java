package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.settlement.SettlementType;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SettlementTemplate(String id, Set<SettlementType> archetypes, List<SettlementTemplateSlot> slots)
{
    public SettlementTemplate
    {
        id = Objects.requireNonNull(id).trim();
        archetypes = Set.copyOf(archetypes);
        slots = List.copyOf(slots);
        if (id.isEmpty() || archetypes.isEmpty() || slots.size() < 2 || slots.size() > 4
            || slots.stream().filter(SettlementTemplateSlot::required).count() < 2)
            throw new IllegalArgumentException("Invalid settlement template");
    }
}
