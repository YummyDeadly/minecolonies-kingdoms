package com.minecolonies.kingdoms.world.road.geometry;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

public record RoadGeometryPoint(BlockPos position, RoadGeometryKind kind)
{
    public RoadGeometryPoint
    {
        position = Objects.requireNonNull(position).immutable();
        Objects.requireNonNull(kind);
    }
    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag(); tag.putLong("pos", position.asLong()); tag.putString("kind", kind.name()); return tag;
    }
    public static RoadGeometryPoint load(final CompoundTag tag)
    {
        return new RoadGeometryPoint(BlockPos.of(tag.getLong("pos")), RoadGeometryKind.valueOf(tag.getString("kind")));
    }
}
