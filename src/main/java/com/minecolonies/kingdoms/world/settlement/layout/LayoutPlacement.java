package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureSelection;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingPlan;
import net.minecraft.core.BlockPos;

public record LayoutPlacement(BlockPos anchor, SettlementStructureSelection structure, SettlementLot lot,
    SettlementStreetSegment streetExtension, TerrainShapingPlan terrain)
{
}
