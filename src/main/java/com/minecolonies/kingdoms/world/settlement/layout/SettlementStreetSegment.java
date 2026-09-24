package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SettlementStreetSegment
{
    private final UUID id;
    private final List<LocalStreetPoint> geometry;
    private final int width;
    private final String purpose;

    public SettlementStreetSegment(final UUID id, final List<BlockPos> points, final int width, final String purpose)
    {
        this(id, points.stream().map(point -> new LocalStreetPoint(point, LocalStreetKind.GROUND)).toList(),
            width, purpose, true);
    }

    private SettlementStreetSegment(final UUID id, final List<LocalStreetPoint> geometry,
        final int width, final String purpose, final boolean ignored)
    {
        this.id = Objects.requireNonNull(id);
        this.purpose = Objects.requireNonNull(purpose).trim();
        this.geometry = List.copyOf(geometry);
        this.width = width;
        if (geometry.size() < 2 || width < 1 || width > 4 || this.purpose.isEmpty())
            throw new IllegalArgumentException("Invalid local street segment");
    }

    public static SettlementStreetSegment withGeometry(final UUID id, final List<LocalStreetPoint> geometry,
        final int width, final String purpose)
    {
        return new SettlementStreetSegment(id, geometry, width, purpose, true);
    }

    public UUID id() { return id; }
    public List<BlockPos> points() { return geometry.stream().map(LocalStreetPoint::position).toList(); }
    public List<LocalStreetPoint> geometry() { return geometry; }
    public int width() { return width; }
    public String purpose() { return purpose; }

    public boolean intersects(final StructureFootprint footprint, final int padding)
    {
        final StructureFootprint expanded = footprint.expand(padding);
        final List<BlockPos> points = points();
        for (int index = 1; index < points.size(); index++)
        {
            final BlockPos a = points.get(index - 1);
            final BlockPos b = points.get(index);
            final int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
            for (int step = 0; step <= steps; step++)
            {
                final double t = steps == 0 ? 0.0D : (double) step / steps;
                final int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
                final int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
                if (expanded.contains(x, z)) return true;
            }
        }
        return false;
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id); tag.putInt("width", width); tag.putString("purpose", purpose);
        final ListTag list = new ListTag();
        geometry.forEach(point -> list.add(point.save()));
        tag.put("points", list);
        return tag;
    }

    public static SettlementStreetSegment load(final CompoundTag tag)
    {
        final List<LocalStreetPoint> points = new ArrayList<>();
        tag.getList("points", Tag.TAG_COMPOUND).forEach(value -> points.add(LocalStreetPoint.load((CompoundTag) value)));
        return withGeometry(tag.getUUID("id"), points, tag.getInt("width"), tag.getString("purpose"));
    }
}
