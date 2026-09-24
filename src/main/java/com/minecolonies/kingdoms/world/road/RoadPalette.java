package com.minecolonies.kingdoms.world.road;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deterministic, weighted road materials. Each column picks from a small mixed palette by a stable hash of its
 * coordinates and the road id, so a road reads as worn masonry/gravel/path rather than one repeated block, and the
 * result is identical on every regeneration.
 */
final class RoadPalette
{
    private RoadPalette() {}

    static BlockState surface(final RoadType type, final boolean sandy, final boolean edge, final long hash)
    {
        final int roll = Math.floorMod(hash, 100);
        if (sandy) return switch (type)
        {
            case ROYAL -> edge ? pick(roll, Blocks.SMOOTH_SANDSTONE, 70, Blocks.CUT_SANDSTONE)
                : pick(roll, Blocks.CUT_SANDSTONE, 50, Blocks.SMOOTH_SANDSTONE, 80, Blocks.SANDSTONE);
            case STONE -> edge ? pick(roll, Blocks.SANDSTONE, 60, Blocks.GRAVEL)
                : pick(roll, Blocks.SANDSTONE, 45, Blocks.SMOOTH_SANDSTONE, 70, Blocks.GRAVEL, 85, Blocks.CUT_SANDSTONE);
            case DIRT, TRAIL -> pick(roll, Blocks.SANDSTONE, 40, Blocks.SMOOTH_SANDSTONE, 65, Blocks.COARSE_DIRT);
        };
        return switch (type)
        {
            case ROYAL -> edge ? pick(roll, Blocks.POLISHED_ANDESITE, 70, Blocks.STONE_BRICKS)
                : pick(roll, Blocks.STONE_BRICKS, 46, Blocks.CRACKED_STONE_BRICKS, 58, Blocks.MOSSY_STONE_BRICKS,
                    68, Blocks.POLISHED_ANDESITE, 80, Blocks.ANDESITE, 90, Blocks.COBBLESTONE);
            case STONE -> edge ? pick(roll, Blocks.GRAVEL, 45, Blocks.COARSE_DIRT, 75, Blocks.COBBLESTONE)
                : pick(roll, Blocks.COBBLESTONE, 38, Blocks.GRAVEL, 54, Blocks.ANDESITE, 68, Blocks.MOSSY_COBBLESTONE,
                    80, Blocks.STONE, 88, Blocks.COARSE_DIRT);
            case DIRT -> edge ? pick(roll, Blocks.COARSE_DIRT, 45, Blocks.DIRT_PATH, 80, Blocks.GRAVEL)
                : pick(roll, Blocks.DIRT_PATH, 55, Blocks.COARSE_DIRT, 77, Blocks.GRAVEL, 86, Blocks.PACKED_MUD,
                    92, Blocks.ROOTED_DIRT);
            case TRAIL -> edge ? pick(roll, Blocks.COARSE_DIRT, 60, Blocks.DIRT_PATH)
                : pick(roll, Blocks.DIRT_PATH, 65, Blocks.COARSE_DIRT, 90, Blocks.GRAVEL);
        };
    }

    /** Half-step placed on the lower side of a one-block rise; dirt roads keep plain steps. */
    static BlockState slab(final RoadType type, final boolean sandy)
    {
        return switch (type)
        {
            case ROYAL -> (sandy ? Blocks.SMOOTH_SANDSTONE_SLAB : Blocks.STONE_BRICK_SLAB).defaultBlockState();
            case STONE -> (sandy ? Blocks.SANDSTONE_SLAB : Blocks.COBBLESTONE_SLAB).defaultBlockState();
            case DIRT, TRAIL -> null;
        };
    }

    static BlockState support(final RoadType type, final boolean sandy)
    {
        if (sandy) return Blocks.SANDSTONE.defaultBlockState();
        return type == RoadType.ROYAL || type == RoadType.STONE ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
    }

    static BlockState deck(final RoadType type)
    {
        return type == RoadType.ROYAL || type == RoadType.STONE ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
    }

    static BlockState railing(final RoadType type)
    {
        return type == RoadType.ROYAL || type == RoadType.STONE ? Blocks.STONE_BRICK_WALL.defaultBlockState() : Blocks.SPRUCE_FENCE.defaultBlockState();
    }

    static BlockState pillar(final RoadType type)
    {
        return type == RoadType.ROYAL || type == RoadType.STONE ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.SPRUCE_LOG.defaultBlockState();
    }

    private static BlockState pick(final int roll, final Object... weightedBlocks)
    {
        // Layout: block0, bound1, block1, bound2, block2, ... ; blockN is used when roll < boundN+1 (last is default).
        Block selected = (Block) weightedBlocks[0];
        for (int index = 1; index + 1 < weightedBlocks.length; index += 2)
        {
            if (roll < (Integer) weightedBlocks[index]) break;
            selected = (Block) weightedBlocks[index + 1];
        }
        return selected.defaultBlockState();
    }
}
