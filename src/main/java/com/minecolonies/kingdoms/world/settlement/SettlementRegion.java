package com.minecolonies.kingdoms.world.settlement;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record SettlementRegion(ResourceLocation dimension, int x, int z)
{
    public SettlementRegion
    {
        Objects.requireNonNull(dimension, "dimension");
    }

    public static SettlementRegion containing(final ResourceLocation dimension, final int blockX, final int blockZ,
        final int regionSize)
    {
        return new SettlementRegion(dimension, Math.floorDiv(blockX, regionSize), Math.floorDiv(blockZ, regionSize));
    }

    public String key()
    {
        return dimension + ":" + x + ":" + z;
    }
}
