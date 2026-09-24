package com.minecolonies.kingdoms.world;

final class WorldGenerationProfiler
{
    private long regions;
    private long settlements;
    private long roads;
    private long settlementChunks;
    private long roadChunks;
    private long blocks;
    private long runs;
    private long totalNanos;
    private long maximumNanos;

    synchronized void planning(final int plannedRegions, final int plannedSettlements, final int plannedRoads, final long nanos)
    {
        regions += plannedRegions; settlements += plannedSettlements; roads += plannedRoads;
        runs++; totalNanos += nanos; maximumNanos = Math.max(maximumNanos, nanos);
    }
    synchronized void generatedSettlement(final int changed) { settlementChunks++; blocks += changed; }
    synchronized void generatedRoad(final int changed) { roadChunks++; blocks += changed; }
    synchronized WorldGenerationStats snapshot()
    {
        return new WorldGenerationStats(regions, settlements, roads, settlementChunks, roadChunks, blocks, runs,
            runs == 0 ? 0.0D : (double) totalNanos / runs, maximumNanos);
    }
    synchronized void reset() { regions = settlements = roads = settlementChunks = roadChunks = blocks = runs = totalNanos = maximumNanos = 0L; }
}
