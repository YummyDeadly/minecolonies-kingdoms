package com.minecolonies.kingdoms.world.settlement.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Materializes one persisted building pad slice. Planning limits are rechecked against the real chunk. */
public final class TerrainShapingChunkGenerator
{
    private static final int VEGETATION_CLEARANCE_HEIGHT = 12;
    /**
     * Planning uses generator/loaded heightmaps, which can differ from the real surface by a boulder or snow block.
     * The physical recheck allows this bounded tolerance and never more; beyond it the building is blocked.
     */
    public static final int PHYSICAL_TOLERANCE = 2;

    /** Read-only validation of one slice (block entities and physical cut/fill tolerance); writes nothing. */
    public java.util.Optional<String> preflight(final ServerLevel level, final LevelChunk chunk, final TerrainShapingPlan plan)
    {
        final BuildingPad pad = plan.pad();
        if (!pad.footprint().intersects(chunk.getPos())) return java.util.Optional.empty();
        final int minX = Math.max(chunk.getPos().getMinBlockX(), pad.footprint().minX());
        final int maxX = Math.min(chunk.getPos().getMaxBlockX(), pad.footprint().maxX());
        final int minZ = Math.max(chunk.getPos().getMinBlockZ(), pad.footprint().minZ());
        final int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), pad.footprint().maxZ());
        if (containsBlockEntity(level, pad, minX, maxX, minZ, maxZ))
            return java.util.Optional.of("BLOCK_ENTITY: block entity inside terrain-shaping footprint in chunk " + chunk.getPos());
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
        {
            final int surface = groundSurface(level, chunk, x, z, pad.targetHeight());
            final int cut = Math.max(0, surface - pad.targetHeight());
            final int fill = Math.max(0, pad.targetHeight() - surface);
            if (cut > pad.maximumCutDepth() + PHYSICAL_TOLERANCE)
                return java.util.Optional.of("EXCESSIVE_CUT: physical cut " + cut + " at " + x + "," + z
                    + " exceeds planned " + pad.maximumCutDepth() + "+" + PHYSICAL_TOLERANCE);
            if (fill > pad.maximumFillDepth() + PHYSICAL_TOLERANCE)
                return java.util.Optional.of("EXCESSIVE_FILL: physical fill " + fill + " at " + x + "," + z
                    + " exceeds planned " + pad.maximumFillDepth() + "+" + PHYSICAL_TOLERANCE);
        }
        return java.util.Optional.empty();
    }

    public Result generate(final ServerLevel level, final LevelChunk chunk, final TerrainShapingPlan plan)
    {
        final BuildingPad pad = plan.pad();
        if (!pad.footprint().intersects(chunk.getPos())) return Result.success(0);
        final int minX = Math.max(chunk.getPos().getMinBlockX(), pad.footprint().minX());
        final int maxX = Math.min(chunk.getPos().getMaxBlockX(), pad.footprint().maxX());
        final int minZ = Math.max(chunk.getPos().getMinBlockZ(), pad.footprint().minZ());
        final int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), pad.footprint().maxZ());
        if (containsBlockEntity(level, pad, minX, maxX, minZ, maxZ))
            return Result.blocked("BLOCK_ENTITY: block entity inside terrain-shaping footprint");
        // Validate the whole slice before writing anything, so a rejection never leaves partial terrain work.
        final int[][] surfaces = new int[maxX - minX + 1][maxZ - minZ + 1];
        final net.minecraft.world.level.block.state.BlockState[][] tops =
            new net.minecraft.world.level.block.state.BlockState[maxX - minX + 1][maxZ - minZ + 1];
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
        {
            final int surface = groundSurface(level, chunk, x, z, pad.targetHeight());
            surfaces[x - minX][z - minZ] = surface;
            tops[x - minX][z - minZ] = topsoil(level.getBlockState(new BlockPos(x, surface - 1, z)));
            final int cut = Math.max(0, surface - pad.targetHeight());
            final int fill = Math.max(0, pad.targetHeight() - surface);
            if (cut > pad.maximumCutDepth() + PHYSICAL_TOLERANCE)
                return Result.blocked("EXCESSIVE_CUT: physical cut " + cut + " at " + x + "," + z + " exceeds planned "
                    + pad.maximumCutDepth() + "+" + PHYSICAL_TOLERANCE);
            if (fill > pad.maximumFillDepth() + PHYSICAL_TOLERANCE)
                return Result.blocked("EXCESSIVE_FILL: physical fill " + fill + " at " + x + "," + z + " exceeds planned "
                    + pad.maximumFillDepth() + "+" + PHYSICAL_TOLERANCE);
        }
        int changed = clearVegetation(level, chunk, plan);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
        {
            final int surface = surfaces[x - minX][z - minZ];
            final var top = tops[x - minX][z - minZ];
            if (surface > pad.targetHeight())
            {
                for (int y = pad.targetHeight(); y < surface; y++)
                    changed += set(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                // A cut column keeps its natural top block (grass, sand, ...) instead of leaving a bare-dirt scar.
                changed += set(level, new BlockPos(x, pad.targetHeight() - 1, z), top);
            }
            else if (surface < pad.targetHeight())
                for (int y = surface; y < pad.targetHeight(); y++)
                    changed += set(level, new BlockPos(x, y, z), y == pad.targetHeight() - 1 ? top
                        : y == pad.targetHeight() - 2 ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
            final BlockPos foundation = new BlockPos(x, pad.targetHeight() - 1, z);
            if (level.getBlockState(foundation).isAir() || !level.getFluidState(foundation).isEmpty())
                changed += set(level, foundation, Blocks.COBBLESTONE.defaultBlockState());
        }
        for (final RetainingWall wall : pad.retainingWalls())
        {
            if (!chunk.getPos().equals(new ChunkPos(wall.start()))) continue;
            final int dx = Integer.signum(wall.end().getX() - wall.start().getX());
            final int dz = Integer.signum(wall.end().getZ() - wall.start().getZ());
            BlockPos cursor = wall.start();
            while (true)
            {
                // The top block stays the pad's topsoil, so edges read as a masonry face, not a grey curb.
                for (int y = wall.baseHeight(); y < wall.topHeight() - 1; y++)
                    changed += set(level, new BlockPos(cursor.getX(), y, cursor.getZ()), Blocks.COBBLESTONE.defaultBlockState());
                if (cursor.getX() == wall.end().getX() && cursor.getZ() == wall.end().getZ()) break;
                cursor = cursor.offset(dx, 0, dz);
            }
        }
        changed += sealLava(level, chunk, pad);
        if (changed > 0) chunk.setUnsaved(true);
        return Result.success(changed);
    }

    /** Natural top material to restore on a shaped column; anything unusual becomes plain dirt. */
    static net.minecraft.world.level.block.state.BlockState topsoil(final net.minecraft.world.level.block.state.BlockState state)
    {
        if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)) return Blocks.DIRT.defaultBlockState();
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK)
            || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.TERRACOTTA)) return state;
        return Blocks.DIRT.defaultBlockState();
    }

    /**
     * Cutting into a hillside can open a hidden vanilla lava spring. Lava in the shaped volume (footprint + 2) is
     * replaced with cobblestone before it can flow; water is left alone (planning already rejects wet pads).
     */
    public static int sealLava(final ServerLevel level, final LevelChunk chunk, final BuildingPad pad)
    {
        final var zone = pad.footprint().expand(2);
        final int minX = Math.max(chunk.getPos().getMinBlockX(), zone.minX());
        final int maxX = Math.min(chunk.getPos().getMaxBlockX(), zone.maxX());
        final int minZ = Math.max(chunk.getPos().getMinBlockZ(), zone.minZ());
        final int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), zone.maxZ());
        final int minY = Math.max(level.getMinBuildHeight(), pad.targetHeight() - pad.maximumFillDepth() - PHYSICAL_TOLERANCE - 2);
        final int maxY = Math.min(level.getMaxBuildHeight() - 1, pad.targetHeight() + pad.maximumCutDepth() + PHYSICAL_TOLERANCE + 2);
        int changed = 0;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++)
        {
            final BlockPos pos = new BlockPos(x, y, z);
            if (level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA) && level.getBlockEntity(pos) == null)
                changed += set(level, pos, Blocks.COBBLESTONE.defaultBlockState());
        }
        return changed;
    }

    private static boolean containsBlockEntity(final ServerLevel level, final BuildingPad pad,
        final int minX, final int maxX, final int minZ, final int maxZ)
    {
        final int minY = Math.max(level.getMinBuildHeight(),
            pad.targetHeight() - pad.maximumFillDepth() - PHYSICAL_TOLERANCE - 1);
        final int maxY = Math.min(level.getMaxBuildHeight() - 1,
            pad.targetHeight() + Math.max(pad.maximumCutDepth() + PHYSICAL_TOLERANCE, VEGETATION_CLEARANCE_HEIGHT));
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            for (int y = minY; y <= maxY; y++)
                if (level.getBlockEntity(new BlockPos(x, y, z)) != null) return true;
        return false;
    }

    private static int clearVegetation(final ServerLevel level, final LevelChunk chunk,
        final TerrainShapingPlan plan)
    {
        final var zone = plan.clearanceZone();
        final int minX = Math.max(chunk.getPos().getMinBlockX(), zone.minX());
        final int maxX = Math.min(chunk.getPos().getMaxBlockX(), zone.maxX());
        final int minZ = Math.max(chunk.getPos().getMinBlockZ(), zone.minZ());
        final int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), zone.maxZ());
        int changed = 0;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            for (int y = plan.pad().targetHeight(); y <= plan.pad().targetHeight() + VEGETATION_CLEARANCE_HEIGHT; y++)
            {
                final BlockPos pos = new BlockPos(x, y, z);
                if (level.getBlockEntity(pos) != null) continue;
                final var state = level.getBlockState(pos);
                if (state.is(BlockTags.LOGS))
                {
                    changed += com.minecolonies.kingdoms.world.WorldBlockEdits.removeTree(level, pos, chunk.getPos());
                    continue;
                }
                if (com.minecolonies.kingdoms.world.WorldgenTerrainSampler.isClearablePlant(state))
                    changed += set(level, pos, Blocks.AIR.defaultBlockState());
            }
        return changed;
    }

    /** Returns the first air block above non-vegetation ground. */
    private static int groundSurface(final ServerLevel level, final LevelChunk chunk,
        final int x, final int z, final int target)
    {
        int y = Math.min(level.getMaxBuildHeight() - 1,
            Math.max(target + VEGETATION_CLEARANCE_HEIGHT, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)));
        final int floor = Math.max(level.getMinBuildHeight(), target - 12);
        while (y > floor)
        {
            final BlockPos below = new BlockPos(x, y - 1, z);
            final var state = level.getBlockState(below);
            if (!com.minecolonies.kingdoms.world.WorldgenTerrainSampler.isVegetation(state)
                && level.getFluidState(below).isEmpty()) return y;
            y--;
        }
        return y;
    }

    private static int set(final ServerLevel level, final BlockPos pos,
        final net.minecraft.world.level.block.state.BlockState state)
    {
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE) ? 1 : 0;
    }

    public record Result(int changes, boolean blocked, String reason)
    {
        public static Result success(final int changes) { return new Result(changes, false, ""); }
        public static Result blocked(final String reason) { return new Result(0, true, reason); }
    }
}
