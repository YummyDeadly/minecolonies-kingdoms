package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.WorldBlockEdits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Materializes persisted local streets without taking ownership from the inter-settlement road network.
 * Each (segment, chunk) slice is written once and then marked, so repeated chunk events and repeated operator
 * materialization are no-ops. Terrain work per column is bounded: at most {@link #MAX_HEADROOM_CUT} blocks of natural
 * terrain are removed above the walking level and at most {@link #MAX_SUPPORT} blocks of support are added below it.
 */
public final class SettlementStreetChunkGenerator
{
    public static final int MAX_HEADROOM_CUT = 3;
    public static final int MAX_SUPPORT = 4;
    private static final int MAX_OVERHANG_SCAN = 8;
    private static final long PLAZA_SALT = 0x504C415A41L;
    private static final long STREET_SALT = 0x53545245L;

    public int generate(final ServerLevel level, final LevelChunk chunk, final SettlementLayoutPlan layout,
        final RoadNetwork globalRoads)
    {
        final ChunkPos chunkPos = chunk.getPos();
        final Set<Long> global = globalRoadPositions(globalRoads, chunkPos);
        final Set<Long> visited = new HashSet<>();
        int changed = 0;
        for (final SettlementStreetSegment segment : layout.streets().segments())
        {
            if (!chunks(segment).contains(chunkPos.toLong())) continue;
            if (layout.streetChunkGenerated(segment.id(), chunkPos.toLong())) continue;
            if (SettlementLayoutPlanner.PLAZA_PURPOSE.equals(segment.purpose()))
                changed += plaza(level, chunkPos, segment, global, visited);
            else
                changed += ribbon(level, chunkPos, segment, global, visited);
            layout.markStreetChunk(segment.id(), chunkPos.toLong());
        }
        if (changed > 0) chunk.setUnsaved(true);
        return changed;
    }

    /** Chunks touched by a segment including its lateral width (and gate posts). */
    public static Set<Long> chunks(final SettlementStreetSegment segment)
    {
        final Set<Long> result = new LinkedHashSet<>();
        final int reach = segment.width() + 1;
        for (final BlockPos point : segment.points())
            for (int dx = -reach; dx <= reach; dx += reach) for (int dz = -reach; dz <= reach; dz += reach)
                result.add(ChunkPos.asLong((point.getX() + dx) >> 4, (point.getZ() + dz) >> 4));
        return result;
    }

    private static int plaza(final ServerLevel level, final ChunkPos chunk, final SettlementStreetSegment segment,
        final Set<Long> global, final Set<Long> visited)
    {
        int changed = 0;
        final BlockPos center = segment.points().getFirst();
        final int radius = segment.width();
        for (final BlockPos point : segment.points())
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            {
                final BlockPos walking = new BlockPos(point.getX() + x, center.getY(), point.getZ() + z);
                final boolean border = Math.abs(x) == radius || Math.abs(z) == radius;
                final int roll = WorldBlockEdits.roll(walking.getX(), walking.getZ(), PLAZA_SALT);
                final BlockState paving = border ? (roll < 70 ? Blocks.STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS).defaultBlockState()
                    : (roll < 40 ? Blocks.POLISHED_ANDESITE : roll < 60 ? Blocks.ANDESITE : roll < 80 ? Blocks.STONE_BRICKS
                        : roll < 92 ? Blocks.COBBLESTONE : Blocks.MOSSY_STONE_BRICKS).defaultBlockState();
                changed += column(level, chunk, walking, paving, null, global, visited);
            }
        // Lantern posts inside the four plaza corners.
        for (final int[] corner : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}})
            changed += lanternPost(level, chunk, center.offset(corner[0] * (radius - 1), 0, corner[1] * (radius - 1)));
        return changed;
    }

    private static int ribbon(final ServerLevel level, final ChunkPos chunk, final SettlementStreetSegment segment,
        final Set<Long> global, final Set<Long> visited)
    {
        final List<LocalStreetPoint> points = segment.geometry();
        final Direction[] stairs = stairFacings(points);
        int changed = 0;
        for (int index = 0; index < points.size(); index++)
        {
            final LocalStreetPoint point = points.get(index);
            final Set<Direction> laterals = new LinkedHashSet<>();
            if (index > 0) laterals.add(direction(points.get(index - 1).position(), point.position()).getClockWise());
            if (index + 1 < points.size()) laterals.add(direction(point.position(), points.get(index + 1).position()).getClockWise());
            if (laterals.isEmpty()) laterals.add(Direction.EAST);
            for (final Direction lateral : laterals)
                for (int offset = -segment.width(); offset <= segment.width(); offset++)
                {
                    final BlockPos walking = point.position().relative(lateral, offset);
                    final int roll = WorldBlockEdits.roll(walking.getX(), walking.getZ(), STREET_SALT);
                    final boolean side = Math.abs(offset) == segment.width();
                    final BlockState surface = switch (point.kind())
                    {
                        case BRIDGE -> Blocks.SPRUCE_PLANKS.defaultBlockState();
                        case STAIRS -> (roll < 75 ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE).defaultBlockState();
                        case GROUND, GRADED -> (side
                            ? roll < 60 ? Blocks.DIRT_PATH : roll < 80 ? Blocks.COARSE_DIRT : roll < 92 ? Blocks.GRAVEL : Blocks.PACKED_MUD
                            : roll < 85 ? Blocks.DIRT_PATH : roll < 95 ? Blocks.COARSE_DIRT : Blocks.GRAVEL).defaultBlockState();
                    };
                    changed += column(level, chunk, walking, surface, stairs[index], global, visited);
                    if (point.kind() == LocalStreetKind.BRIDGE && Math.abs(offset) == segment.width())
                        changed += railing(level, chunk, walking.relative(lateral, Integer.signum(offset)));
                }
        }
        if ( SettlementLayoutPlanner.ROOT_PURPOSE.equals(segment.purpose()) && points.size() > 1)
        {
            final BlockPos gate = points.getFirst().position();
            final Direction lateral = direction(gate, points.get(1).position()).getClockWise();
            changed += gatePost(level, chunk, gate.relative(lateral, segment.width() + 1));
            changed += gatePost(level, chunk, gate.relative(lateral, -segment.width() - 1));
        }
        return changed;
    }

    /**
     * A step between two points becomes a stair in the lower column, at the lower walking level, facing the upper
     * column. Walking onto its low half and then its high half climbs exactly one block.
     */
    static Direction[] stairFacings(final List<LocalStreetPoint> points)
    {
        final Direction[] result = new Direction[points.size()];
        for (int index = 1; index < points.size(); index++)
        {
            final BlockPos previous = points.get(index - 1).position();
            final BlockPos current = points.get(index).position();
            if (current.getY() > previous.getY()) result[index - 1] = direction(previous, current);
            else if (current.getY() < previous.getY()) result[index] = direction(current, previous);
        }
        return result;
    }

    private static int column(final ServerLevel level, final ChunkPos chunk, final BlockPos walking,
        final BlockState surfaceState, final Direction stair, final Set<Long> global, final Set<Long> visited)
    {
        final BlockPos surface = walking.below();
        final long columnKey = BlockPos.asLong(surface.getX(), 0, surface.getZ());
        if (!chunk.equals(new ChunkPos(surface)) || global.contains(columnKey) || !visited.add(columnKey)
            || protectedColumn(level, surface)) return 0;
        int changed = 0;
        for (int y = 0; y <= MAX_OVERHANG_SCAN; y++)
            if (level.getBlockState(walking.above(y)).is(BlockTags.LOGS)) changed += WorldBlockEdits.removeTree(level, walking.above(y), chunk);
        // Never leave a floating shelf: if natural ground continues above the bounded cut, leave the column natural.
        int top = -1;
        for (int y = MAX_OVERHANG_SCAN; y >= 0 && top < 0; y--)
            if (WorldBlockEdits.isNaturalTerrain(level.getBlockState(walking.above(y)))) top = y;
        if (top > MAX_HEADROOM_CUT + 2) return changed;
        changed += clearHeadroom(level, walking, stair == null ? 0 : 1, Math.max(top, MAX_HEADROOM_CUT));
        final boolean bridge = surfaceState.is(Blocks.SPRUCE_PLANKS);
        if (!bridge)
        {
            int support = 0;
            BlockPos cursor = surface.below();
            while (support < MAX_SUPPORT && replaceableGround(level, cursor)) { support++; cursor = cursor.below(); }
            if (support >= MAX_SUPPORT && replaceableGround(level, cursor)) return changed;
            for (int offset = 1; offset <= support; offset++)
                changed += set(level, surface.below(offset), Blocks.COBBLESTONE.defaultBlockState());
        }
        changed += set(level, surface, surfaceState);
        if (stair != null)
            changed += set(level, walking, Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, stair));
        return changed;
    }

    /** Removes plants and the (bounded) natural ground above the walking level, sealing any exposed lava. */
    private static int clearHeadroom(final ServerLevel level, final BlockPos walking, final int start, final int top)
    {
        int changed = 0;
        for (int y = start; y <= top; y++)
        {
            final BlockPos pos = walking.above(y);
            final BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) continue;
            if (vegetation(state) || WorldBlockEdits.isNaturalTerrain(state))
            {
                changed += set(level, pos, Blocks.AIR.defaultBlockState());
                changed += WorldBlockEdits.sealLavaAround(level, pos);
            }
        }
        return changed;
    }

    private static int lanternPost(final ServerLevel level, final ChunkPos chunk, final BlockPos walking)
    {
        if (!chunk.equals(new ChunkPos(walking)) || protectedColumn(level, walking.below())) return 0;
        if (!level.getBlockState(walking).isAir() || replaceableGround(level, walking.below())) return 0;
        int changed = set(level, walking, Blocks.SPRUCE_FENCE.defaultBlockState());
        changed += set(level, walking.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
        if (level.getBlockState(walking.above(2)).isAir()) changed += set(level, walking.above(2), Blocks.LANTERN.defaultBlockState());
        return changed;
    }

    private static int railing(final ServerLevel level, final ChunkPos chunk, final BlockPos pos)
    {
        if (!chunk.equals(new ChunkPos(pos)) || level.getBlockEntity(pos) != null) return 0;
        final BlockState state = level.getBlockState(pos);
        if (!state.isAir() && !vegetation(state)) return 0;
        return set(level, pos, Blocks.SPRUCE_FENCE.defaultBlockState());
    }

    private static int gatePost(final ServerLevel level, final ChunkPos chunk, final BlockPos walking)
    {
        if (!chunk.equals(new ChunkPos(walking)) || protectedColumn(level, walking.below())) return 0;
        if (replaceableGround(level, walking.below())) return 0;
        int changed = 0;
        for (int y = 0; y < 3; y++)
        {
            final BlockPos pos = walking.above(y);
            final BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !vegetation(state) && !WorldBlockEdits.isNaturalTerrain(state)) return changed;
            changed += set(level, pos, Blocks.STONE_BRICKS.defaultBlockState());
        }
        final BlockPos top = walking.above(3);
        if (level.getBlockState(top).isAir()) changed += set(level, top, Blocks.LANTERN.defaultBlockState());
        return changed;
    }

    private static boolean replaceableGround(final ServerLevel level, final BlockPos pos)
    {
        final BlockState state = level.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty() || vegetation(state);
    }

    private static boolean vegetation(final BlockState state)
    {
        return com.minecolonies.kingdoms.world.WorldgenTerrainSampler.isClearablePlant(state);
    }

    private static boolean protectedColumn(final ServerLevel level, final BlockPos surface)
    {
        for (int y = surface.getY() - MAX_SUPPORT; y <= surface.getY() + MAX_HEADROOM_CUT + 2; y++)
            if (level.getBlockEntity(new BlockPos(surface.getX(), y, surface.getZ())) != null) return true;
        return false;
    }

    private static Direction direction(final BlockPos from, final BlockPos to)
    {
        final int dx = Integer.signum(to.getX() - from.getX());
        final int dz = Integer.signum(to.getZ() - from.getZ());
        return dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int set(final ServerLevel level, final BlockPos pos, final BlockState state)
    {
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE) ? 1 : 0;
    }

    private static Set<Long> globalRoadPositions(final RoadNetwork roads, final ChunkPos chunk)
    {
        final Set<Long> result = new HashSet<>();
        roads.inChunk(chunk).forEach(road -> road.geometry().points().forEach(point -> {
            final BlockPos center = point.position();
            for (int x = -road.type().width(); x <= road.type().width(); x++)
                for (int z = -road.type().width(); z <= road.type().width(); z++)
                    result.add(BlockPos.asLong(center.getX() + x, 0, center.getZ() + z));
        }));
        return result;
    }
}
