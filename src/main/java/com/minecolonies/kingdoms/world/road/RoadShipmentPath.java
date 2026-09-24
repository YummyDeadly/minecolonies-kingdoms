package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.caravan.ShipmentPath;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class RoadShipmentPath implements ShipmentPath
{
    private final ResourceLocation dimension;
    private final List<Vec3> points;
    private final double[] cumulative;
    private final double length;
    private final double averageSpeedMultiplier;
    private final double effectiveDistance;
    private final List<RoadSpan> spans;
    private final List<com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind> kinds;

    /** The part of the path that follows one road, as distances along the whole path. */
    public record RoadSpan(java.util.UUID roadId, double start, double end)
    {
        public double length() { return end - start; }
    }

    public RoadShipmentPath(final RoadRoute route, final RoadNetwork network)
    {
        final List<Vec3> assembled = new ArrayList<>();
        final List<com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind> assembledKinds = new ArrayList<>();
        final List<int[]> spanIndices = new ArrayList<>();
        ResourceLocation foundDimension = null;
        double weightedSpeed = 0.0D;
        double weightedLength = 0.0D;
        double effectiveLength = 0.0D;
        for (int index = 0; index < route.roadIds().size(); index++)
        {
            final RoadRecord road = network.get(route.roadIds().get(index)).orElseThrow();
            if (foundDimension == null) foundDimension = road.dimension();
            else if (!foundDimension.equals(road.dimension())) throw new IllegalArgumentException("Road route crosses dimensions");
            final boolean forward = road.firstSettlementId().equals(route.settlementIds().get(index));
            final var geometryPoints = forward ? road.geometry().points() : road.geometry().points().reversed();
            final int spanStart = Math.max(0, assembled.size() - 1);
            for (final var point : geometryPoints)
            {
                final Vec3 vector = Vec3.atCenterOf(point.position());
                if (assembled.isEmpty() || !assembled.getLast().equals(vector))
                {
                    assembled.add(vector);
                    assembledKinds.add(point.kind());
                }
            }
            spanIndices.add(new int[] {spanStart, assembled.size() - 1});
            weightedSpeed += road.length() * road.type().speedMultiplier();
            weightedLength += road.length();
            effectiveLength += road.length() / road.type().speedMultiplier();
        }
        if (assembled.size() < 2 || foundDimension == null) throw new IllegalArgumentException("Road route has no geometry");
        dimension = foundDimension;
        points = List.copyOf(assembled);
        cumulative = new double[points.size()];
        for (int index = 1; index < points.size(); index++) cumulative[index] = cumulative[index - 1] + points.get(index).distanceTo(points.get(index - 1));
        length = cumulative[cumulative.length - 1];
        final List<RoadSpan> builtSpans = new ArrayList<>();
        for (int index = 0; index < spanIndices.size(); index++)
            builtSpans.add(new RoadSpan(route.roadIds().get(index), cumulative[spanIndices.get(index)[0]],
                cumulative[Math.max(spanIndices.get(index)[0], spanIndices.get(index)[1])]));
        spans = List.copyOf(builtSpans);
        kinds = List.copyOf(assembledKinds);
        averageSpeedMultiplier = weightedLength == 0.0D ? 1.0D : weightedSpeed / weightedLength;
        effectiveDistance = effectiveLength;
    }

    @Override public ResourceLocation dimension() { return dimension; }
    @Override public double length() { return length; }
    public double speedMultiplier() { return averageSpeedMultiplier; }
    @Override public double effectiveDistance() { return effectiveDistance; }

    /** Road-by-road spans of this path, in travel order. */
    public List<RoadSpan> spans() { return spans; }

    /** Whether any path point within {@code radius} (along the path) of {@code distance} is a bridge point. */
    public boolean bridgeNear(final double distance, final double radius)
    {
        for (int index = 0; index < cumulative.length; index++)
            if (Math.abs(cumulative[index] - distance) <= radius
                && kinds.get(index) == com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind.BRIDGE) return true;
        return false;
    }

    @Override
    public Vec3 positionAt(final double progress)
    {
        return positionAtDistance(clamp(progress) * length);
    }

    @Override
    public double projectProgress(final Vec3 position)
    {
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestAlong = 0.0D;
        for (int index = 1; index < points.size(); index++)
        {
            final Vec3 start = points.get(index - 1);
            final Vec3 end = points.get(index);
            final Vec3 delta = end.subtract(start);
            final double segmentLengthSquared = delta.lengthSqr();
            final double t = segmentLengthSquared == 0.0D ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, position.subtract(start).dot(delta) / segmentLengthSquared));
            final Vec3 projected = start.add(delta.scale(t));
            final double distance = projected.distanceToSqr(position);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                bestAlong = cumulative[index - 1] + Math.sqrt(segmentLengthSquared) * t;
            }
        }
        return length == 0.0D ? 1.0D : clamp(bestAlong / length);
    }

    @Override
    public Vec3 localWaypoint(final double progress, final double distanceAhead)
    {
        return positionAtDistance(Math.min(length, clamp(progress) * length + Math.max(0.0D, distanceAhead)));
    }

    private Vec3 positionAtDistance(final double target)
    {
        if (target <= 0.0D) return points.getFirst();
        if (target >= length) return points.getLast();
        for (int index = 1; index < cumulative.length; index++)
        {
            if (target <= cumulative[index])
            {
                final double segment = cumulative[index] - cumulative[index - 1];
                final double t = segment == 0.0D ? 0.0D : (target - cumulative[index - 1]) / segment;
                return points.get(index - 1).lerp(points.get(index), t);
            }
        }
        return points.getLast();
    }

    private static double clamp(final double value) { return Math.max(0.0D, Math.min(1.0D, value)); }
}
