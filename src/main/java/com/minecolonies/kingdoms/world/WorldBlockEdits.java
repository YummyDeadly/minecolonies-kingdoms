package com.minecolonies.kingdoms.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Small, bounded block edits shared by settlement pads, local streets, and global roads. */
public final class WorldBlockEdits
{
    public static final int MAX_TREE_LOGS = 192;
    private static final int TREE_RADIUS = 6;

    private WorldBlockEdits() {}

    /**
     * Removes the connected trunk/branch logs of a tree whose log stands at {@code start}, restricted to one chunk
     * and a bounded radius. Logs are removed with full block updates so the canopy's leaves recompute their
     * distance and decay naturally instead of floating forever (removal with {@code UPDATE_KNOWN_SHAPE} never
     * notifies the leaves).
     */
    public static int removeTree(final ServerLevel level, final BlockPos start, final ChunkPos chunk)
    {
        if (!level.getBlockState(start).is(BlockTags.LOGS)) return 0;
        final ArrayDeque<BlockPos> open = new ArrayDeque<>();
        final Set<Long> seen = new HashSet<>();
        open.add(start.immutable());
        seen.add(start.asLong());
        int removed = 0;
        while (!open.isEmpty() && removed < MAX_TREE_LOGS)
        {
            final BlockPos pos = open.removeFirst();
            if (level.getBlockEntity(pos) != null) continue;
            if (level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) removed++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
            {
                final BlockPos next = pos.offset(dx, dy, dz);
                if (!chunk.equals(new ChunkPos(next)) || next.getY() < start.getY() - 1
                    || Math.abs(next.getX() - start.getX()) > TREE_RADIUS || Math.abs(next.getZ() - start.getZ()) > TREE_RADIUS
                    || !seen.add(next.asLong())) continue;
                if (level.getBlockState(next).is(BlockTags.LOGS)) open.addLast(next);
            }
        }
        return removed;
    }

    /** Stable per-column hash for deterministic material variety. */
    public static int roll(final int x, final int z, final long salt)
    {
        long value = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return Math.floorMod(value ^ (value >>> 31), 100);
    }

    /** Natural ground that local streets and roads may cut (never ores, player blocks, or block entities). */
    public static boolean isNaturalTerrain(final BlockState state)
    {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
            || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.CLAY)
            || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.ICE)
            || state.is(Blocks.PACKED_ICE);
    }

    /** Seals lava next to a freshly opened position before it can flow (hidden vanilla springs). */
    public static int sealLavaAround(final ServerLevel level, final BlockPos pos)
    {
        int changed = 0;
        for (final Direction direction : Direction.values())
        {
            final BlockPos next = pos.relative(direction);
            if (level.getFluidState(next).is(FluidTags.LAVA) && level.getBlockEntity(next) == null
                && level.setBlock(next, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS)) changed++;
        }
        return changed;
    }
}
