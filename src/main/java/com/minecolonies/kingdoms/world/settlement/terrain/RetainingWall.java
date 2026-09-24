package com.minecolonies.kingdoms.world.settlement.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

public record RetainingWall(BlockPos start, BlockPos end, int baseHeight, int topHeight)
{
    public RetainingWall
    {
        start = Objects.requireNonNull(start).immutable(); end = Objects.requireNonNull(end).immutable();
        if (start.getX() != end.getX() && start.getZ() != end.getZ())
            throw new IllegalArgumentException("Retaining wall must be axis aligned");
        if (baseHeight > topHeight) throw new IllegalArgumentException("Inverted retaining wall");
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag(); tag.putLong("start", start.asLong()); tag.putLong("end", end.asLong());
        tag.putInt("base", baseHeight); tag.putInt("top", topHeight); return tag;
    }

    public static RetainingWall load(final CompoundTag tag)
    {
        return new RetainingWall(BlockPos.of(tag.getLong("start")), BlockPos.of(tag.getLong("end")),
            tag.getInt("base"), tag.getInt("top"));
    }
}
