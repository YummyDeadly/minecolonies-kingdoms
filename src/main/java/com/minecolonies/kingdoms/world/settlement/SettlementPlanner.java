package com.minecolonies.kingdoms.world.settlement;

import net.minecraft.core.BlockPos;

import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalysis;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalyzer;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteRequirements;
import com.minecolonies.kingdoms.world.settlement.site.SiteRejectionReason;
import java.util.ArrayList;
import java.util.List;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;

public final class SettlementPlanner
{
    private static final long SALT = 0x4b494e47444f4d53L;
    private static final String[] PREFIXES = {"Ash", "Bright", "Crow", "Dawn", "Grey", "High", "Oak", "Raven", "Stone", "West"};
    private static final String[] SUFFIXES = {"bridge", "ford", "haven", "holm", "keep", "mere", "stead", "watch", "wick", "worth"};
    private static final int MEMO_CAPACITY = 4_096;
    private static final int MAX_REFINEMENTS = 2;
    private final SettlementSiteAnalyzer siteAnalyzer = new SettlementSiteAnalyzer();
    /**
     * Region candidates are a pure function of seed, region, settings, and generator terrain, so they are memoized
     * across planning calls (bounded LRU). Neighbor regions are otherwise re-analyzed on every chunk-load event.
     */
    private final Map<MemoKey, Optional<Candidate>> memo = new LinkedHashMap<>(256, 0.75F, true)
    {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<MemoKey, Optional<Candidate>> eldest)
        {
            return size() > MEMO_CAPACITY;
        }
    };
    private final java.util.EnumMap<SiteRejectionReason, Integer> rejections = new java.util.EnumMap<>(SiteRejectionReason.class);
    private int fullAnalyses;
    private int coarseAnalyses;

    public Optional<SettlementRecord> plan(final long worldSeed, final SettlementRegion region,
        final SettlementSettings settings, final TerrainSampler terrain)
    {
        return plan(worldSeed, region, settings, terrain, new java.util.HashMap<>());
    }

    public Map<SettlementRegion, Optional<SettlementRecord>> planAll(final long worldSeed,
        final Collection<SettlementRegion> regions, final SettlementSettings settings, final TerrainSampler terrain)
    {
        final Map<SettlementRegion, Candidate> candidates = new java.util.HashMap<>();
        final Map<SettlementRegion, Optional<SettlementRecord>> result = new LinkedHashMap<>();
        for (final SettlementRegion region : regions)
            result.put(region, plan(worldSeed, region, settings, terrain, candidates));
        return result;
    }

    private Optional<SettlementRecord> plan(final long worldSeed, final SettlementRegion region,
        final SettlementSettings settings, final TerrainSampler terrain, final Map<SettlementRegion, Candidate> candidates)
    {
        final Candidate candidate = candidateCached(worldSeed, region, settings, terrain, candidates);
        if (candidate == null) return Optional.empty();
        final int radius = Math.max(1, (int) Math.ceil((double) settings.minimumDistance() / settings.regionSize()));
        for (int rx = region.x() - radius; rx <= region.x() + radius; rx++)
        {
            for (int rz = region.z() - radius; rz <= region.z() + radius; rz++)
            {
                if (rx == region.x() && rz == region.z()) continue;
                final Candidate other = candidateCached(worldSeed, new SettlementRegion(region.dimension(), rx, rz), settings, terrain, candidates);
                if (other != null && candidate.distanceSquared(other) < (long) settings.minimumDistance() * settings.minimumDistance()
                    && Candidate.ORDER.compare(other, candidate) < 0)
                {
                    return Optional.empty();
                }
            }
        }
        final SettlementType type = candidate.type;
        final int populationRange = type.maximumPopulation() - type.minimumPopulation() + 1;
        final int population = type.minimumPopulation() + Math.floorMod((int) mix(candidate.randomValue), populationRange);
        final int orientation = orientation(Math.floorMod((int) (candidate.randomValue >>> 16), 4),
            candidate.analysis == null ? 15 : candidate.analysis.approachMask());
        final BlockPos anchor = new BlockPos(candidate.x, candidate.height, candidate.z);
        final BlockPos gatePosition = switch (orientation)
        {
            case 0 -> anchor.offset(0, 0, type.footprintRadius());
            case 90 -> anchor.offset(-type.footprintRadius(), 0, 0);
            case 180 -> anchor.offset(0, 0, -type.footprintRadius());
            default -> anchor.offset(type.footprintRadius(), 0, 0);
        };
        final BlockPos gate = gatePosition.atY(terrain.sample(gatePosition.getX(), gatePosition.getZ()).height());
        final UUID id = stableUuid("settlement", worldSeed, region.key());
        final UUID faction = stableUuid("faction", worldSeed, region.key());
        return Optional.of(new SettlementRecord(id, name(candidate.randomValue, region), type, region.dimension(),
            anchor, orientation, gate, faction, population, SettlementPhysicalState.PLANNED, region, null, 0L,
            candidate.analysis));
    }

    private Candidate candidateCached(final long worldSeed, final SettlementRegion region,
        final SettlementSettings settings, final TerrainSampler terrain, final Map<SettlementRegion, Candidate> candidates)
    {
        if (candidates.containsKey(region)) return candidates.get(region);
        final MemoKey key = new MemoKey(worldSeed, region, settings);
        Optional<Candidate> memoized = memo.get(key);
        if (memoized == null)
        {
            memoized = Optional.ofNullable(candidate(worldSeed, region, settings, terrain));
            memo.put(key, memoized);
        }
        final Candidate value = memoized.orElse(null);
        candidates.put(region, value);
        return value;
    }

    private Candidate candidate(final long worldSeed, final SettlementRegion region, final SettlementSettings settings,
        final TerrainSampler terrain)
    {
        final long regionSeed = mix(worldSeed ^ SALT ^ mix(region.dimension().toString().hashCode())
            ^ mix(((long) region.x() << 32) ^ (region.z() & 0xffffffffL)));
        if (Math.floorMod(regionSeed, 100) >= settings.densityPercent()) return null;
        final SplittableRandom random = new SplittableRandom(regionSeed);
        final int margin = Math.min(settings.regionSize() / 4, Math.max(64, settings.sampleRadius() * 2));
        final int span = settings.regionSize() - margin * 2;
        // Broad phase: every candidate gets the 25-sample coarse check. Narrow phase: full area analysis in coarse
        // quality order, stopping at the first accepted site. Both orders are deterministic.
        final List<Pending> pending = new ArrayList<>();
        for (int index = 0; index < settings.candidateCount(); index++)
        {
            final int x = region.x() * settings.regionSize() + margin + random.nextInt(Math.max(1, span));
            final int z = region.z() * settings.regionSize() + margin + random.nextInt(Math.max(1, span));
            final long value = random.nextLong();
            final SettlementType type = chooseType(value, settings);
            final SettlementSiteRequirements requirements = requirements(type, settings);
            coarseAnalyses++;
            final SettlementSiteAnalyzer.CoarseSite coarse = siteAnalyzer.coarse(x, z, requirements, terrain);
            if (coarse.rejection() != SiteRejectionReason.NONE) { rejections.merge(coarse.rejection(), 1, Integer::sum); continue; }
            pending.add(new Pending(index, x, z, value, type, requirements, coarse));
        }
        pending.sort(Pending.ORDER);
        final int minX = region.x() * settings.regionSize() + margin / 2;
        final int maxX = (region.x() + 1) * settings.regionSize() - margin / 2;
        final int minZ = region.z() * settings.regionSize() + margin / 2;
        final int maxZ = (region.z() + 1) * settings.regionSize() - margin / 2;
        int refinements = 0;
        for (final Pending candidate : pending)
        {
            fullAnalyses++;
            final SettlementSiteAnalyzer.Detailed detailed = siteAnalyzer.analyzeDetailed(candidate.x, candidate.z,
                candidate.requirements, terrain);
            final Candidate scored = score(candidate.x, candidate.z, candidate.value, candidate.type, detailed, terrain);
            if (scored != null) return scored;
            // Refinement: a shoreline or patchy site is re-centred once on its largest buildable component.
            final SettlementSiteAnalysis analysis = detailed.analysis();
            final boolean movable = analysis.rejection() == SiteRejectionReason.WATER
                || analysis.rejection() == SiteRejectionReason.INSUFFICIENT_BUILDABLE_AREA
                || analysis.rejection() == SiteRejectionReason.NO_GATE_APPROACH;
            final int shift = Math.abs(detailed.centroidX() - candidate.x) + Math.abs(detailed.centroidZ() - candidate.z);
            if (refinements < MAX_REFINEMENTS && movable && analysis.connectedFraction() >= 0.25D
                && shift >= candidate.requirements.sampleSpacing()
                && detailed.centroidX() >= minX && detailed.centroidX() <= maxX
                && detailed.centroidZ() >= minZ && detailed.centroidZ() <= maxZ)
            {
                refinements++;
                fullAnalyses++;
                final SettlementSiteAnalyzer.Detailed refined = siteAnalyzer.analyzeDetailed(detailed.centroidX(),
                    detailed.centroidZ(), candidate.requirements, terrain);
                final Candidate moved = score(detailed.centroidX(), detailed.centroidZ(), candidate.value, candidate.type, refined, terrain);
                if (moved != null) return moved;
            }
        }
        return null;
    }

    public static SettlementSiteRequirements requirements(final SettlementType type, final SettlementSettings settings)
    {
        final SettlementSiteRequirements base = SettlementSiteRequirements.forType(type, settings.sampleRadius());
        return new SettlementSiteRequirements(base.analysisRadius(), base.sampleSpacing(),
            base.minimumBuildableFraction(), base.minimumConnectedFraction(), base.maximumWaterFraction(),
            Math.min(base.maximumElevationSpan(), Math.max(4, settings.maximumRoughness())),
            Math.min(base.maximumSampleStep(), Math.max(2, settings.maximumSlope())), base.maximumTerrainWork());
    }

    /** Cumulative site rejection counts since {@link #resetDiagnostics()}; memoized regions are not recounted. */
    public Map<SiteRejectionReason, Integer> rejectionCounts() { return Map.copyOf(rejections); }
    public int fullAnalyses() { return fullAnalyses; }
    public int coarseAnalyses() { return coarseAnalyses; }
    public void resetDiagnostics() { rejections.clear(); fullAnalyses = 0; coarseAnalyses = 0; }
    public void clearMemo() { memo.clear(); }

    private Candidate score(final int x, final int z, final long randomValue, final SettlementType type,
        final SettlementSiteAnalyzer.Detailed detailed, final TerrainSampler terrain)
    {
        final SettlementSiteAnalysis analysis = detailed.analysis();
        if (!analysis.accepted()) { rejections.merge(analysis.rejection(), 1, Integer::sum); return null; }
        final int center = terrain.sample(x, z).height();
        final long penalty = (long) ((1.0D - analysis.connectedFraction()) * 100_000.0D)
            + analysis.waterSamples() * 4_000L + analysis.maximumLocalStep() * 1_000L
            + analysis.estimatedTerrainWork() * 20L;
        return new Candidate(x, z, center, penalty, randomValue, type, analysis);
    }

    /** Keeps the deterministic preferred gate direction when it is approachable; otherwise rotates to one that is. */
    static int orientation(final int preferred, final int approachMask)
    {
        for (int step = 0; step < 4; step++)
        {
            final int quarter = Math.floorMod(preferred + step, 4);
            final int bit = switch (quarter)
            {
                case 0 -> SettlementSiteAnalysis.SOUTH;
                case 1 -> SettlementSiteAnalysis.WEST;
                case 2 -> SettlementSiteAnalysis.NORTH;
                default -> SettlementSiteAnalysis.EAST;
            };
            if ((approachMask & bit) != 0) return quarter * 90;
        }
        return Math.floorMod(preferred, 4) * 90;
    }

    private static SettlementType chooseType(final long random, final SettlementSettings settings)
    {
        final int total = settings.typeWeights().values().stream().mapToInt(Integer::intValue).sum();
        int selected = Math.floorMod((int) mix(random ^ 0x54595045L), total);
        for (final SettlementType type : SettlementType.values())
        {
            selected -= settings.typeWeights().get(type);
            if (selected < 0) return type;
        }
        return SettlementType.VILLAGE;
    }

    private static String name(final long value, final SettlementRegion region)
    {
        final int first = Math.floorMod((int) value, PREFIXES.length);
        final int second = Math.floorMod((int) (value >>> 32), SUFFIXES.length);
        return PREFIXES[first] + SUFFIXES[second] + " " + signed(region.x()) + signed(region.z());
    }

    private static String signed(final int value) { return value < 0 ? "N" + -value : "P" + value; }

    public static UUID stableUuid(final String subject, final long seed, final String key)
    {
        return UUID.nameUUIDFromBytes((subject + ':' + seed + ':' + key).getBytes(StandardCharsets.UTF_8));
    }

    private static long mix(long value)
    {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record MemoKey(long seed, SettlementRegion region, SettlementSettings settings) {}

    private record Pending(int index, int x, int z, long value, SettlementType type,
        SettlementSiteRequirements requirements, SettlementSiteAnalyzer.CoarseSite coarse)
    {
        private static final Comparator<Pending> ORDER = Comparator.comparingInt((Pending value) -> value.coarse.water())
            .thenComparingInt(value -> value.coarse.span()).thenComparingInt(Pending::index);
    }

    private record Candidate(int x, int z, int height, long penalty, long randomValue, SettlementType type,
        SettlementSiteAnalysis analysis)
    {
        private static final Comparator<Candidate> ORDER = Comparator.comparingLong(Candidate::penalty)
            .thenComparingLong(Candidate::randomValue).thenComparingInt(Candidate::x).thenComparingInt(Candidate::z);
        long distanceSquared(final Candidate other)
        {
            final long dx = (long) x - other.x;
            final long dz = (long) z - other.z;
            return dx * dx + dz * dz;
        }
    }
}
