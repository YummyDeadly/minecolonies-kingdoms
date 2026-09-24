package com.minecolonies.kingdoms.world.settlement.layout;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;

public record LocalStreetPoint(BlockPos position, LocalStreetKind kind)
{
    public LocalStreetPoint
    {
        position = Objects.requireNonNull(position).immutable();
        Objects.requireNonNull(kind);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putLong("pos", position.asLong());
        tag.putString("kind", kind.name());
        return tag;
    }

    public static LocalStreetPoint load(final CompoundTag tag)
    {
        final LocalStreetKind kind = tag.contains("kind", Tag.TAG_STRING)
            ? LocalStreetKind.valueOf(tag.getString("kind")) : LocalStreetKind.GROUND;
        return new LocalStreetPoint(BlockPos.of(tag.getLong("pos")), kind);
    }
}
