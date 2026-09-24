package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.world.settlement.SettlementType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum SettlementBuildingType
{
    HOUSE(4, 3, "housing", 8, 0, Map.of(), Set.of(SettlementType.values()),
        "minecraft:oak_planks", "minecraft:oak_log", "minecraft:oak_slab"),
    FARM(6, 5, "food production", 0, 0, Map.of(EconomicResource.FOOD, 40.0D), Set.of(SettlementType.values()),
        "minecraft:farmland", "minecraft:oak_log", "minecraft:hay_block"),
    STOREHOUSE(5, 4, "storage", 0, 500, Map.of(), Set.of(SettlementType.values()),
        "minecraft:cobblestone", "minecraft:spruce_planks", "minecraft:spruce_slab"),
    LUMBER_YARD(5, 5, "wood production", 0, 0, Map.of(EconomicResource.WOOD, 24.0D), Set.of(SettlementType.values()),
        "minecraft:coarse_dirt", "minecraft:spruce_log", "minecraft:spruce_slab"),
    QUARRY(6, 5, "stone production", 0, 0, Map.of(EconomicResource.STONE, 20.0D), Set.of(SettlementType.values()),
        "minecraft:stone", "minecraft:cobblestone", "minecraft:stone_slab"),
    SMITHY(5, 4, "tools and iron production", 0, 0,
        Map.of(EconomicResource.IRON, 5.0D, EconomicResource.TOOLS, 10.0D), Set.of(SettlementType.values()),
        "minecraft:stone_bricks", "minecraft:bricks", "minecraft:stone_brick_slab"),
    MARKET(6, 5, "trade infrastructure", 0, 150, Map.of(),
        Set.of(SettlementType.TOWN, SettlementType.TRADING_TOWN, SettlementType.CASTLE),
        "minecraft:smooth_stone", "minecraft:oak_log", "minecraft:red_wool"),
    CIVIC(6, 6, "civic services", 2, 100, Map.of(), Set.of(SettlementType.values()),
        "minecraft:stone_bricks", "minecraft:polished_andesite", "minecraft:stone_brick_slab");

    private final int halfWidth;
    private final int halfDepth;
    private final String role;
    private final int housingContribution;
    private final int storageContribution;
    private final Map<EconomicResource, Double> productionContributions;
    private final Set<SettlementType> allowedTypes;
    private final String floorBlockId;
    private final String wallBlockId;
    private final String roofBlockId;

    SettlementBuildingType(final int halfWidth, final int halfDepth, final String role,
        final int housingContribution, final int storageContribution,
        final Map<EconomicResource, Double> productionContributions, final Set<SettlementType> allowedTypes,
        final String floorBlockId, final String wallBlockId, final String roofBlockId)
    {
        this.halfWidth = halfWidth;
        this.halfDepth = halfDepth;
        this.role = role;
        this.housingContribution = housingContribution;
        this.storageContribution = storageContribution;
        final EnumMap<EconomicResource, Double> copy = new EnumMap<>(EconomicResource.class);
        copy.putAll(productionContributions);
        this.productionContributions = Map.copyOf(copy);
        this.allowedTypes = Set.copyOf(allowedTypes);
        this.floorBlockId = floorBlockId;
        this.wallBlockId = wallBlockId;
        this.roofBlockId = roofBlockId;
    }

    public int halfWidth() { return halfWidth; }
    public int halfDepth() { return halfDepth; }
    public String role() { return role; }
    public int housingContribution() { return housingContribution; }
    public int storageContribution() { return storageContribution; }
    public Map<EconomicResource, Double> productionContributions() { return productionContributions; }
    public boolean allowedFor(final SettlementType type) { return allowedTypes.contains(type); }
    public String floorBlockId() { return floorBlockId; }
    public String wallBlockId() { return wallBlockId; }
    public String roofBlockId() { return roofBlockId; }
}
