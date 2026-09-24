package com.minecolonies.kingdoms.world.settlement.site;

import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded area analysis used before a settlement candidate becomes authoritative. */
public final class SettlementSiteAnalyzer
{
    public static final int MAX_GRID_SIDE = 41;
    public static final int MAX_SAMPLES = MAX_GRID_SIDE * MAX_GRID_SIDE + 32;

    /** Analysis plus the world-space centroid of the largest connected buildable component. */
    public record Detailed(SettlementSiteAnalysis analysis, int centroidX, int centroidZ) {}

    public SettlementSiteAnalysis analyze(final int centerX, final int centerZ,
        final SettlementSiteRequirements requirements, final TerrainSampler terrain)
    {
        return analyzeDetailed(centerX, centerZ, requirements, terrain).analysis();
    }

    public Detailed analyzeDetailed(final int centerX, final int centerZ,
        final SettlementSiteRequirements requirements, final TerrainSampler terrain)
    {
        final int cells = Math.min(MAX_GRID_SIDE / 2, requirements.analysisRadius() / requirements.sampleSpacing());
        final int side = cells * 2 + 1;
        final Cell[][] grid = new Cell[side][side];
        final List<Integer> dryHeights = new ArrayList<>(side * side);
        int water = 0;
        int disallowed = 0;
        for (int gx = 0; gx < side; gx++) for (int gz = 0; gz < side; gz++)
        {
            final int x = centerX + (gx - cells) * requirements.sampleSpacing();
            final int z = centerZ + (gz - cells) * requirements.sampleSpacing();
            final TerrainSample sample = terrain.sample(x, z);
            grid[gx][gz] = new Cell(sample.height(), sample.allowedBiome(), sample.water());
            if (!sample.allowedBiome()) disallowed++;
            else if (sample.water()) water++;
            else dryHeights.add(sample.height());
        }
        final int total = side * side;
        if (dryHeights.isEmpty())
            return new Detailed(result(false, total, 0, 0, water, disallowed, 0, 0, 0, 0, 0L,
                disallowed == total ? SiteRejectionReason.DISALLOWED_BIOME : SiteRejectionReason.WATER, 0), centerX, centerZ);
        dryHeights.sort(Comparator.naturalOrder());
        final int median = dryHeights.get(dryHeights.size() / 2);
        final int lowerBand = median - requirements.maximumElevationSpan() / 2;
        final int upperBand = median + requirements.maximumElevationSpan() / 2;
        int buildable = 0;
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int maximumStep = 0;
        long terrainWork = 0L;
        for (int gx = 0; gx < side; gx++) for (int gz = 0; gz < side; gz++)
        {
            final Cell cell = grid[gx][gz];
            if (!cell.allowed || cell.water) continue;
            minimum = Math.min(minimum, cell.height); maximum = Math.max(maximum, cell.height);
            int localStep = 0;
            for (final int[] direction : DIRECTIONS)
            {
                final int nx = gx + direction[0]; final int nz = gz + direction[1];
                if (nx < 0 || nx >= side || nz < 0 || nz >= side) continue;
                final Cell neighbor = grid[nx][nz];
                if (neighbor.allowed && !neighbor.water)
                    localStep = Math.max(localStep, Math.abs(cell.height - neighbor.height));
            }
            maximumStep = Math.max(maximumStep, localStep);
            cell.buildable = cell.height >= lowerBand && cell.height <= upperBand
                && localStep <= requirements.maximumSampleStep();
            if (cell.buildable)
            {
                buildable++;
                final int nearestTerrace = median + Math.max(-2, Math.min(2,
                    Math.round((cell.height - median) / 3.0F))) * 3;
                terrainWork += Math.abs(cell.height - nearestTerrace);
            }
        }
        final int[] component = largestConnected(grid);
        final int connected = component[0];
        final int centroidX = connected == 0 ? centerX
            : centerX + Math.round((component[1] / (float) connected - cells) * requirements.sampleSpacing());
        final int centroidZ = connected == 0 ? centerZ
            : centerZ + Math.round((component[2] / (float) connected - cells) * requirements.sampleSpacing());
        final int approachMask = approachMask(grid, cells, requirements.maximumSampleStep());
        final int gateApproaches = Integer.bitCount(approachMask);
        final double waterFraction = water / (double) total;
        final double buildableFraction = buildable / (double) total;
        final double connectedFraction = connected / (double) total;
        final SiteRejectionReason rejection = disallowed > total / 2 ? SiteRejectionReason.DISALLOWED_BIOME
            : waterFraction > requirements.maximumWaterFraction() ? SiteRejectionReason.WATER
            : maximum - minimum > requirements.maximumElevationSpan() * 2 ? SiteRejectionReason.CLIFF
            : maximumStep > requirements.maximumSampleStep() * 2 ? SiteRejectionReason.SLOPE
            : buildableFraction < requirements.minimumBuildableFraction()
                || connectedFraction < requirements.minimumConnectedFraction() ? SiteRejectionReason.INSUFFICIENT_BUILDABLE_AREA
            : gateApproaches == 0 ? SiteRejectionReason.NO_GATE_APPROACH
            : terrainWork > requirements.maximumTerrainWork() ? SiteRejectionReason.EXCESSIVE_TERRAIN_VOLUME
            : SiteRejectionReason.NONE;
        return new Detailed(result(rejection == SiteRejectionReason.NONE, total, buildable, connected, water, disallowed,
            minimum, maximum, maximumStep, gateApproaches, terrainWork, rejection, approachMask), centroidX, centroidZ);
    }

    /**
     * Cheap broad phase: a 5x5 subset of the full grid (so a caching sampler reuses these samples). It rejects only
     * sites that are far outside the requirements, before the full grid is sampled.
     */
    public record CoarseSite(SiteRejectionReason rejection, int water, int span) {}

    public SiteRejectionReason coarseReject(final int centerX, final int centerZ,
        final SettlementSiteRequirements requirements, final TerrainSampler terrain)
    {
        return coarse(centerX, centerZ, requirements, terrain).rejection();
    }

    public CoarseSite coarse(final int centerX, final int centerZ,
        final SettlementSiteRequirements requirements, final TerrainSampler terrain)
    {
        final int cells = Math.min(MAX_GRID_SIDE / 2, requirements.analysisRadius() / requirements.sampleSpacing());
        final int stride = Math.max(1, cells / 2) * requirements.sampleSpacing();
        int water = 0;
        int disallowed = 0;
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int gx = -2; gx <= 2; gx++) for (int gz = -2; gz <= 2; gz++)
        {
            final TerrainSample sample = terrain.sample(centerX + gx * stride, centerZ + gz * stride);
            if (!sample.allowedBiome()) disallowed++;
            else if (sample.water()) water++;
            else { minimum = Math.min(minimum, sample.height()); maximum = Math.max(maximum, sample.height()); }
        }
        final int span = maximum >= minimum ? maximum - minimum : 0;
        if (disallowed > 12) return new CoarseSite(SiteRejectionReason.DISALLOWED_BIOME, water, span);
        if (water / 25.0D > Math.min(1.0D, requirements.maximumWaterFraction() + 0.30D) || water + disallowed == 25)
            return new CoarseSite(SiteRejectionReason.WATER, water, span);
        if (span > requirements.maximumElevationSpan() * 3) return new CoarseSite(SiteRejectionReason.CLIFF, water, span);
        return new CoarseSite(SiteRejectionReason.NONE, water, span);
    }

    /** Returns {size, sumX, sumZ} of the largest 4-connected buildable component (bounded by the grid). */
    private static int[] largestConnected(final Cell[][] grid)
    {
        final boolean[][] visited = new boolean[grid.length][grid.length];
        int[] largest = {0, 0, 0};
        for (int x = 0; x < grid.length; x++) for (int z = 0; z < grid.length; z++)
        {
            if (visited[x][z] || !grid[x][z].buildable) continue;
            int count = 0;
            int sumX = 0;
            int sumZ = 0;
            final ArrayDeque<int[]> open = new ArrayDeque<>(); open.add(new int[]{x, z}); visited[x][z] = true;
            while (!open.isEmpty())
            {
                final int[] current = open.removeFirst(); count++; sumX += current[0]; sumZ += current[1];
                for (final int[] direction : DIRECTIONS)
                {
                    final int nx = current[0] + direction[0]; final int nz = current[1] + direction[1];
                    if (nx < 0 || nx >= grid.length || nz < 0 || nz >= grid.length
                        || visited[nx][nz] || !grid[nx][nz].buildable) continue;
                    visited[nx][nz] = true; open.addLast(new int[]{nx, nz});
                }
            }
            if (count > largest[0]) largest = new int[] {count, sumX, sumZ};
        }
        return largest;
    }

    private static int approachMask(final Cell[][] grid, final int center, final int maximumStep)
    {
        int valid = 0;
        for (int index = 0; index < DIRECTIONS.length; index++)
        {
            final int[] direction = DIRECTIONS[index];
            Cell previous = grid[center][center];
            boolean pass = previous.buildable;
            for (int step = 1; pass && step <= center; step++)
            {
                final Cell next = grid[center + direction[0] * step][center + direction[1] * step];
                pass = next.buildable && Math.abs(next.height - previous.height) <= maximumStep;
                previous = next;
            }
            if (pass) valid |= 1 << index;
        }
        return valid;
    }

    private static SettlementSiteAnalysis result(final boolean accepted, final int total, final int buildable,
        final int connected, final int water, final int disallowed, final int minimum, final int maximum,
        final int maximumStep, final int gateApproaches, final long work, final SiteRejectionReason reason,
        final int approachMask)
    {
        return new SettlementSiteAnalysis(accepted, total, buildable, connected, water, disallowed,
            minimum, maximum, maximumStep, gateApproaches, work, reason, approachMask);
    }

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final class Cell
    {
        private final int height;
        private final boolean allowed;
        private final boolean water;
        private boolean buildable;
        private Cell(final int height, final boolean allowed, final boolean water)
        {
            this.height = height; this.allowed = allowed; this.water = water;
        }
    }
}
