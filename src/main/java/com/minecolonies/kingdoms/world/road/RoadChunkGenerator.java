package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.world.WorldBlockEdits;
import com.minecolonies.kingdoms.world.WorldgenTerrainSampler;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Chunk-local physical roads. Geometry Y is the walking level (first free block above ground), so the pavement is
 * written at {@code Y - 1}. Coverage is a rounded band of radius {@code width + 0.5} around the centre line (every
 * direction, including diagonals, is covered without gaps); each column takes the height of its nearest centre
 * point. Per column the generator removes whole trees, cuts natural ground above the walking level (only when
 * the whole overhang fits the bound, so nothing is left floating), fills a bounded embankment below, and places a
 * mixed deterministic palette with half-slab ramps on stone roads. Bridges get decks, railings, and pillars.
 */
public final class RoadChunkGenerator
{
    static final int MAXIMUM_CUT = 6;
    static final int MAXIMUM_EMBANKMENT = 6;
    static final int MAXIMUM_PILLAR = 40;
    static final int PILLAR_SPACING = 6;

    public int generate(final ServerLevel level, final LevelChunk chunk, final RoadRecord road, final RoadSettings settings)
    {
        return generate(level, chunk, road, settings, column -> false);
    }

    /**
     * @param reserved columns ({@code BlockPos.asLong(x, 0, z)}) that belong to settlement lots or plazas; the global
     *                 road never paves over them.
     */
    public int generate(final ServerLevel level, final LevelChunk chunk, final RoadRecord road, final RoadSettings settings,
        final LongPredicate reserved)
    {
        final long chunkKey = chunk.getPos().toLong();
        if (!road.hasPhysicalGeometry() || road.status() == RoadStatus.UNROUTABLE || road.status() == RoadStatus.FAILED
            || !road.needsGeneration(chunkKey)) return 0;
        final Map<Long, Cell> cells = coverage(chunk.getPos(), road);
        if (cells.isEmpty()) { road.markGenerated(chunkKey); return 0; }
        for (final Cell cell : cells.values())
            for (int y = cell.walkingY - MAXIMUM_EMBANKMENT; y <= cell.walkingY + MAXIMUM_CUT; y++)
                if (level.getBlockEntity(new BlockPos(cell.x, y, cell.z)) != null)
                {
                    road.status(RoadStatus.PENDING);
                    return 0;
                }
        road.status(RoadStatus.GENERATING);
        final long seed = road.id().getLeastSignificantBits() ^ road.id().getMostSignificantBits();
        int changed = 0;
        for (final Cell cell : cells.values())
        {
            if (reserved.test(BlockPos.asLong(cell.x, 0, cell.z))) continue;
            final long hash = mix(cell.x * 0x9E3779B97F4A7C15L ^ cell.z * 0xC2B2AE3D27D4EB4FL ^ seed);
            if (cell.edge && cell.kind != RoadGeometryKind.BRIDGE
                && Math.floorMod(hash >>> 17, 100) >= road.type().edgeKeepPercent()) continue;
            changed += cell.kind == RoadGeometryKind.BRIDGE
                ? bridge(level, chunk.getPos(), road.type(), cell)
                : ground(level, chunk.getPos(), road.type(), cell, hash);
        }
        road.markGenerated(chunkKey);
        if (changed > 0) chunk.setUnsaved(true);
        return changed;
    }

    /** Nearest-centre assignment of every column within the road band that lies in this chunk. */
    static Map<Long, Cell> coverage(final ChunkPos chunk, final RoadRecord road)
    {
        final List<RoadGeometryPoint> points = road.geometry().points();
        final int width = road.type().width();
        final double outer = (width + 0.5D) * (width + 0.5D);
        final double inner = width == 0 ? -1.0D : (width - 0.5D) * (width - 0.5D);
        final Map<Long, Cell> cells = new LinkedHashMap<>();
        for (int index = 0; index < points.size(); index++)
        {
            final BlockPos center = points.get(index).position();
            if (center.getX() + width < chunk.getMinBlockX() || center.getX() - width > chunk.getMaxBlockX()
                || center.getZ() + width < chunk.getMinBlockZ() || center.getZ() - width > chunk.getMaxBlockZ()) continue;
            final boolean ramp = neighbourHigher(points, index);
            for (int dx = -width; dx <= width; dx++) for (int dz = -width; dz <= width; dz++)
            {
                final int distance = dx * dx + dz * dz;
                if (distance > outer) continue;
                final int x = center.getX() + dx;
                final int z = center.getZ() + dz;
                if (x < chunk.getMinBlockX() || x > chunk.getMaxBlockX() || z < chunk.getMinBlockZ() || z > chunk.getMaxBlockZ()) continue;
                final long key = BlockPos.asLong(x, 0, z);
                final Cell existing = cells.get(key);
                if (existing != null && existing.distance <= distance) continue;
                cells.put(key, new Cell(x, z, center.getY(), points.get(index).kind(), distance, distance > inner,
                    ramp && distance <= inner + 1, index));
            }
        }
        return cells;
    }

    private static boolean neighbourHigher(final List<RoadGeometryPoint> points, final int index)
    {
        final int y = points.get(index).position().getY();
        return index > 0 && points.get(index - 1).position().getY() > y
            || index + 1 < points.size() && points.get(index + 1).position().getY() > y;
    }

    private static int ground(final ServerLevel level, final ChunkPos chunk, final RoadType type, final Cell cell, final long hash)
    {
        final BlockPos walking = new BlockPos(cell.x, cell.walkingY, cell.z);
        final BlockPos surface = walking.below();
        int changed = 0;
        // Trees standing in the band are removed whole so no trunk or permanently floating canopy remains.
        for (int y = 0; y <= MAXIMUM_CUT + 2; y++)
        {
            final BlockPos pos = walking.above(y);
            if (level.getBlockState(pos).is(BlockTags.LOGS)) changed += WorldBlockEdits.removeTree(level, pos, chunk);
        }
        // Cut: remove natural ground above the walking level only if the whole overhang is within the bound.
        int top = -1;
        for (int y = MAXIMUM_CUT + 2; y >= 0 && top < 0; y--)
            if (WorldBlockEdits.isNaturalTerrain(level.getBlockState(walking.above(y)))) top = y;
        if (top > MAXIMUM_CUT) return changed;
        for (int y = 0; y <= Math.max(top, 3); y++)
        {
            final BlockPos pos = walking.above(y);
            final BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) continue;
            if (WorldBlockEdits.isNaturalTerrain(state) || WorldgenTerrainSampler.isClearablePlant(state))
            {
                changed += set(level, pos, Blocks.AIR.defaultBlockState());
                changed += WorldBlockEdits.sealLavaAround(level, pos);
            }
        }
        // Embankment: bounded support under the pavement; deeper gaps are not bridged by ground roads.
        final BlockState original = level.getBlockState(surface);
        final boolean sandy = original.is(BlockTags.SAND) || original.is(Blocks.SANDSTONE);
        int depth = 0;
        while (depth < MAXIMUM_EMBANKMENT && replaceable(level, surface.below(depth + 1))) depth++;
        if (depth >= MAXIMUM_EMBANKMENT && replaceable(level, surface.below(depth + 1))) return changed;
        final BlockState support = RoadPalette.support(type, sandy);
        for (int y = 1; y <= depth; y++) changed += set(level, surface.below(y), support);
        changed += set(level, surface, RoadPalette.surface(type, sandy, cell.edge, hash));
        final BlockState slab = cell.ramp ? RoadPalette.slab(type, sandy) : null;
        if (slab != null) changed += set(level, walking, slab);
        return changed;
    }

    private static int bridge(final ServerLevel level, final ChunkPos chunk, final RoadType type, final Cell cell)
    {
        final BlockPos walking = new BlockPos(cell.x, cell.walkingY, cell.z);
        final BlockPos deck = walking.below();
        int changed = 0;
        for (int y = 0; y <= 3; y++)
        {
            final BlockPos pos = walking.above(y);
            final BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.LOGS)) changed += WorldBlockEdits.removeTree(level, pos, chunk);
            else if (!state.isAir() && state.getFluidState().isEmpty() && WorldgenTerrainSampler.isClearablePlant(state))
                changed += set(level, pos, Blocks.AIR.defaultBlockState());
        }
        changed += set(level, deck, RoadPalette.deck(type));
        if (cell.edge)
        {
            changed += set(level, walking, RoadPalette.railing(type));
            if (Math.floorMod(cell.index, PILLAR_SPACING) == 0)
                for (int y = 1; y <= MAXIMUM_PILLAR && replaceable(level, deck.below(y)); y++)
                    changed += set(level, deck.below(y), RoadPalette.pillar(type));
        }
        return changed;
    }

    private static boolean replaceable(final ServerLevel level, final BlockPos pos)
    {
        final BlockState state = level.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty() || WorldgenTerrainSampler.isClearablePlant(state);
    }

    private static int set(final ServerLevel level, final BlockPos pos, final BlockState state)
    {
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE) ? 1 : 0;
    }

    private static long mix(long value)
    {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /** One paved column: owner centre height/kind, squared distance, outer-ring flag, ramp flag, centre index. */
    record Cell(int x, int z, int walkingY, RoadGeometryKind kind, int distance, boolean edge, boolean ramp, int index) {}
}
