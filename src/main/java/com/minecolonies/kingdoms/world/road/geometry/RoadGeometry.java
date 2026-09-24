package com.minecolonies.kingdoms.world.road.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RoadGeometry
{
    private final List<RoadGeometryPoint> points;
    private final double length;

    public RoadGeometry(final List<RoadGeometryPoint> points)
    {
        if (points.size() < 2) throw new IllegalArgumentException("Road geometry requires two points");
        final List<RoadGeometryPoint> copy = new ArrayList<>();
        for (final RoadGeometryPoint point : points)
            if (copy.isEmpty() || !copy.getLast().position().equals(point.position())) copy.add(point);
        if (copy.size() < 2) throw new IllegalArgumentException("Road geometry has no length");
        this.points = List.copyOf(copy);
        double measured = 0.0D;
        for (int index = 1; index < copy.size(); index++) measured += Math.sqrt(copy.get(index).position().distSqr(copy.get(index - 1).position()));
        length = measured;
    }

    public static RoadGeometry legacy(final List<BlockPos> polyline)
    {
        return new RoadGeometry(polyline.stream().map(point -> new RoadGeometryPoint(point, RoadGeometryKind.LEGACY)).toList());
    }
    public List<RoadGeometryPoint> points() { return points; }
    public List<BlockPos> positions() { return points.stream().map(RoadGeometryPoint::position).toList(); }
    public double length() { return length; }
    public Set<Long> chunks()
    {
        final Set<Long> chunks = new LinkedHashSet<>(); points.forEach(point -> chunks.add(new ChunkPos(point.position()).toLong())); return Set.copyOf(chunks);
    }
    public List<RoadGeometryPoint> inChunk(final ChunkPos chunk)
    {
        return points.stream().filter(point -> new ChunkPos(point.position()).equals(chunk)).toList();
    }
    public List<RoadGeometryPoint> intersecting(final ChunkPos chunk, final int radius)
    {
        return points.stream().filter(point -> point.position().getX() + radius >= chunk.getMinBlockX()
            && point.position().getX() - radius <= chunk.getMaxBlockX()
            && point.position().getZ() + radius >= chunk.getMinBlockZ()
            && point.position().getZ() - radius <= chunk.getMaxBlockZ()).toList();
    }
    public List<RoadBorderPortal> portals(final ChunkPos chunk)
    {
        final List<RoadBorderPortal> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++)
        {
            final BlockPos pos = points.get(index).position();
            if (!new ChunkPos(pos).equals(chunk)) continue;
            final boolean boundary = Math.floorMod(pos.getX(), 16) <= 1 || Math.floorMod(pos.getX(), 16) >= 14
                || Math.floorMod(pos.getZ(), 16) <= 1 || Math.floorMod(pos.getZ(), 16) >= 14;
            if (boundary) result.add(new RoadBorderPortal(chunk.toLong(), pos));
        }
        return List.copyOf(result);
    }
    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag(); final ListTag list = new ListTag(); points.forEach(point -> list.add(point.save())); tag.put("points", list); return tag;
    }
    public static RoadGeometry load(final CompoundTag tag)
    {
        final List<RoadGeometryPoint> points = new ArrayList<>();
        tag.getList("points", Tag.TAG_COMPOUND).forEach(value -> points.add(RoadGeometryPoint.load((CompoundTag) value)));
        return new RoadGeometry(points);
    }
}
