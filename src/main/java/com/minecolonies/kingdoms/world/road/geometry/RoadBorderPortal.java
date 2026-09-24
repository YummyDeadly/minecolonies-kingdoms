package com.minecolonies.kingdoms.world.road.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public record RoadBorderPortal(long chunk, BlockPos position)
{
    public RoadBorderPortal
    {
        position = position.immutable();
        if (!new ChunkPos(chunk).equals(new ChunkPos(position))) throw new IllegalArgumentException("Portal is outside its chunk");
    }
}
