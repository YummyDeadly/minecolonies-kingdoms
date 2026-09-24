package com.minecolonies.kingdoms.world.settlement.structure;

public record TerrainEnvelope(boolean valid, int baseHeight, int minimumHeight, int maximumHeight,
    int waterColumns, int unsupportedCorners, int maximumCutFill, double maximumGrade, String rejection)
{
    public static TerrainEnvelope rejected(final int baseHeight, final int min, final int max, final int water,
        final int unsupported, final int cutFill, final double grade, final String reason)
    {
        return new TerrainEnvelope(false, baseHeight, min, max, water, unsupported, cutFill, grade, reason);
    }
}
