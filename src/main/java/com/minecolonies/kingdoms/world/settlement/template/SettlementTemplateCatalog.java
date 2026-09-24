package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Kingdoms-owned semantic templates. They select roles, never exact blueprint file names. */
public final class SettlementTemplateCatalog
{
    private static final List<SettlementTemplate> TEMPLATES = List.of(
        new SettlementTemplate("village-green", Set.of(SettlementType.VILLAGE), List.of(
            slot("civic-center", SettlementBuildingType.CIVIC, true, 12, 30),
            slot("first-homes", SettlementBuildingType.HOUSE, true, 18, 38),
            slot("stores", SettlementBuildingType.STOREHOUSE, true, 20, 42),
            slot("fields", SettlementBuildingType.FARM, false, 28, 52))),
        new SettlementTemplate("market-quarter", Set.of(SettlementType.TOWN, SettlementType.TRADING_TOWN), List.of(
            slot("civic-center", SettlementBuildingType.CIVIC, true, 14, 32),
            slot("first-homes", SettlementBuildingType.HOUSE, true, 20, 42),
            slot("stores", SettlementBuildingType.STOREHOUSE, true, 22, 46),
            slot("market", SettlementBuildingType.MARKET, false, 24, 50))),
        new SettlementTemplate("garrison-court", Set.of(SettlementType.CASTLE, SettlementType.FORT), List.of(
            slot("civic-center", SettlementBuildingType.CIVIC, true, 14, 34),
            slot("quarters", SettlementBuildingType.HOUSE, true, 20, 42),
            slot("stores", SettlementBuildingType.STOREHOUSE, true, 22, 46),
            slot("smithy", SettlementBuildingType.SMITHY, false, 24, 50))));

    public SettlementTemplate select(final SettlementType type, final UUID settlementId)
    {
        final List<SettlementTemplate> matching = TEMPLATES.stream().filter(value -> value.archetypes().contains(type))
            .sorted(Comparator.comparing(SettlementTemplate::id)).toList();
        if (matching.isEmpty()) throw new IllegalArgumentException("No starter template for " + type);
        final int index = Math.floorMod((int) (settlementId.getMostSignificantBits() ^ settlementId.getLeastSignificantBits()),
            matching.size());
        return matching.get(index);
    }

    private static SettlementTemplateSlot slot(final String role, final SettlementBuildingType type,
        final boolean required, final int minimum, final int maximum)
    {
        return new SettlementTemplateSlot(role, type, required, minimum, maximum);
    }
}
