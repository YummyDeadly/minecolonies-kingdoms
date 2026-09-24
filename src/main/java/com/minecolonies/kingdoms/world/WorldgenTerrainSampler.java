package com.minecolonies.kingdoms.world;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Terrain heights for planning. Height is always the first walkable air block above natural ground, excluding
 * trees and replaceable plants. It never requests or loads a chunk.
 *
 * <p>{@link #generatorOnly} is used for region/road planning, whose result must not depend on which chunks happen
 * to be loaded; it is thread-safe and runs on the background planning worker. {@link #loadedOnly} is used for
 * lot/pad planning on the server thread: loaded heightmaps are cheap, exact, and agree with the physical
 * terrain-shaping recheck, while an unloaded column is reported as {@link TerrainSample#unknown()} instead of paying
 * roughly a millisecond or more per generator noise-column evaluation.</p>
 */
public final class WorldgenTerrainSampler implements TerrainSampler
{
    private static final int MAX_VEGETATION_DESCENT = 32;
    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final boolean useLoadedChunks;
    private final boolean useGenerator;
    private final java.util.Map<Long, LevelChunk> stable = new java.util.HashMap<>();

    private WorldgenTerrainSampler(final ServerLevel level, final boolean useLoadedChunks, final boolean useGenerator)
    {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.useLoadedChunks = useLoadedChunks;
        this.useGenerator = useGenerator;
    }

    public static WorldgenTerrainSampler generatorOnly(final ServerLevel level)
    {
        return new WorldgenTerrainSampler(level, false, true);
    }

    public static WorldgenTerrainSampler loadedOnly(final ServerLevel level)
    {
        return new WorldgenTerrainSampler(level, true, false);
    }

    @Override
    public TerrainSample sample(final int x, final int z)
    {
        final LevelChunk loaded = useLoadedChunks ? stableChunk(x >> 4, z >> 4) : null;
        if (loaded != null)
        {
            final int height = groundHeight(loaded, x, z);
            final Holder<Biome> biome = level.getBiome(new BlockPos(x, height, z));
            final boolean water = !loaded.getFluidState(new BlockPos(x, height - 1, z)).isEmpty()
                || biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
            final boolean allowed = biome.is(BiomeTags.IS_OVERWORLD) && !biome.is(BiomeTags.IS_OCEAN);
            return new TerrainSample(height, water, allowed);
        }
        if (!useGenerator) return TerrainSample.unknown();
        final int height = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
            level, level.getChunkSource().randomState());
        final Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x),
            QuartPos.fromBlock(height), QuartPos.fromBlock(z), level.getChunkSource().randomState().sampler());
        final boolean water = height <= generator.getSeaLevel() || biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
        final boolean allowed = biome.is(BiomeTags.IS_OVERWORLD) && !biome.is(BiomeTags.IS_OCEAN);
        return new TerrainSample(height, water, allowed);
    }

    /**
     * A loaded chunk can still receive feature blocks (boulders, trees) from a neighbour that generates later. It is
     * treated as final only when all eight neighbours are loaded too. Never loads a chunk.
     */
    private LevelChunk stableChunk(final int chunkX, final int chunkZ)
    {
        return stable.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ), ignored -> {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz) == null) return null;
            return level.getChunkSource().getChunkNow(chunkX, chunkZ);
        });
    }

    public static boolean neighbourhoodLoaded(final ServerLevel level, final int chunkX, final int chunkZ)
    {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz) == null) return false;
        return true;
    }

    /**
     * Narrower than {@link #isVegetation}: only natural plants may be removed by clearing. A non-solid artificial
     * block (torch, sign post) is ignored for ground height but is never deleted by vegetation clearing.
     */
    public static boolean isClearablePlant(final BlockState state)
    {
        if (!state.getFluidState().isEmpty() || state.isAir()) return false;
        final var block = state.getBlock();
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.canBeReplaced()
            || block instanceof net.minecraft.world.level.block.BushBlock
            || block instanceof net.minecraft.world.level.block.SugarCaneBlock
            || block instanceof net.minecraft.world.level.block.BambooStalkBlock
            || block instanceof net.minecraft.world.level.block.CactusBlock;
    }

    /** First air block above natural ground; logs, leaves, and replaceable plants/snow are skipped (bounded). */
    static int groundHeight(final LevelChunk chunk, final int x, final int z)
    {
        // ChunkAccess.getHeight returns the top block's Y (firstAvailable - 1); planning heights everywhere use the
        // generator's getBaseHeight semantics, i.e. the first free Y above the top block. Keep them identical.
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        final int floor = Math.max(chunk.getMinBuildHeight() + 1, y - MAX_VEGETATION_DESCENT);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (y > floor)
        {
            final BlockState below = chunk.getBlockState(cursor.set(x, y - 1, z));
            if (!below.getFluidState().isEmpty() || !isVegetation(below)) return y;
            y--;
        }
        return y;
    }

    /**
     * The single "not ground" definition shared by planning, physical terrain shaping, and local streets: air, logs,
     * leaves, and every non-fluid block that is replaceable or non-solid (grass, flowers, sugar cane, bushes, snow
     * layers). Planning and the physical recheck must agree, otherwise a sugar-cane stand reads as terrain.
     */
    public static boolean isVegetation(final BlockState state)
    {
        return state.isAir() || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
            || (state.getFluidState().isEmpty() && (state.canBeReplaced() || !state.isSolid()));
    }
}
