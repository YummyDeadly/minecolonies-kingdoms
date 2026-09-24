package com.minecolonies.kingdoms.world.settlement.structure;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TerrainEnvelopeEvaluator
{
    public static final int MAX_SAMPLES = 5_000;

    public TerrainEnvelope evaluate(final StructureFootprint footprint, final BlockPos entrance,
        final Direction entranceFacing, final TerrainPlacementConstraints constraints, final TerrainSampler terrain)
    {
        final StructureFootprint padded = footprint.expand(constraints.perimeterPadding());
        final long footprintSamples = (long) padded.width() * padded.depth();
        if (footprintSamples + constraints.approachLength() > MAX_SAMPLES)
            return TerrainEnvelope.rejected(entrance.getY(), entrance.getY(), entrance.getY(), 0, 0, 0, 0.0D, "terrain envelope sample cap");
        final List<Integer> heights = new ArrayList<>((int) footprintSamples + constraints.approachLength());
        int water = 0;
        boolean disallowed = false;
        for (int x = padded.minX(); x <= padded.maxX(); x++) for (int z = padded.minZ(); z <= padded.maxZ(); z++)
        {
            final TerrainSample sample = terrain.sample(x, z);
            heights.add(sample.height());
            if (sample.water()) water++;
            if (!sample.allowedBiome()) disallowed = true;
        }
        int approachHeight = terrain.sample(entrance.getX(), entrance.getZ()).height();
        double maximumGrade = 0.0D;
        for (int step = 1; step <= constraints.approachLength(); step++)
        {
            final int x = entrance.getX() + entranceFacing.getStepX() * step;
            final int z = entrance.getZ() + entranceFacing.getStepZ() * step;
            final TerrainSample sample = terrain.sample(x, z);
            heights.add(sample.height());
            maximumGrade = Math.max(maximumGrade, Math.abs(sample.height() - approachHeight));
            approachHeight = sample.height();
            if (sample.water()) water++;
            if (!sample.allowedBiome()) disallowed = true;
        }
        heights.sort(Comparator.naturalOrder());
        final int base = heights.get(heights.size() / 2);
        final int min = heights.getFirst();
        final int max = heights.getLast();
        int cutFill = 0;
        for (final int height : heights) cutFill = Math.max(cutFill, Math.abs(height - base));
        int unsupported = 0;
        final int[][] corners = {{footprint.minX(), footprint.minZ()}, {footprint.maxX(), footprint.minZ()},
            {footprint.minX(), footprint.maxZ()}, {footprint.maxX(), footprint.maxZ()}};
        for (final int[] corner : corners)
            if (Math.abs(terrain.sample(corner[0], corner[1]).height() - base) > constraints.maximumCutFill()) unsupported++;
        final String reason = disallowed ? "disallowed biome" : water > constraints.maximumWaterColumns() ? "water in terrain envelope"
            : max - min > constraints.maximumHeightVariation() ? "excessive height variation"
            : maximumGrade > constraints.maximumGrade() ? "entrance approach grade"
            : cutFill > constraints.maximumCutFill() ? "excessive cut/fill"
            : unsupported > constraints.maximumUnsupportedCorners() ? "unsupported footprint corners" : null;
        return reason == null ? new TerrainEnvelope(true, base, min, max, water, unsupported, cutFill, maximumGrade, null)
            : TerrainEnvelope.rejected(base, min, max, water, unsupported, cutFill, maximumGrade, reason);
    }
}
