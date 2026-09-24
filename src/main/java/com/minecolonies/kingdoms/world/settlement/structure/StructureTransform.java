package com.minecolonies.kingdoms.world.settlement.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record StructureTransform(int rotation, boolean mirrored)
{
    public StructureTransform
    {
        rotation = Math.floorMod(rotation, 360);
        if (rotation % 90 != 0) throw new IllegalArgumentException("Rotation must be a multiple of 90");
    }

    public BlockPos apply(final BlockPos offset)
    {
        int x = mirrored ? -offset.getX() : offset.getX();
        final int z = offset.getZ();
        return switch (rotation)
        {
            case 0 -> new BlockPos(x, offset.getY(), z);
            case 90 -> new BlockPos(-z, offset.getY(), x);
            case 180 -> new BlockPos(-x, offset.getY(), -z);
            case 270 -> new BlockPos(z, offset.getY(), -x);
            default -> throw new IllegalStateException("Unexpected rotation " + rotation);
        };
    }

    public Direction apply(final Direction facing)
    {
        Direction result = mirrored && facing.getAxis().isHorizontal()
            ? (facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing) : facing;
        for (int turns = 0; turns < rotation / 90; turns++) result = result.getClockWise();
        return result;
    }
}
