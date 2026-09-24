package com.minecolonies.kingdoms.colony.decision;

import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

public record StrategicDecision(
    StrategicActionType action,
    EconomicResource resource,
    NeedType reason,
    long createdAt)
{
    public StrategicDecision
    {
        Objects.requireNonNull(action, "action");
        if (createdAt < 0L)
        {
            throw new IllegalArgumentException("createdAt must be non-negative");
        }
    }

    public static StrategicDecision none(final long gameTime)
    {
        return new StrategicDecision(StrategicActionType.NONE, null, null, gameTime);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putString("action", action.name());
        if (resource != null)
        {
            tag.putString("resource", resource.name());
        }
        if (reason != null)
        {
            tag.putString("reason", reason.name());
        }
        tag.putLong("createdAt", createdAt);
        return tag;
    }

    public static StrategicDecision load(final CompoundTag tag)
    {
        final String resourceName = tag.getString("resource");
        final String reasonName = tag.getString("reason");
        return new StrategicDecision(
            StrategicActionType.valueOf(tag.getString("action")),
            resourceName.isEmpty() ? null : EconomicResource.valueOf(resourceName),
            reasonName.isEmpty() ? null : NeedType.valueOf(reasonName),
            Math.max(0L, tag.getLong("createdAt")));
    }
}
