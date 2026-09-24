package com.minecolonies.kingdoms.world.settlement.terrain;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TerrainShapingPlanner
{
    public static final int MAX_SAMPLES = 4_096;

    public TerrainShapingEvaluation plan(final StructureFootprint footprint, final TerrainSampler terrain,
        final TerrainShapingSettings settings)
    {
        final long area = (long) footprint.width() * footprint.depth();
        if (area > MAX_SAMPLES)
            return TerrainShapingEvaluation.rejected(TerrainShapingRejection.SAMPLE_LIMIT, 0, 0, 0, 0, 0);
        final List<Integer> heights = new ArrayList<>((int) area);
        boolean water = false;
        boolean disallowed = false;
        boolean unknown = false;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++)
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++)
            {
                final TerrainSample sample = terrain.sample(x, z);
                heights.add(sample.height()); water |= sample.water(); disallowed |= !sample.allowedBiome();
                unknown |= !sample.known();
            }
        if (unknown) return TerrainShapingEvaluation.rejected(TerrainShapingRejection.UNLOADED_TERRAIN,
            heights.size(), 0, 0, 0, 0);
        if (disallowed) return TerrainShapingEvaluation.rejected(TerrainShapingRejection.DISALLOWED_BIOME,
            heights.size(), 0, 0, 0, 0);
        if (water) return TerrainShapingEvaluation.rejected(TerrainShapingRejection.WATER,
            heights.size(), 0, 0, 0, 0);
        heights.sort(Comparator.naturalOrder());
        final int min = heights.getFirst(); final int max = heights.getLast();
        if (max - min > settings.maximumPadHeightVariance())
            return TerrainShapingEvaluation.rejected(TerrainShapingRejection.EXCESSIVE_HEIGHT_VARIANCE,
                heights.size(), 0, 0, 0, 0);
        Candidate best = null;
        for (int target = min; target <= max; target++)
        {
            int cut = 0, fill = 0, maxCut = 0, maxFill = 0;
            for (final int height : heights)
            {
                if (height > target) { cut += height - target; maxCut = Math.max(maxCut, height - target); }
                else { fill += target - height; maxFill = Math.max(maxFill, target - height); }
            }
            if (maxCut > settings.maximumCutDepth() || maxFill > settings.maximumFillDepth()) continue;
            final Candidate candidate = new Candidate(target, cut, fill, maxCut, maxFill);
            if (best == null || Candidate.ORDER.compare(candidate, best) < 0) best = candidate;
        }
        if (best == null)
        {
            final int median = heights.get(heights.size() / 2);
            final int maxCut = max - median; final int maxFill = median - min;
            return TerrainShapingEvaluation.rejected(maxCut > settings.maximumCutDepth()
                ? TerrainShapingRejection.EXCESSIVE_CUT : TerrainShapingRejection.EXCESSIVE_FILL,
                heights.size(), 0, 0, maxCut, maxFill);
        }
        if (best.cut + best.fill > settings.maximumTerrainWorkVolume())
            return TerrainShapingEvaluation.rejected(TerrainShapingRejection.EXCESSIVE_TERRAIN_VOLUME,
                heights.size(), best.cut, best.fill, best.maxCut, best.maxFill);
        final List<RetainingWall> walls = walls(footprint, best.target, terrain, settings.maximumRetainingWallHeight());
        if (walls == null)
            return TerrainShapingEvaluation.rejected(TerrainShapingRejection.RETAINING_WALL_TOO_HIGH,
                heights.size(), best.cut, best.fill, best.maxCut, best.maxFill);
        final TerrainShapingMode mode = best.cut == 0 && best.fill == 0 ? TerrainShapingMode.NATURAL
            : best.cut == 0 ? TerrainShapingMode.FILL : best.fill == 0 ? TerrainShapingMode.CUT
            : TerrainShapingMode.CUT_AND_FILL;
        final BuildingPad pad = new BuildingPad(footprint, best.target, min, max, best.cut, best.fill,
            best.maxCut, best.maxFill, mode, walls);
        return new TerrainShapingEvaluation(new TerrainShapingPlan(TerrainShapingPlan.CURRENT_VERSION, pad,
            footprint.expand(settings.clearancePadding())), TerrainShapingRejection.NONE, heights.size(),
            best.cut, best.fill, best.maxCut, best.maxFill);
    }

    private static List<RetainingWall> walls(final StructureFootprint footprint, final int target,
        final TerrainSampler terrain, final int maximumHeight)
    {
        final List<RetainingWall> result = new ArrayList<>();
        // An unknown outside column (unloaded neighbor chunk) is treated as level ground: no wall is planned there,
        // and the physical recheck still validates the real pad slice before writing.
        for (int x = footprint.minX(); x <= footprint.maxX(); x++)
        {
            if (!wall(result, x, footprint.minZ(), terrain.sample(x, footprint.minZ() - 1), target, maximumHeight)) return null;
            if (!wall(result, x, footprint.maxZ(), terrain.sample(x, footprint.maxZ() + 1), target, maximumHeight)) return null;
        }
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++)
        {
            if (!wall(result, footprint.minX(), z, terrain.sample(footprint.minX() - 1, z), target, maximumHeight)) return null;
            if (!wall(result, footprint.maxX(), z, terrain.sample(footprint.maxX() + 1, z), target, maximumHeight)) return null;
        }
        return List.copyOf(result);
    }

    private static boolean wall(final List<RetainingWall> result, final int x, final int z,
        final TerrainSample outside, final int target, final int maximumHeight)
    {
        // A one-block drop is a natural grass step; masonry is only used where a real face is exposed.
        if (!outside.known() || outside.height() >= target - 1) return true;
        final int outsideHeight = outside.height();
        if (target - outsideHeight > maximumHeight) return false;
        final BlockPos point = new BlockPos(x, target, z);
        result.add(new RetainingWall(point, point, outsideHeight, target)); return true;
    }

    private record Candidate(int target, int cut, int fill, int maxCut, int maxFill)
    {
        private static final Comparator<Candidate> ORDER = Comparator
            .comparingInt((Candidate value) -> value.cut + value.fill)
            .thenComparingInt(value -> Math.abs(value.cut - value.fill))
            .thenComparingInt(Candidate::target);
    }
}
