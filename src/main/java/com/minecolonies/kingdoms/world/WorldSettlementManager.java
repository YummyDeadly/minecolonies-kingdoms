package com.minecolonies.kingdoms.world;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadChunkGenerator;
import com.minecolonies.kingdoms.world.road.RoadPlanner;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadSettings;
import com.minecolonies.kingdoms.world.road.RoadTerrainPlanner;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.settlement.SettlementChunkGenerator;
import com.minecolonies.kingdoms.world.settlement.SettlementPlanner;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementSettings;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementGrowthManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class WorldSettlementManager
{
    private static final WorldSettlementManager INSTANCE = new WorldSettlementManager();
    private final SettlementChunkGenerator settlementGenerator = new SettlementChunkGenerator();
    private final RoadChunkGenerator roadGenerator = new RoadChunkGenerator();
    private final WorldGenerationProfiler profiler = new WorldGenerationProfiler();
    private MinecraftServer server;

    private WorldSettlementManager() {}
    public static WorldSettlementManager getInstance() { return INSTANCE; }
    /** Automatic planning runs here; only the pure planner and thread-safe generator sampling are used off-thread. */
    private final SettlementPlanner asyncSettlements = new SettlementPlanner();
    private final RoadPlanner asyncRoads = new RoadPlanner();
    private final java.util.Set<SettlementRegion> inFlight = new java.util.HashSet<>();
    private java.util.concurrent.ExecutorService planningExecutor;
    private long planningGeneration;
    private boolean roadJobInFlight;
    private boolean roadJobRequested;

    public void initialize(final MinecraftServer value)
    {
        server = value;
        profiler.reset();
        planningGeneration++;
        inFlight.clear();
        roadJobInFlight = false;
        roadJobRequested = false;
        planningExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "Kingdoms-Settlement-Planner");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) -> KingdomsMod.LOGGER.error("Settlement planner worker failure", error));
            return thread;
        });
    }

    public void shutdown()
    {
        server = null;
        planningGeneration++;
        inFlight.clear();
        pendingChunks.clear();
        if (planningExecutor != null)
        {
            planningExecutor.shutdownNow();
            try
            {
                if (!planningExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                    KingdomsMod.LOGGER.warn("Settlement planning worker did not stop within the shutdown timeout");
            }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            planningExecutor = null;
        }
        asyncSettlements.clearMemo();
        lastPlanningReport = "none";
    }
    public String lastPlanningReport() { return lastPlanningReport; }
    private String lastPlanningReport = "none";

    /** Explicit diagnostic: full site analysis at a position without creating anything. */
    public com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalysis probeSite(final ServerLevel level,
        final int x, final int z, final SettlementType type)
    {
        return new com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalyzer().analyze(x, z,
            SettlementPlanner.requirements(type, settlementSettings()), WorldgenTerrainSampler.generatorOnly(level));
    }
    public WorldGenerationStats stats() { return profiler.snapshot(); }

    /**
     * Chunk-load events can fire in the middle of synchronous chunk loading (MinecraftServer.execute runs inline on
     * the server thread). Planning or writing blocks there would read half-decorated terrain and re-enter chunk
     * loading, so events are queued and processed from the server tick instead.
     */
    private final java.util.ArrayDeque<PendingChunk> pendingChunks = new java.util.ArrayDeque<>();
    private static final int MAX_CHUNK_EVENTS_PER_TICK = 64;
    private static final int MAX_PENDING_CHUNK_EVENTS = 16_384;

    private record PendingChunk(ServerLevel level, net.minecraft.world.level.ChunkPos pos, boolean newChunk) {}

    public void queueChunkLoad(final ServerLevel level, final LevelChunk chunk, final boolean newChunk)
    {
        if (server == null || !KingdomsConfig.SERVER.settlementEnabled.get() || !allowed(level)) return;
        if (newChunk) SettlementGrowthManager.getInstance().observeNewChunk(level, chunk);
        if (pendingChunks.size() >= MAX_PENDING_CHUNK_EVENTS) pendingChunks.pollFirst();
        pendingChunks.addLast(new PendingChunk(level, chunk.getPos(), newChunk));
    }

    public void tick()
    {
        for (int index = 0; index < MAX_CHUNK_EVENTS_PER_TICK && !pendingChunks.isEmpty(); index++)
        {
            final PendingChunk pending = pendingChunks.pollFirst();
            final LevelChunk chunk = pending.level().getChunkSource().getChunkNow(pending.pos().x, pending.pos().z);
            if (chunk != null) onChunkLoad(pending.level(), chunk, pending.newChunk());
        }
    }

    public void onChunkLoad(final ServerLevel level, final LevelChunk chunk, final boolean newChunk)
    {
        if (server == null || !KingdomsConfig.SERVER.settlementEnabled.get() || !allowed(level)) return;
        schedulePlanning(level, SettlementRegion.containing(level.dimension().location(),
            chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), KingdomsConfig.SERVER.settlementRegionSize.get()), 1, null);
        if (!newChunk && !KingdomsConfig.SERVER.worldgenModifyExistingChunks.get()) return;
        generateChunk(level, chunk);
    }

    /**
     * Automatic chunk-load planning. Region candidates are analysed on the {@code Kingdoms-Settlement-Planner} worker
     * with generator-only sampling (no chunk access); results are committed on the server thread. Because region
     * results are pure functions of seed/region/settings/terrain, asynchronous commit order cannot change the layout.
     */
    public boolean schedulePlanning(final ServerLevel level, final SettlementRegion center, final int radius,
        final java.util.function.Consumer<String> report)
    {
        if (planningExecutor == null || !allowed(level)) return false;
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final List<SettlementRegion> pending = new ArrayList<>();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) for (int z = center.z() - radius; z <= center.z() + radius; z++)
        {
            final SettlementRegion region = new SettlementRegion(center.dimension(), x, z);
            if (!data.settlements().isRegionPlanned(region) && !inFlight.contains(region)) pending.add(region);
        }
        if (pending.isEmpty()) return false;
        inFlight.addAll(pending);
        final long token = planningGeneration;
        final long seed = level.getSeed();
        final SettlementSettings settings = settlementSettings();
        final WorldgenTerrainSampler generator = WorldgenTerrainSampler.generatorOnly(level);
        final MinecraftServer owner = server;
        planningExecutor.execute(() -> {
            final long started = System.nanoTime();
            final Map<Long, com.minecolonies.kingdoms.world.settlement.TerrainSample> cache = new HashMap<>();
            final com.minecolonies.kingdoms.world.settlement.TerrainSampler cached = (x, z) ->
                cache.computeIfAbsent((((long) x) << 32) ^ (z & 0xffffffffL), ignored -> generator.sample(x, z));
            final Map<SettlementRegion, Optional<SettlementRecord>> plans;
            final String siteSummary;
            try
            {
                asyncSettlements.resetDiagnostics();
                plans = asyncSettlements.planAll(seed, pending, settings, cached);
                siteSummary = "coarseSites=" + asyncSettlements.coarseAnalyses() + " fullSites=" + asyncSettlements.fullAnalyses()
                    + " siteRejections=" + new java.util.TreeMap<>(asyncSettlements.rejectionCounts());
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Background settlement planning failed", exception);
                owner.execute(() -> { if (token == planningGeneration) inFlight.removeAll(pending); });
                return;
            }
            final long elapsed = System.nanoTime() - started;
            final int samples = cache.size();
            owner.execute(() -> commitAutomatic(level, token, pending, plans, elapsed, samples + " " + siteSummary, report));
        });
        return true;
    }

    private void commitAutomatic(final ServerLevel level, final long token, final List<SettlementRegion> pending,
        final Map<SettlementRegion, Optional<SettlementRecord>> plans, final long workerNanos, final String samples,
        final java.util.function.Consumer<String> report)
    {
        if (token != planningGeneration || server == null) return;
        inFlight.removeAll(pending);
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final long started = System.nanoTime();
        int regions = 0;
        int created = 0;
        for (final SettlementRegion region : pending)
        {
            if (data.settlements().isRegionPlanned(region)) continue;
            regions++;
            data.settlements().markRegionPlanned(region);
            final Optional<SettlementRecord> planned = plans.get(region);
            if (planned != null && planned.isPresent() && data.settlements().put(planned.get()))
            {
                createSimulationRecords(data, planned.get());
                created++;
            }
        }
        if (regions > 0) data.markChanged();
        profiler.planning(regions, created, 0, System.nanoTime() - started);
        if (workerNanos > 250_000_000L || created > 0)
            KingdomsMod.LOGGER.info("Background settlement planning: regions={} settlements={} samples={} workerMs={}",
                regions, created, samples, String.format(java.util.Locale.ROOT, "%.1f", workerNanos / 1_000_000.0D));
        lastPlanningReport = String.format(java.util.Locale.ROOT,
            "background regions=%d settlements=%d samples=%s workerMs=%.1f commitMs=%.2f%s",
            regions, created, samples, workerNanos / 1_000_000.0D, (System.nanoTime() - started) / 1_000_000.0D,
            created > 0 && KingdomsConfig.SERVER.roadsEnabled.get() ? " (roads are being planned in the background)" : "");
        if (report != null) report.accept(lastPlanningReport);
        if (created > 0 && KingdomsConfig.SERVER.roadsEnabled.get()) scheduleRoadPlanning(level);
    }

    /** Road geometry is planned off-thread on NBT copies and merged by stable road UUID on the server thread. */
    private void scheduleRoadPlanning(final ServerLevel level)
    {
        if (planningExecutor == null) return;
        if (roadJobInFlight) { roadJobRequested = true; return; }
        roadJobInFlight = true;
        roadJobRequested = false;
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final com.minecolonies.kingdoms.world.settlement.SettlementRegistry settlementCopy =
            com.minecolonies.kingdoms.world.settlement.SettlementRegistry.load(data.settlements().save());
        final com.minecolonies.kingdoms.world.road.RoadNetwork roadCopy =
            com.minecolonies.kingdoms.world.road.RoadNetwork.load(data.roads().save());
        final long token = planningGeneration;
        final long seed = level.getSeed();
        final RoadSettings settings = roadSettings();
        final WorldgenTerrainSampler generator = WorldgenTerrainSampler.generatorOnly(level);
        final MinecraftServer owner = server;
        planningExecutor.execute(() -> {
            final long started = System.nanoTime();
            final Map<Long, com.minecolonies.kingdoms.world.settlement.TerrainSample> cache = new HashMap<>();
            int planned = 0;
            try
            {
                planned = asyncRoads.planMissing(seed, settlementCopy, roadCopy,
                    (x, z) -> cache.computeIfAbsent((((long) x) << 32) ^ (z & 0xffffffffL), ignored -> generator.sample(x, z)),
                    settings);
            }
            catch (RuntimeException exception) { KingdomsMod.LOGGER.error("Background road planning failed", exception); }
            final int count = planned;
            final long elapsed = System.nanoTime() - started;
            owner.execute(() -> {
                if (token != planningGeneration || server == null) return;
                roadJobInFlight = false;
                final KingdomsSavedData live = KingdomsSavedData.get(level);
                int merged = 0;
                for (final RoadRecord road : roadCopy.roads())
                    if (live.roads().get(road.id()).isEmpty() && live.settlements().get(road.firstSettlementId()).isPresent()
                        && live.settlements().get(road.secondSettlementId()).isPresent() && live.roads().put(road)) merged++;
                if (merged > 0) live.markChanged();
                if (count > 0 || elapsed > 250_000_000L)
                    KingdomsMod.LOGGER.info("Background road planning: roads={} merged={} workerMs={}", count, merged,
                        String.format(java.util.Locale.ROOT, "%.1f", elapsed / 1_000_000.0D));
                if (roadJobRequested) scheduleRoadPlanning(level);
            });
        });
    }

    public SettlementGrowthManager.MaterializeResult materialize(final ServerLevel level, final SettlementRecord settlement)
    {
        if (settlement.generatedChunks().isEmpty())
            return SettlementGrowthManager.getInstance().materializeSettlement(level, settlement);
        int changed = 0;
        final int radius = settlement.type().footprintRadius();
        for (int x = (settlement.anchor().getX() - radius) >> 4; x <= (settlement.anchor().getX() + radius) >> 4; x++)
            for (int z = (settlement.anchor().getZ() - radius) >> 4; z <= (settlement.anchor().getZ() + radius) >> 4; z++)
                changed += generateSettlement(level, level.getChunk(x, z), settlement);
        KingdomsSavedData.get(level).markChanged();
        return new SettlementGrowthManager.MaterializeResult(changed, 0, "");
    }

    public int regenerateRoadSegment(final ServerLevel level, final ChunkPos chunkPos)
    {
        final LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int changed = 0;
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        for (final RoadRecord road : data.roads().inChunk(chunkPos))
        {
            road.invalidateChunk(chunkPos.toLong());
            final int roadChanged = roadGenerator.generate(level, chunk, road, roadSettings(), reservedColumns(level, chunk.getPos()));
            changed += roadChanged;
            if (roadChanged > 0) profiler.generatedRoad(roadChanged);
        }
        data.markChanged();
        return changed;
    }

    public Optional<RoadRecord> connect(final ServerLevel level, final SettlementRecord first, final SettlementRecord second)
    {
        if (first.id().equals(second.id()) || !first.dimension().equals(second.dimension())
            || !first.dimension().equals(level.dimension().location())) return Optional.empty();
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final UUID left = first.id().compareTo(second.id()) <= 0 ? first.id() : second.id();
        final UUID right = left.equals(first.id()) ? second.id() : first.id();
        final UUID id = SettlementPlanner.stableUuid("road", level.getSeed(), left + ":" + right);
        final RoadRecord existing = data.roads().get(id).orElse(null);
        if (existing != null) return Optional.of(existing);
        final var geometry = new RoadPlanner().geometry(first, second,
            RoadPlanner.obstacles(data.settlements().records(), first.dimension()),
            WorldgenTerrainSampler.generatorOnly(level), roadSettings());
        final RoadRecord road = geometry.<RoadRecord>map(value -> new RoadRecord(id, left, right, first.dimension(),
            RoadType.DIRT, value, RoadStatus.PLANNED, 2)).orElseGet(() -> RoadRecord.unrouteable(id, left, right,
            first.dimension(), RoadType.DIRT, first.gate(), second.gate(), 2));
        data.roads().put(road);
        data.markChanged();
        return Optional.of(road);
    }

    private void generateChunk(final ServerLevel level, final LevelChunk chunk)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        for (final SettlementRecord settlement : data.settlements().records())
            if (settlement.dimension().equals(level.dimension().location()) && !settlement.generatedChunks().isEmpty()
                && settlementGenerator.intersects(settlement, chunk.getPos()))
            {
                try { generateSettlement(level, chunk, settlement); }
                catch (RuntimeException exception)
                {
                    settlement.physicalState(com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState.FAILED);
                    KingdomsMod.LOGGER.error("Failed to generate settlement {} in chunk {}", settlement.id(), chunk.getPos(), exception);
                }
            }
        for (final RoadRecord road : data.roads().inChunk(chunk.getPos()))
        {
            try
            {
                final int changed = roadGenerator.generate(level, chunk, road, roadSettings(), reservedColumns(level, chunk.getPos()));
                if (changed > 0) profiler.generatedRoad(changed);
                if (data.roads().isFullyGenerated(road)) road.status(com.minecolonies.kingdoms.world.road.RoadStatus.GENERATED);
            }
            catch (RuntimeException exception)
            {
                road.status(com.minecolonies.kingdoms.world.road.RoadStatus.FAILED);
                KingdomsMod.LOGGER.error("Failed to generate road {} in chunk {}", road.id(), chunk.getPos(), exception);
            }
        }
        SettlementGrowthManager.getInstance().generateChunk(level, chunk);
        data.markChanged();
    }

    private int generateSettlement(final ServerLevel level, final LevelChunk chunk, final SettlementRecord settlement)
    {
        final int changed = settlementGenerator.generate(level, chunk, settlement);
        if (changed > 0) profiler.generatedSettlement(changed);
        return changed;
    }

    /**
     * Settlement lots (footprint + 1) and plazas near a chunk: a global road never paves over them, even when the
     * road was planned after a growth lot.
     */
    private static java.util.function.LongPredicate reservedColumns(final ServerLevel level, final ChunkPos chunk)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final List<com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint> reserved = new ArrayList<>();
        final var bounds = new com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint(
            chunk.getMinBlockX(), chunk.getMinBlockZ(), chunk.getMaxBlockX(), chunk.getMaxBlockZ());
        for (final var layout : data.growth().layouts())
        {
            layout.lots().forEach(lot -> { if (lot.footprint().expand(1).intersects(bounds)) reserved.add(lot.footprint().expand(1)); });
            layout.streets().segments().stream()
                .filter(segment -> com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner.PLAZA_PURPOSE.equals(segment.purpose()))
                .forEach(segment -> {
                    final BlockPos center = segment.points().getFirst();
                    final int radius = segment.width() + 1;
                    final var plaza = new com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint(
                        center.getX() - radius, center.getZ() - radius, center.getX() + radius, center.getZ() + radius);
                    if (plaza.intersects(bounds)) reserved.add(plaza);
                });
        }
        if (reserved.isEmpty()) return column -> false;
        return column -> {
            final int x = BlockPos.getX(column);
            final int z = BlockPos.getZ(column);
            for (final var footprint : reserved) if (footprint.contains(x, z)) return true;
            return false;
        };
    }

    private static void createSimulationRecords(final KingdomsSavedData data, final SettlementRecord settlement)
    {
        if (data.colony(settlement.id()).isPresent()) return;
        final Faction faction = new Faction(settlement.factionId(), settlement.name() + " Council", FactionType.CITY_STATE);
        faction.setCapitalColonyId(settlement.id());
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(settlement.id(), settlement.dimension(),
            settlement.anchor(), settlement.name(), settlement.factionId(), settlement.createdAt());
        final int workers = settlement.initialPopulation() * 2 / 3;
        final int soldiers = settlement.type() == SettlementType.CASTLE || settlement.type() == SettlementType.FORT
            ? settlement.initialPopulation() / 5 : settlement.initialPopulation() / 10;
        colony.updatePopulation(settlement.initialPopulation(), workers, soldiers);
        colony.updateCapacities(Math.max(settlement.initialPopulation() + 8, 24), Math.max(1000, settlement.initialPopulation() * 100));
        data.putFaction(faction);
        data.putColony(colony);
    }

    private static boolean allowed(final ServerLevel level)
    {
        final String id = level.dimension().location().toString();
        return KingdomsConfig.SERVER.settlementAllowedDimensions.get().stream().anyMatch(id::equals);
    }

    private static SettlementSettings settlementSettings()
    {
        return new SettlementSettings(KingdomsConfig.SERVER.settlementRegionSize.get(),
            KingdomsConfig.SERVER.settlementDensityPercent.get(), KingdomsConfig.SERVER.settlementMinimumDistance.get(),
            KingdomsConfig.SERVER.settlementCandidateCount.get(), KingdomsConfig.SERVER.settlementSampleRadius.get(),
            KingdomsConfig.SERVER.settlementMaximumSlope.get(), KingdomsConfig.SERVER.settlementMaximumRoughness.get(),
            typeWeights());
    }

    private static Map<SettlementType, Integer> typeWeights()
    {
        final Map<SettlementType, Integer> configured = Map.of(
            SettlementType.VILLAGE, KingdomsConfig.SERVER.settlementVillageWeight.get(),
            SettlementType.TOWN, KingdomsConfig.SERVER.settlementTownWeight.get(),
            SettlementType.TRADING_TOWN, KingdomsConfig.SERVER.settlementTradingTownWeight.get(),
            SettlementType.CASTLE, KingdomsConfig.SERVER.settlementCastleWeight.get(),
            SettlementType.FORT, KingdomsConfig.SERVER.settlementFortWeight.get());
        if (configured.values().stream().mapToInt(Integer::intValue).sum() > 0) return configured;
        KingdomsMod.LOGGER.warn("All settlement type weights are zero; using the documented defaults");
        return Map.of(SettlementType.VILLAGE, 45, SettlementType.TOWN, 25, SettlementType.TRADING_TOWN, 15,
            SettlementType.CASTLE, 8, SettlementType.FORT, 7);
    }

    private static RoadSettings roadSettings()
    {
        return new RoadSettings(KingdomsConfig.SERVER.roadsSoftMaximumDistance.get(),
            Math.max(KingdomsConfig.SERVER.roadsHardMaximumDistance.get(), KingdomsConfig.SERVER.roadsSoftMaximumDistance.get()),
            KingdomsConfig.SERVER.roadsMaximumDegree.get(), KingdomsConfig.SERVER.roadsGridSize.get(),
            KingdomsConfig.SERVER.roadsSearchMargin.get(), KingdomsConfig.SERVER.roadsMaximumPathNodes.get(),
            KingdomsConfig.SERVER.roadsSlopeCost.get(), KingdomsConfig.SERVER.roadsWaterCost.get(),
            KingdomsConfig.SERVER.roadsElevationCost.get(), KingdomsConfig.SERVER.roadsMaximumGroundGrade.get(),
            KingdomsConfig.SERVER.roadsMaximumBridgeSpan.get(), KingdomsConfig.SERVER.roadsMaximumBridgeBankDelta.get(),
            KingdomsConfig.SERVER.roadsRavineDepthThreshold.get(), KingdomsConfig.SERVER.roadsMaximumFinePathPoints.get(),
            KingdomsConfig.SERVER.roadsPhysicalRefinementRadius.get(), KingdomsConfig.SERVER.roadsClearReplaceableVegetation.get());
    }
}
