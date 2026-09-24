package com.minecolonies.kingdoms.world.road.geometry;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.world.road.RoadSettings;
import com.minecolonies.kingdoms.world.road.RoadTerrainPlanner;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.road.RoadTerrainPlanner;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RoadGeometryPlanner
{
    private final RoadTerrainPlanner coarse = new RoadTerrainPlanner();

    public Optional<RoadGeometry> plan(final BlockPos start, final BlockPos end, final TerrainSampler terrain,
        final RoadSettings settings)
    {
        return plan(start, start, end, end, List.of(), terrain, settings);
    }

    /**
     * Gate-to-gate road that first leaves each gate straight outward to its approach point (the corridor kept free
     * in front of every settlement gate) and routes between the approach points around settlement cores.
     */
    public Optional<RoadGeometry> plan(final BlockPos start, final BlockPos startApproach, final BlockPos endApproach,
        final BlockPos end, final List<RoadTerrainPlanner.Obstacle> obstacles, final TerrainSampler terrain,
        final RoadSettings settings)
    {
        final Optional<List<BlockPos>> corridor = coarse.findPath(startApproach, endApproach, terrain, settings, obstacles);
        if (corridor.isEmpty()) return failure(start, end, "coarse search exhausted or rejected terrain");
        // A smoothed centre line (curves instead of long straights with 45-degree kinks) is tried first; if its
        // corners cut into terrain that the grade/bridge rules reject, the unsmoothed corridor is used instead.
        final List<BlockPos> raw = withGates(start, corridor.orElseThrow(), end);
        final String[] reason = {""};
        final List<BlockPos> smoothed = withGates(start, smooth(corridor.orElseThrow(), SMOOTHING_ITERATIONS), end);
        if (!smoothed.equals(raw))
        {
            final Optional<RoadGeometry> curved = profile(start, end, refine(smoothed, terrain, settings), terrain, settings, reason);
            if (curved.isPresent()) return curved;
        }
        final Optional<RoadGeometry> straight = profile(start, end, refine(raw, terrain, settings), terrain, settings, reason);
        return straight.isPresent() ? straight : failure(start, end, reason[0]);
    }

    private static List<BlockPos> withGates(final BlockPos start, final List<BlockPos> corridor, final BlockPos end)
    {
        final List<BlockPos> result = new ArrayList<>(corridor.size() + 2);
        if (!sameColumn(start, corridor.getFirst())) result.add(start);
        result.addAll(corridor);
        if (!sameColumn(end, corridor.getLast())) result.add(end);
        return List.copyOf(result);
    }

    private static boolean sameColumn(final BlockPos a, final BlockPos b)
    {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    static final int SMOOTHING_ITERATIONS = 2;

    /** Chaikin corner cutting with fixed endpoints; bounded (each iteration doubles the interior node count). */
    public static List<BlockPos> smooth(final List<BlockPos> nodes, final int iterations)
    {
        List<BlockPos> current = nodes;
        for (int iteration = 0; iteration < iterations && current.size() > 2; iteration++)
        {
            final List<BlockPos> next = new ArrayList<>(current.size() * 2);
            next.add(current.getFirst());
            for (int index = 0; index + 1 < current.size(); index++)
            {
                final BlockPos a = current.get(index);
                final BlockPos b = current.get(index + 1);
                if (index > 0) next.add(lerp(a, b, 0.25D));
                if (index + 2 < current.size()) next.add(lerp(a, b, 0.75D));
            }
            next.add(current.getLast());
            current = next;
        }
        return List.copyOf(current);
    }

    private static BlockPos lerp(final BlockPos a, final BlockPos b, final double t)
    {
        return new BlockPos((int) Math.round(a.getX() + (b.getX() - a.getX()) * t), a.getY(),
            (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
    }

    private Optional<RoadGeometry> profile(final BlockPos start, final BlockPos end, final List<BlockPos> horizontal,
        final TerrainSampler terrain, final RoadSettings settings, final String[] reason)
    {
        if (horizontal.size() < 2) return reject(reason, "fine corridor search exhausted safety bounds");
        // Pass 1: bridge spans are fixed; ground columns keep their terrain height for the profile pass.
        final int count = horizontal.size();
        final int[] terrainY = new int[count];
        final int[] y = new int[count];
        final boolean[] bridge = new boolean[count];
        int index = 0;
        while (index < count)
        {
            final BlockPos current = horizontal.get(index);
            final TerrainSample sample = terrain.sample(current.getX(), current.getZ());
            if (sample.water() || isGap(horizontal, index, terrain, settings))
            {
                final TerrainSample leftBank = index == 0 ? sample : terrain.sample(horizontal.get(index - 1).getX(), horizontal.get(index - 1).getZ());
                final int bank = findNextBank(horizontal, index + 1, terrain, settings.maximumBridgeSpan(),
                    leftBank.height(), settings.maximumBridgeBankDelta());
                if (bank < 0) return reject(reason, "water/ravine span exceeds bridge bounds");
                final TerrainSample rightBank = terrain.sample(horizontal.get(bank).getX(), horizontal.get(bank).getZ());
                if (Math.abs(leftBank.height() - rightBank.height()) > settings.maximumBridgeBankDelta())
                    return reject(reason, "bridge banks exceed height delta");
                final int bridgeY = Math.max(leftBank.height(), rightBank.height());
                for (int span = index; span < bank; span++) { bridge[span] = true; y[span] = bridgeY; terrainY[span] = bridgeY; }
                index = bank;
                continue;
            }
            terrainY[index] = sample.height();
            index++;
        }
        final int step = (int) Math.floor(settings.maximumGroundGrade());
        if (step < 1) return reject(reason, "configured ground grade cannot change block elevation");
        // Pass 2: symmetric slope-limited profile. The midpoint of the largest cut envelope and the smallest fill
        // envelope spreads a terrain step over both sides instead of lagging behind it (the one-directional
        // clamp failed on any local rise above the bank delta).
        final int[] fillEnvelope = terrainY.clone();
        final int[] cutEnvelope = terrainY.clone();
        for (int i = 1; i < count; i++)
        {
            fillEnvelope[i] = Math.max(fillEnvelope[i], fillEnvelope[i - 1] - step);
            cutEnvelope[i] = Math.min(cutEnvelope[i], cutEnvelope[i - 1] + step);
        }
        for (int i = count - 2; i >= 0; i--)
        {
            fillEnvelope[i] = Math.max(fillEnvelope[i], fillEnvelope[i + 1] - step);
            cutEnvelope[i] = Math.min(cutEnvelope[i], cutEnvelope[i + 1] + step);
        }
        for (int i = 0; i < count; i++)
            if (!bridge[i]) y[i] = Math.floorDiv(fillEnvelope[i] + cutEnvelope[i], 2);
        // Pass 3: bridge decks are fixed anchors; ground next to them is clamped towards the deck in both directions.
        for (int i = 1; i < count; i++)
            if (!bridge[i]) y[i] = Math.max(y[i - 1] - step, Math.min(y[i - 1] + step, y[i]));
        for (int i = count - 2; i >= 0; i--)
            if (!bridge[i]) y[i] = Math.max(y[i + 1] - step, Math.min(y[i + 1] + step, y[i]));
        final List<RoadGeometryPoint> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            if (i > 0 && !(bridge[i] && bridge[i - 1]) && Math.abs(y[i] - y[i - 1]) > step)
                return reject(reason, "graded profile cannot meet bridge deck within grade");
            if (bridge[i]) { result.add(new RoadGeometryPoint(horizontal.get(i).atY(y[i]), RoadGeometryKind.BRIDGE)); continue; }
            if (Math.abs(terrainY[i] - y[i]) > settings.maximumBridgeBankDelta())
                return reject(reason, "graded cut/fill exceeds bound");
            final boolean graded = y[i] != terrainY[i] || (i > 0 && y[i] != y[i - 1]);
            result.add(new RoadGeometryPoint(horizontal.get(i).atY(y[i]),
                i > 0 && graded ? RoadGeometryKind.GRADED : RoadGeometryKind.GROUND));
        }
        if (result.size() < 2) return reject(reason, "geometry collapsed below two points");
        return Optional.of(new RoadGeometry(result));
    }

    private static Optional<RoadGeometry> reject(final String[] reason, final String value)
    {
        reason[0] = value;
        return Optional.empty();
    }

    private static Optional<RoadGeometry> failure(final BlockPos start, final BlockPos end, final String reason)
    {
        KingdomsMod.LOGGER.warn("Road geometry {} -> {} is unrouteable: {}", start.toShortString(), end.toShortString(), reason);
        return Optional.empty();
    }

    private static boolean isGap(final List<BlockPos> line, final int index, final TerrainSampler terrain, final RoadSettings settings)
    {
        if (index == 0 || index + 1 >= line.size()) return false;
        final int previous = terrain.sample(line.get(index - 1).getX(), line.get(index - 1).getZ()).height();
        final int current = terrain.sample(line.get(index).getX(), line.get(index).getZ()).height();
        final int next = terrain.sample(line.get(index + 1).getX(), line.get(index + 1).getZ()).height();
        return previous - current > settings.ravineDepthThreshold() || next - current > settings.ravineDepthThreshold();
    }

    private static int findNextBank(final List<BlockPos> line, final int start, final TerrainSampler terrain,
        final int maximumSpan, final int expectedHeight, final int maximumBankDelta)
    {
        final int limit = Math.min(line.size(), start + maximumSpan + 1);
        for (int index = start; index < limit; index++)
        {
            final TerrainSample sample = terrain.sample(line.get(index).getX(), line.get(index).getZ());
            if (!sample.water() && Math.abs(sample.height() - expectedHeight) <= maximumBankDelta) return index;
        }
        return -1;
    }

    /** Bresenham rasterisation between corridor nodes (straight at any angle, 8-connected, bounded). */
    private static List<BlockPos> refine(final List<BlockPos> corridor, final TerrainSampler terrain,
        final RoadSettings settings)
    {
        final List<BlockPos> result = new ArrayList<>();
        result.add(corridor.getFirst().immutable());
        for (int index = 1; index < corridor.size(); index++)
        {
            final BlockPos from = result.getLast();
            final BlockPos to = corridor.get(index);
            final int dx = Math.abs(to.getX() - from.getX());
            final int dz = Math.abs(to.getZ() - from.getZ());
            final int sx = Integer.signum(to.getX() - from.getX());
            final int sz = Integer.signum(to.getZ() - from.getZ());
            int error = dx - dz;
            int x = from.getX();
            int z = from.getZ();
            while (x != to.getX() || z != to.getZ())
            {
                final int doubled = error * 2;
                if (doubled > -dz) { error -= dz; x += sx; }
                if (doubled < dx) { error += dx; z += sz; }
                result.add(new BlockPos(x, from.getY(), z));
                if (result.size() > settings.maximumFinePathPoints()) return List.of();
            }
        }
        return List.copyOf(result);
    }

}
