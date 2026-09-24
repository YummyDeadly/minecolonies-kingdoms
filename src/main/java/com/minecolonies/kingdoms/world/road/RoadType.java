package com.minecolonies.kingdoms.world.road;

/**
 * Road classes. {@link #width()} is the half-width in blocks around the centre line (a road covers a rounded
 * band of radius {@code width + 0.5}); {@link #edgeKeepPercent()} is how much of the outermost ring is paved, so
 * lower-class roads get irregular, worn edges instead of a ruler-straight strip.
 */
public enum RoadType
{
    TRAIL(1, 1.00D, 40),
    DIRT(1, 1.15D, 70),
    STONE(2, 1.30D, 55),
    ROYAL(2, 1.45D, 100);

    private final int width;
    private final double speedMultiplier;
    private final int edgeKeepPercent;

    RoadType(final int width, final double speedMultiplier, final int edgeKeepPercent)
    {
        this.width = width;
        this.speedMultiplier = speedMultiplier;
        this.edgeKeepPercent = edgeKeepPercent;
    }

    public int width() { return width; }
    public double speedMultiplier() { return speedMultiplier; }
    public int edgeKeepPercent() { return edgeKeepPercent; }
}
