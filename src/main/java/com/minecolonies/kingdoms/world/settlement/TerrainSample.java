package com.minecolonies.kingdoms.world.settlement;

/**
 * One planning column. {@code known=false} means the sampler was not allowed to evaluate this column (for example an
 * unloaded chunk during autonomous lot planning); planners must reject rather than guess.
 */
public record TerrainSample(int height, boolean water, boolean allowedBiome, boolean known)
{
    private static final TerrainSample UNKNOWN = new TerrainSample(0, false, false, false);

    public TerrainSample(final int height, final boolean water, final boolean allowedBiome)
    {
        this(height, water, allowedBiome, true);
    }

    public static TerrainSample unknown() { return UNKNOWN; }
}
