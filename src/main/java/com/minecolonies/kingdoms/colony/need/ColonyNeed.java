package com.minecolonies.kingdoms.colony.need;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

public record ColonyNeed(NeedType type, NeedSeverity severity, double current, double target, long createdAt)
{
    public ColonyNeed
    {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        if (!Double.isFinite(current) || !Double.isFinite(target) || current < 0.0D || target <= 0.0D)
        {
            throw new IllegalArgumentException("Need values must be finite and target must be positive");
        }
        if (createdAt < 0L)
        {
            throw new IllegalArgumentException("createdAt must be non-negative");
        }
    }

    public double shortageRatio()
    {
        return Math.max(0.0D, Math.min(1.0D, (target - current) / target));
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putString("type", type.name());
        tag.putString("severity", severity.name());
        tag.putDouble("current", current);
        tag.putDouble("target", target);
        tag.putLong("createdAt", createdAt);
        return tag;
    }

    public static ColonyNeed load(final CompoundTag tag)
    {
        return new ColonyNeed(
            NeedType.valueOf(tag.getString("type")),
            NeedSeverity.valueOf(tag.getString("severity")),
            tag.getDouble("current"),
            tag.getDouble("target"),
            Math.max(0L, tag.getLong("createdAt")));
    }
}
