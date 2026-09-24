package com.minecolonies.kingdoms.caravan;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public final class DirectShipmentPath implements ShipmentPath
{
    private final ResourceLocation dimension;
    private final Vec3 origin;
    private final Vec3 destination;
    private final Vec3 delta;
    private final double length;

    public DirectShipmentPath(final ResourceLocation dimension, final Vec3 origin, final Vec3 destination)
    {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.destination = Objects.requireNonNull(destination, "destination");
        delta = destination.subtract(origin);
        length = delta.length();
    }

    @Override public ResourceLocation dimension() { return dimension; }
    @Override public double length() { return length; }

    @Override
    public Vec3 positionAt(final double progress)
    {
        return origin.add(delta.scale(clamp(progress)));
    }

    @Override
    public double projectProgress(final Vec3 position)
    {
        final double lengthSquared = delta.lengthSqr();
        return lengthSquared < 1.0E-8D ? 1.0D : clamp(position.subtract(origin).dot(delta) / lengthSquared);
    }

    @Override
    public Vec3 localWaypoint(final double progress, final double distanceAhead)
    {
        final double advance = length < 1.0E-8D ? 1.0D : Math.max(0.0D, distanceAhead) / length;
        return positionAt(progress + advance);
    }

    private static double clamp(final double value)
    {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
