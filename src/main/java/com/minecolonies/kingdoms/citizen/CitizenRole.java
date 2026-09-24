package com.minecolonies.kingdoms.citizen;

import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;

/**
 * Visible representative roles. Every working role is derived from a completed settlement building of
 * {@link #workplace()}; residents have no workplace. The texture prefix names the matching outfit in the installed
 * MineColonies citizen texture set (runtime resource reference only; the renderer falls back if it is missing).
 * Female aristocrats and nobles use MineColonies' dress models (128x128 textures) that the humanoid model cannot map,
 * so those roles name a humanoid outfit for women instead.
 */
public enum CitizenRole
{
    RESIDENT(null, 0, "citizen", 3, "Resident"),
    FARMER(SettlementBuildingType.FARM, 2, "farmer", 1, "Farmer"),
    PORTER(SettlementBuildingType.STOREHOUSE, 1, "courier", 1, "Porter"),
    LUMBERJACK(SettlementBuildingType.LUMBER_YARD, 1, "forester", 1, "Lumberjack"),
    QUARRY_WORKER(SettlementBuildingType.QUARRY, 1, "miner", 1, "Quarry worker"),
    SMITH(SettlementBuildingType.SMITHY, 1, "blacksmith", 1, "Smith"),
    MERCHANT(SettlementBuildingType.MARKET, 2, "aristocrat", 3, "settler", 3, "Merchant"),
    OFFICIAL(SettlementBuildingType.CIVIC, 1, "noble", 3, "teacher", 1, "Official"),
    GUARD(SettlementBuildingType.CIVIC, 1, "knight", 1, "Guard");

    private final SettlementBuildingType workplace;
    private final int slotsPerBuilding;
    private final String texturePrefix;
    private final int textureVariants;
    private final String femaleTexturePrefix;
    private final int femaleTextureVariants;
    private final String displayName;

    CitizenRole(final SettlementBuildingType workplace, final int slotsPerBuilding, final String texturePrefix,
        final int textureVariants, final String displayName)
    {
        this(workplace, slotsPerBuilding, texturePrefix, textureVariants, texturePrefix, textureVariants, displayName);
    }

    CitizenRole(final SettlementBuildingType workplace, final int slotsPerBuilding, final String texturePrefix,
        final int textureVariants, final String femaleTexturePrefix, final int femaleTextureVariants, final String displayName)
    {
        this.workplace = workplace;
        this.slotsPerBuilding = slotsPerBuilding;
        this.texturePrefix = texturePrefix;
        this.textureVariants = textureVariants;
        this.femaleTexturePrefix = femaleTexturePrefix;
        this.femaleTextureVariants = femaleTextureVariants;
        this.displayName = displayName;
    }

    public SettlementBuildingType workplace() { return workplace; }
    public int slotsPerBuilding() { return slotsPerBuilding; }
    public String texturePrefix(final boolean female) { return female ? femaleTexturePrefix : texturePrefix; }
    public int textureVariants(final boolean female) { return female ? femaleTextureVariants : textureVariants; }
    public String displayName() { return displayName; }
    public boolean works() { return workplace != null; }
}
