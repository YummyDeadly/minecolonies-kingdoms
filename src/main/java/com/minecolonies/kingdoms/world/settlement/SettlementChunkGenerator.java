package com.minecolonies.kingdoms.world.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

public final class SettlementChunkGenerator
{
    public boolean intersects(final SettlementRecord settlement, final ChunkPos chunk)
    {
        final int radius = settlement.type().footprintRadius();
        return settlement.anchor().getX() + radius >= chunk.getMinBlockX()
            && settlement.anchor().getX() - radius <= chunk.getMaxBlockX()
            && settlement.anchor().getZ() + radius >= chunk.getMinBlockZ()
            && settlement.anchor().getZ() - radius <= chunk.getMaxBlockZ();
    }

    public int generate(final ServerLevel level, final LevelChunk chunk, final SettlementRecord settlement)
    {
        if (!intersects(settlement, chunk.getPos()) || settlement.isChunkGenerated(chunk.getPos().toLong())) return 0;
        settlement.physicalState(SettlementPhysicalState.GENERATING);
        int changed = 0;
        for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++)
        {
            for (int z = chunk.getPos().getMinBlockZ(); z <= chunk.getPos().getMaxBlockZ(); z++)
            {
                final int dx = x - settlement.anchor().getX();
                final int dz = z - settlement.anchor().getZ();
                final int radius = settlement.type().footprintRadius();
                if (Math.abs(dx) > radius || Math.abs(dz) > radius) continue;
                final int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (surface <= level.getMinBuildHeight() || surface + 5 >= level.getMaxBuildHeight()) continue;
                final boolean avenue = Math.abs(dx) <= 1 || Math.abs(dz) <= 1;
                final boolean plaza = Math.abs(dx) <= 5 && Math.abs(dz) <= 5;
                if (avenue || plaza)
                {
                    changed += set(level, new BlockPos(x, surface, z), plaza ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState());
                    clear(level, x, surface + 1, z, 3);
                }
                changed += building(level, settlement, x, z, surface, dx, dz);
                if ((settlement.type() == SettlementType.CASTLE || settlement.type() == SettlementType.FORT)
                    && (Math.abs(dx) == radius || Math.abs(dz) == radius))
                {
                    changed += set(level, new BlockPos(x, surface, z), Blocks.COBBLESTONE.defaultBlockState());
                    changed += set(level, new BlockPos(x, surface + 1, z), Blocks.STONE_BRICKS.defaultBlockState());
                    changed += set(level, new BlockPos(x, surface + 2, z), Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
        }
        settlement.markChunkGenerated(chunk.getPos().toLong());
        if (allFootprintChunksGenerated(settlement)) settlement.physicalState(SettlementPhysicalState.GENERATED);
        chunk.setUnsaved(true);
        return changed;
    }

    private static int building(final ServerLevel level, final SettlementRecord settlement, final int x, final int z,
        final int surface, final int dx, final int dz)
    {
        final int[][] centers = {{10,10},{-10,10},{10,-10},{-10,-10}};
        int changed = 0;
        for (final int[] center : centers)
        {
            final int bx = dx - center[0];
            final int bz = dz - center[1];
            if (Math.abs(bx) > 3 || Math.abs(bz) > 3) continue;
            changed += set(level, new BlockPos(x, surface, z), Blocks.OAK_PLANKS.defaultBlockState());
            final boolean wall = Math.abs(bx) == 3 || Math.abs(bz) == 3;
            if (wall && !(bz == 3 && bx == 0))
            {
                for (int y = 1; y <= 3; y++) changed += set(level, new BlockPos(x, surface + y, z), Blocks.OAK_LOG.defaultBlockState());
            }
            else clear(level, x, surface + 1, z, 3);
            if (wall || Math.abs(bx) <= 3 && Math.abs(bz) <= 3)
                changed += set(level, new BlockPos(x, surface + 4, z), Blocks.OAK_SLAB.defaultBlockState());
        }
        return changed;
    }

    private static int set(final ServerLevel level, final BlockPos pos, final BlockState state)
    {
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE) ? 1 : 0;
    }

    private static void clear(final ServerLevel level, final int x, final int y, final int z, final int height)
    {
        for (int offset = 0; offset < height; offset++)
            level.setBlock(new BlockPos(x, y + offset, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static boolean allFootprintChunksGenerated(final SettlementRecord settlement)
    {
        final int radius = settlement.type().footprintRadius();
        final int minX = (settlement.anchor().getX() - radius) >> 4;
        final int maxX = (settlement.anchor().getX() + radius) >> 4;
        final int minZ = (settlement.anchor().getZ() - radius) >> 4;
        final int maxZ = (settlement.anchor().getZ() + radius) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (!settlement.isChunkGenerated(ChunkPos.asLong(x, z))) return false;
        return true;
    }
}
