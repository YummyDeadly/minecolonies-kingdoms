package com.minecolonies.kingdoms.caravan;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public interface ShipmentPath
{
    ResourceLocation dimension();

    Vec3 positionAt(double progress);

    double projectProgress(Vec3 position);

    Vec3 localWaypoint(double progress, double distanceAhead);

    double length();

    default double effectiveDistance()
    {
        return length();
    }
}
