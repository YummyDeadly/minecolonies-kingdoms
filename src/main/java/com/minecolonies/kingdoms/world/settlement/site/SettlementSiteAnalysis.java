package com.minecolonies.kingdoms.world.settlement.site;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

public record SettlementSiteAnalysis(boolean accepted, int totalSamples, int buildableSamples,
    int largestConnectedSamples, int waterSamples, int disallowedSamples, int minimumHeight,
    int maximumHeight, int maximumLocalStep, int gateApproaches, long estimatedTerrainWork,
    SiteRejectionReason rejection, int approachMask)
{
    /** Approach bits: east (+x), west (-x), south (+z), north (-z). */
    public static final int EAST = 1, WEST = 2, SOUTH = 4, NORTH = 8;

    public SettlementSiteAnalysis
    {
        Objects.requireNonNull(rejection);
        if (totalSamples < 1 || buildableSamples < 0 || largestConnectedSamples < 0 || waterSamples < 0
            || disallowedSamples < 0 || largestConnectedSamples > buildableSamples || buildableSamples > totalSamples
            || waterSamples > totalSamples || disallowedSamples > totalSamples || maximumHeight < minimumHeight
            || maximumLocalStep < 0 || gateApproaches < 0 || estimatedTerrainWork < 0L
            || approachMask < 0 || approachMask > 15 || Integer.bitCount(approachMask) > gateApproaches)
            throw new IllegalArgumentException("Invalid settlement site analysis");
        if (accepted != (rejection == SiteRejectionReason.NONE))
            throw new IllegalArgumentException("Accepted site and rejection reason disagree");
    }

    public double buildableFraction() { return buildableSamples / (double) totalSamples; }
    public double connectedFraction() { return largestConnectedSamples / (double) totalSamples; }
    public double waterFraction() { return waterSamples / (double) totalSamples; }
    public int elevationSpan() { return maximumHeight - minimumHeight; }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putBoolean("accepted", accepted); tag.putInt("samples", totalSamples);
        tag.putInt("buildable", buildableSamples); tag.putInt("connected", largestConnectedSamples);
        tag.putInt("water", waterSamples); tag.putInt("disallowed", disallowedSamples);
        tag.putInt("minHeight", minimumHeight); tag.putInt("maxHeight", maximumHeight);
        tag.putInt("maxStep", maximumLocalStep); tag.putInt("gateApproaches", gateApproaches);
        tag.putLong("terrainWork", estimatedTerrainWork); tag.putString("rejection", rejection.name());
        tag.putInt("approachMask", approachMask);
        return tag;
    }

    public static SettlementSiteAnalysis load(final CompoundTag tag)
    {
        return new SettlementSiteAnalysis(tag.getBoolean("accepted"), Math.max(1, tag.getInt("samples")),
            tag.getInt("buildable"), tag.getInt("connected"), tag.getInt("water"), tag.getInt("disallowed"),
            tag.getInt("minHeight"), tag.getInt("maxHeight"), tag.getInt("maxStep"),
            tag.getInt("gateApproaches"), tag.getLong("terrainWork"),
            SiteRejectionReason.valueOf(tag.getString("rejection")),
            Math.min(tag.getInt("approachMask"), tag.getInt("gateApproaches") == 0 ? 0 : 15) & 15);
    }
}
