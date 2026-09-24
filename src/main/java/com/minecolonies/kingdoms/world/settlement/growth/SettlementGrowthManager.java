package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.NeedEvaluator;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.integration.structurize.SettlementStructureService;
import com.minecolonies.kingdoms.world.WorldgenTerrainSampler;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetChunkGenerator;
import com.minecolonies.kingdoms.world.settlement.template.SettlementDistrictPlanner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SettlementGrowthManager
{
    private static final SettlementGrowthManager INSTANCE = new SettlementGrowthManager();
    private final SettlementGrowthEvaluator evaluator = new SettlementGrowthEvaluator();
    private final SettlementLayoutPlanner layouts = SettlementLayoutPlanner.configured();
    private final SettlementBuildingChunkGenerator generator = new SettlementBuildingChunkGenerator();
    private final SettlementStreetChunkGenerator streetGenerator = new SettlementStreetChunkGenerator();
    private final SettlementDistrictPlanner districts = new SettlementDistrictPlanner(layouts);
    private final NeedEvaluator needs = new NeedEvaluator();
    private final EconomyManager economy = new EconomyManager();
    private final GrowthProfiler profiler = new GrowthProfiler();
    /**
     * Starter slots reach 52 blocks plus half a blueprint; the extra ring keeps every planned column's chunk
     * neighbourhood loaded, so neighbour features cannot change terrain after planning (11x11 chunks at most).
     */
    public static final int STARTER_LOAD_RADIUS = 80;
    private static final String AWAITING_OPERATOR =
        "EXISTING_CHUNK_PROTECTED: footprint includes a chunk not generated this session; use /kingdoms growth materialize";
    private final FreshChunkTracker freshChunks = new FreshChunkTracker(65_536);
    /** In-memory backoff for starters whose area was not loaded; not persisted (a restart simply retries). */
    private final Map<UUID, Long> starterRetryAt = new java.util.HashMap<>();
    private static final long STARTER_RETRY_TICKS = 200L;
    private MinecraftServer server;
    private long nextCycleGameTime;
    private int cycleCursor;

    private SettlementGrowthManager() {}
    public static SettlementGrowthManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        server = value;
        nextCycleGameTime = value.overworld().getGameTime() + KingdomsConfig.SERVER.growthEvaluationIntervalTicks.get();
        cycleCursor = 0;
        profiler.reset();
    }

    public void shutdown()
    {
        server = null;
        freshChunks.clear();
        starterRetryAt.clear();
        nextCycleGameTime = 0L;
        cycleCursor = 0;
        profiler.reset();
    }

    public void catalogReady(final MinecraftServer value)
    {
        if (server == value) nextCycleGameTime = 0L;
    }

    public void tick(final MinecraftServer value)
    {
        if (server == null || !KingdomsConfig.SERVER.growthEnabled.get()) return;
        final long gameTime = value.overworld().getGameTime();
        if (gameTime < nextCycleGameTime) return;
        final boolean startersPending = hasPendingStarters(value);
        runCycle(value, gameTime, startersPending ? 1 : KingdomsConfig.SERVER.growthMaxSettlementsPerCycle.get());
        nextCycleGameTime = gameTime + (hasPendingStarters(value) ? 1
            : KingdomsConfig.SERVER.growthEvaluationIntervalTicks.get());
    }

    public List<GrowthEvaluationResult> tickNow(final MinecraftServer value)
    {
        return runCycle(value, value.overworld().getGameTime(), hasPendingStarters(value) ? 1
            : KingdomsConfig.SERVER.growthMaxSettlementsPerCycle.get());
    }

    public GrowthEvaluationResult evaluateNow(final MinecraftServer value, final UUID settlementId)
    {
        return evaluate(value, KingdomsSavedData.get(value.overworld()), settlementId, value.overworld().getGameTime());
    }

    public GrowthStats stats() { return profiler.snapshot(); }

    /** Records a chunk that world generation created during this server session (strict existing-world ownership). */
    public void observeNewChunk(final ServerLevel level, final LevelChunk chunk)
    {
        freshChunks.add(level.dimension(), chunk.getPos().toLong());
    }

    /**
     * Autonomous physical work for one chunk event. Street slices are written only in chunks that are owned (new this
     * session or {@code modifyExistingChunks=true}). A building is written only when every footprint chunk is loaded
     * and owned, and then all of its slices are written together, so autonomous generation never leaves half a
     * building at a chunk border.
     */
    public int generateChunk(final ServerLevel level, final LevelChunk chunk)
    {
        if (!KingdomsConfig.SERVER.growthEnabled.get()) return 0;
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        int changed = 0;
        final int centerX = chunk.getPos().getMiddleBlockX();
        final int centerZ = chunk.getPos().getMiddleBlockZ();
        final java.util.Set<UUID> candidates = new java.util.LinkedHashSet<>();
        for (final SettlementRecord settlement : data.settlements().records())
        {
            final int planningRadius = settlement.type().footprintRadius() + 64;
            if (settlement.dimension().equals(level.dimension().location())
                && Math.abs(settlement.anchor().getX() - centerX) <= planningRadius
                && Math.abs(settlement.anchor().getZ() - centerZ) <= planningRadius)
            {
                final boolean hadDistrict = data.growth().layout(settlement.id()).isPresent();
                ensureStarterDistrict(level, data, settlement, level.getGameTime());
                if (!hadDistrict) data.growth().forSettlement(settlement.id()).forEach(value -> candidates.add(value.id()));
            }
        }
        if (owned(level, chunk.getPos().toLong()))
            for (final var layout : data.growth().layouts())
            {
                final SettlementRecord owner = data.settlements().get(layout.settlementId()).orElse(null);
                final int reach = KingdomsConfig.SERVER.growthMaximumExpansionRadius.get() + 96;
                if (owner != null && owner.dimension().equals(level.dimension().location())
                    && Math.abs(owner.anchor().getX() - centerX) <= reach && Math.abs(owner.anchor().getZ() - centerZ) <= reach)
                    changed += streetGenerator.generate(level, chunk, layout, data.roads());
            }
        data.growth().inChunk(chunk.getPos()).forEach(value -> candidates.add(value.id()));
        for (final UUID id : candidates)
        {
            final SettlementBuildingRecord building = data.growth().building(id).orElse(null);
            if (building == null) continue;
            final SettlementRecord settlement = data.settlements().get(building.settlementId()).orElse(null);
            if (settlement == null || !settlement.dimension().equals(level.dimension().location())) continue;
            changed += generateAutonomously(level, building);
        }
        updateSettlementPhysicalStates(level, data);
        if (changed > 0) data.markChanged();
        return changed;
    }

    private int generateAutonomously(final ServerLevel level, final SettlementBuildingRecord building)
    {
        if (building.status() != SettlementBuildingStatus.READY && building.status() != SettlementBuildingStatus.GENERATING) return 0;
        // Structurize's blueprint accessor waits on its pack barrier; never call it on the server thread before READY.
        if (building.usesBlueprintVisual() && !SettlementStructureService.getInstance().ready()) return 0;
        final List<LevelChunk> slices = new ArrayList<>();
        for (final long chunkKey : building.footprintChunks().stream().sorted().toList())
        {
            if (!building.needsGeneration(chunkKey)) continue;
            final LevelChunk loaded = level.getChunkSource().getChunkNow(
                net.minecraft.world.level.ChunkPos.getX(chunkKey), net.minecraft.world.level.ChunkPos.getZ(chunkKey));
            if (loaded == null || !WorldgenTerrainSampler.neighbourhoodLoaded(level, loaded.getPos().x, loaded.getPos().z)) return 0;
            if (!owned(level, chunkKey))
            {
                building.awaiting(AWAITING_OPERATOR);
                return 0;
            }
            slices.add(loaded);
        }
        final var refusal = generator.preflight(level, slices, building);
        if (refusal.isPresent())
        {
            building.blocked(refusal.orElseThrow());
            profiler.blockedBuild();
            return 0;
        }
        int changed = 0;
        for (final LevelChunk slice : slices)
        {
            if (building.status() == SettlementBuildingStatus.BLOCKED || building.status() == SettlementBuildingStatus.FAILED) break;
            changed += generateSlice(level, slice, building);
        }
        return changed;
    }

    private int generateSlice(final ServerLevel level, final LevelChunk chunk, final SettlementBuildingRecord building)
    {
        final SettlementBuildingStatus before = building.status();
        try
        {
            final int buildingChanges = generator.generate(level, chunk, building, level.getGameTime());
            if (buildingChanges > 0 || before != building.status()) profiler.physicalChunk();
            if (before != SettlementBuildingStatus.COMPLETED && building.status() == SettlementBuildingStatus.COMPLETED)
                profiler.buildingCompleted();
            if (before != SettlementBuildingStatus.BLOCKED && building.status() == SettlementBuildingStatus.BLOCKED)
                profiler.blockedBuild();
            return buildingChanges;
        }
        catch (RuntimeException exception)
        {
            building.failed("generation exception: " + exception.getClass().getSimpleName());
            KingdomsMod.LOGGER.error("Failed to generate growth building {} in chunk {}", building.id(), chunk.getPos(), exception);
            return 0;
        }
    }

    private static boolean owned(final ServerLevel level, final long chunkKey)
    {
        return KingdomsConfig.SERVER.worldgenModifyExistingChunks.get()
            || INSTANCE.freshChunks.contains(level.dimension(), chunkKey);
    }

    private static void updateSettlementPhysicalStates(final ServerLevel level, final KingdomsSavedData data)
    {
        for (final SettlementRecord settlement : data.settlements().records())
        {
            if (!settlement.dimension().equals(level.dimension().location())) continue;
            final List<SettlementBuildingRecord> starter = data.growth().forSettlement(settlement.id()).stream()
                .filter(value -> value.origin() == SettlementBuildingOrigin.STARTER).toList();
            if (!starter.isEmpty() && starter.stream().allMatch(value -> value.status() == SettlementBuildingStatus.COMPLETED))
                settlement.physicalState(com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState.GENERATED);
        }
    }

    /** Explicit operator override: synchronously loads the bounded footprint and retries a blocked building once. */
    public int materialize(final ServerLevel level, final SettlementBuildingRecord building)
    {
        if (building.status() == SettlementBuildingStatus.PLANNED) building.ready(level.getGameTime());
        building.retryAfterBlock();
        if (building.usesBlueprintVisual() && !SettlementStructureService.getInstance().ready())
        {
            building.awaiting("STRUCTURE_CATALOG_LOADING");
            return 0;
        }
        final List<LevelChunk> slices = new ArrayList<>();
        for (final long chunkKey : building.footprintChunks().stream().sorted().toList())
            if (building.needsGeneration(chunkKey))
                slices.add(level.getChunk(net.minecraft.world.level.ChunkPos.getX(chunkKey),
                    net.minecraft.world.level.ChunkPos.getZ(chunkKey)));
        if (building.status() == SettlementBuildingStatus.READY || building.status() == SettlementBuildingStatus.GENERATING)
        {
            final var refusal = generator.preflight(level, slices, building);
            if (refusal.isPresent())
            {
                building.blocked(refusal.orElseThrow());
                profiler.blockedBuild();
                KingdomsSavedData.get(level).markChanged();
                return 0;
            }
        }
        int changed = 0;
        for (final LevelChunk slice : slices)
        {
            if (building.status() == SettlementBuildingStatus.BLOCKED || building.status() == SettlementBuildingStatus.FAILED) break;
            changed += generateSlice(level, slice, building);
        }
        KingdomsSavedData.get(level).markChanged();
        return changed;
    }

    /** Explicit operator override: plans the starter if needed, then writes pending buildings followed by streets. */
    public MaterializeResult materializeSettlement(final ServerLevel level, final SettlementRecord settlement)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        // Explicit operator override: load the bounded starter area so pad planning reads exact, cheap heightmaps.
        final int radius = STARTER_LOAD_RADIUS;
        for (int x = (settlement.anchor().getX() - radius) >> 4; x <= (settlement.anchor().getX() + radius) >> 4; x++)
            for (int z = (settlement.anchor().getZ() - radius) >> 4; z <= (settlement.anchor().getZ() + radius) >> 4; z++)
                level.getChunk(x, z);
        final String blocker = ensureStarterDistrict(level, data, settlement, level.getGameTime());
        if (blocker != null) return new MaterializeResult(0, 0, blocker);
        int changed = 0;
        int buildings = 0;
        for (final SettlementBuildingRecord building : data.growth().forSettlement(settlement.id()))
        {
            if (building.status() == SettlementBuildingStatus.COMPLETED || building.status() == SettlementBuildingStatus.FAILED) continue;
            changed += materialize(level, building);
            buildings++;
        }
        changed += materializeStreets(level, data, settlement);
        updateSettlementPhysicalStates(level, data);
        data.markChanged();
        return new MaterializeResult(changed, buildings, "");
    }

    public int materializeStreets(final ServerLevel level, final KingdomsSavedData data, final SettlementRecord settlement)
    {
        final var layout = data.growth().layout(settlement.id()).orElse(null);
        if (layout == null) return 0;
        final java.util.Set<Long> streetChunks = new java.util.TreeSet<>();
        layout.streets().segments().forEach(segment -> streetChunks.addAll(SettlementStreetChunkGenerator.chunks(segment)));
        int changed = 0;
        for (final long chunkKey : streetChunks)
            changed += streetGenerator.generate(level,
                level.getChunk(net.minecraft.world.level.ChunkPos.getX(chunkKey), net.minecraft.world.level.ChunkPos.getZ(chunkKey)),
                layout, data.roads());
        return changed;
    }

    public record MaterializeResult(int changedBlocks, int buildingsProcessed, String blocker) {}

    private List<GrowthEvaluationResult> runCycle(final MinecraftServer value, final long gameTime, final int maximum)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final List<UUID> eligible = data.settlements().records().stream().map(SettlementRecord::id)
            .filter(id -> data.colony(id).map(colony -> colony.kind() == ColonyKind.NPC_ABSTRACT).orElse(false))
            .sorted().toList();
        if (eligible.isEmpty()) return List.of();
        final int count = Math.min(maximum, eligible.size());
        final List<GrowthEvaluationResult> results = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
            results.add(evaluate(value, data, eligible.get(Math.floorMod(cycleCursor + index, eligible.size())), gameTime));
        cycleCursor = Math.floorMod(cycleCursor + count, eligible.size());
        return List.copyOf(results);
    }

    private static boolean hasPendingStarters(final MinecraftServer value)
    {
        if (!SettlementStructureService.getInstance().ready()) return false;
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final long now = value.overworld().getGameTime();
        return data.settlements().records().stream().anyMatch(settlement -> settlement.generatedChunks().isEmpty()
            && now >= INSTANCE.starterRetryAt.getOrDefault(settlement.id(), Long.MIN_VALUE)
            && data.growth().layout(settlement.id()).isEmpty()
            && data.growth().forSettlement(settlement.id()).isEmpty()
            && java.util.Optional.ofNullable(value.getLevel(ResourceKey.create(Registries.DIMENSION, settlement.dimension())))
                .map(level -> level.hasChunk(settlement.anchor().getX() >> 4, settlement.anchor().getZ() >> 4)).orElse(false)
            && data.growth().state(settlement.id()).map(state ->
                state.starterDistrictStatus() == StarterDistrictStatus.UNPLANNED).orElse(true));
    }

    private GrowthEvaluationResult evaluate(final MinecraftServer value, final KingdomsSavedData data,
        final UUID settlementId, final long gameTime)
    {
        final long started = System.nanoTime();
        UUID plannedId = null;
        SettlementBuildingType plannedType = null;
        boolean populationGrew = false;
        String detail;
        final SettlementRecord settlement = data.settlements().get(settlementId).orElse(null);
        final NPCColonyData colony = data.colony(settlementId).orElse(null);
        if (settlement == null || colony == null || colony.kind() != ColonyKind.NPC_ABSTRACT)
        {
            detail = "not an eligible procedural NPC_ABSTRACT settlement";
            profiler.evaluation(System.nanoTime() - started);
            return new GrowthEvaluationResult(settlementId, SettlementGrowthStage.STARTER, null, null, false, detail);
        }
        final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, settlement.dimension());
        final ServerLevel level = value.getLevel(dimension);
        final SettlementGrowthState state = data.growth().stateOrCreate(settlementId);
        final String starterBlocker = level == null ? null : ensureStarterDistrict(level, data, settlement, gameTime);
        if (level != null && starterBlocker == null)
            for (final SettlementBuildingRecord pending : data.growth().forSettlement(settlementId))
                generateAutonomously(level, pending);
        final List<SettlementBuildingRecord> buildings = data.growth().forSettlement(settlementId);
        advanceLogicalConstruction(buildings, gameTime, KingdomsConfig.SERVER.growthEvaluationIntervalTicks.get());
        applyDerivedEffects(colony, state, buildings);
        colony.replaceNeeds(needs.evaluate(colony, gameTime));
        final SettlementGrowthStage nextStage = stageFor(settlement, colony, buildings);
        if (state.stage() != nextStage) { state.stage(nextStage); profiler.stageTransition(); }
        if (gameTime - state.lastPopulationEvaluationAt() >= KingdomsConfig.SERVER.growthPopulationIntervalTicks.get())
        {
            state.populationEvaluated(gameTime);
            populationGrew = tryGrowPopulation(colony, state.stage(), settlement,
                KingdomsConfig.SERVER.growthPopulationHardCap.get());
            if (populationGrew) profiler.populationGrowth();
        }
        if (level == null)
        {
            detail = "settlement dimension is unavailable";
            state.blocked(detail);
        }
        else if (starterBlocker != null)
        {
            detail = starterBlocker;
            state.blocked(detail);
            profiler.blockedBuild();
        }
        else if (buildings.stream().anyMatch(building -> building.status() == SettlementBuildingStatus.PLANNED))
        {
            detail = "logical construction pending";
            state.blocked(detail);
        }
        else if (buildings.size() >= KingdomsConfig.SERVER.growthMaximumBuildingsPerSettlement.get())
        {
            detail = "building cap reached";
            state.blocked(detail);
        }
        else
        {
            final SettlementStructureService structures = SettlementStructureService.getInstance();
            final String knownStyle = data.growth().layout(settlementId).map(plan -> plan.styleFamily())
                .filter(family -> !family.isBlank()).orElse(null);
            final GrowthDecision decision = evaluator.decide(settlement, colony, buildings, type -> knownStyle == null
                || !structures.ready() || !structures.catalog().candidates(settlementId, type, 0, knownStyle, 1).isEmpty());
            if (!decision.plansBuilding())
            {
                detail = decision.reason();
                state.blocked(detail);
            }
            else if (structures.state() == SettlementStructureService.State.UNINITIALIZED
                || structures.state() == SettlementStructureService.State.LOADING)
            {
                detail = "STRUCTURE_CATALOG_LOADING";
                state.blocked(detail);
                profiler.blockedBuild();
            }
            else if (structures.state() == SettlementStructureService.State.FAILED)
            {
                detail = "STRUCTURE_CATALOG_FAILED";
                state.blocked(detail);
                profiler.blockedBuild();
            }
            else
            {
                final int sequence = state.nextSequence();
                final var catalog = structures.catalog();
                final var existingLayout = data.growth().layout(settlementId);
                final var style = existingLayout.map(plan -> plan.styleFamily().isBlank() ? null : plan.styleFamily())
                    .or(() -> catalog.selectStyle(settlementId));
                if (style.isEmpty())
                {
                    detail = "no compatible installed MineColonies structure style";
                    state.blocked(detail);
                    profiler.blockedBuild();
                }
                else
                {
                    final var selections = catalog.candidates(settlementId, decision.buildingType(), sequence,
                        style.orElseThrow(), 6);
                    if (selections.isEmpty())
                    {
                        detail = "style " + style.orElseThrow() + " has no compatible "
                            + decision.buildingType().name().toLowerCase() + " blueprint";
                        state.blocked(detail); profiler.blockedBuild();
                    }
                    else
                    {
                        final var layout = data.growth().layoutOrCreate(settlementId,
                            () -> layouts.createPlan(settlement, style.orElseThrow()));
                        final UUID buildingId = SettlementPlotPlanner.stableBuildingId(settlement.id(), sequence,
                            decision.buildingType(), 2);
                        final WorldgenTerrainSampler worldTerrain = WorldgenTerrainSampler.loadedOnly(level);
                        final java.util.Map<Long, com.minecolonies.kingdoms.world.settlement.TerrainSample> terrainCache = new java.util.HashMap<>();
                        final com.minecolonies.kingdoms.world.settlement.TerrainSampler cachedTerrain = (x, z) ->
                            terrainCache.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL),
                                ignored -> worldTerrain.sample(x, z));
                        final var planned = layouts.plan(settlement, buildingId, selections, layout,
                            KingdomsConfig.SERVER.growthMaximumExpansionRadius.get(), cachedTerrain, data.roads());
                        if (planned.isEmpty())
                        {
                            detail = "NO_VALID_LOT:" + decision.buildingType().name() + "[" + layout.lastSearch().summary() + "]";
                            state.blocked(detail); profiler.blockedBuild();
                        }
                        else
                        {
                            final var placement = planned.orElseThrow();
                            final var selected = placement.structure();
                            final SettlementBuildingRecord building = new SettlementBuildingRecord(buildingId, settlement.id(),
                                decision.buildingType(), placement.anchor(), selected.transform().rotation(), sequence, 2,
                                gameTime, SettlementBuildingStatus.PLANNED, SettlementBuildingOrigin.GROWTH);
                            building.assignVisual(selected.descriptor().sourceId(), selected.descriptor().structureId(),
                                selected.descriptor().styleFamily(), selected.transform().mirrored(),
                                placement.lot().entrance(), placement.lot().footprint());
                            building.assignTerrainShaping(placement.terrain());
                            if (!data.growth().put(building))
                            {
                                detail = "stable building id already exists"; state.blocked(detail); profiler.blockedBuild();
                            }
                            else
                            {
                                layout.streets().put(placement.streetExtension()); layout.putLot(placement.lot());
                                state.allocateSequence();
                                state.decision(decision.reason() + " -> " + building.type().name().toLowerCase()
                                    + " [" + building.styleFamily() + "]");
                                detail = state.lastDecision(); plannedId = building.id(); plannedType = building.type();
                                profiler.buildingPlanned();
                            }
                        }
                    }
                }
            }
        }
        state.evaluated(gameTime);
        data.markChanged();
        profiler.evaluation(System.nanoTime() - started);
        return new GrowthEvaluationResult(settlementId, state.stage(), plannedId, plannedType, populationGrew, detail);
    }

    private String ensureStarterDistrict(final ServerLevel level, final KingdomsSavedData data,
        final SettlementRecord settlement, final long gameTime)
    {
        if (!settlement.generatedChunks().isEmpty() || data.growth().layout(settlement.id()).isPresent()
            || !data.growth().forSettlement(settlement.id()).isEmpty()) return null;
        if (!level.hasChunk(settlement.anchor().getX() >> 4, settlement.anchor().getZ() >> 4))
            return "STARTER_SITE_NOT_LOADED";
        final SettlementGrowthState state = data.growth().stateOrCreate(settlement.id());
        if (state.resetStarterIfPlannedBefore(SettlementDistrictPlanner.STARTER_PLANNER_VERSION)) data.markChanged();
        if (state.starterDistrictStatus() == StarterDistrictStatus.READY) return null;
        if (state.starterDistrictStatus() == StarterDistrictStatus.BLOCKED)
            return state.blocker() == null ? "STARTER_TEMPLATE_BLOCKED" : state.blocker();
        final SettlementStructureService structures = SettlementStructureService.getInstance();
        if (structures.state() == SettlementStructureService.State.UNINITIALIZED
            || structures.state() == SettlementStructureService.State.LOADING) return "STRUCTURE_CATALOG_LOADING";
        if (structures.state() == SettlementStructureService.State.FAILED) return "STRUCTURE_CATALOG_FAILED";
        if (gameTime < starterRetryAt.getOrDefault(settlement.id(), Long.MIN_VALUE))
            return state.blocker() == null ? "STARTER_SITE_NOT_LOADED" : state.blocker();
        final long started = System.nanoTime();
        final var planned = districts.plan(settlement, structures.catalog(), WorldgenTerrainSampler.loadedOnly(level),
            data.roads(), gameTime);
        final double millis = (System.nanoTime() - started) / 1_000_000.0D;
        state.starterDiagnostics(planned.diagnostics());
        if (!planned.accepted() && planned.unloadedTerrain())
        {
            // Not a terrain verdict: part of the starter area is not loaded. Retry later; never persist as BLOCKED.
            final String blocker = "STARTER_SITE_NOT_LOADED:" + planned.blocker();
            state.blocked(blocker);
            starterRetryAt.put(settlement.id(), gameTime + STARTER_RETRY_TICKS);
            return blocker;
        }
        if (!planned.accepted())
        {
            final String blocker = "STARTER_TEMPLATE_BLOCKED:" + planned.blocker();
            state.starterDistrictBlocked(blocker, SettlementDistrictPlanner.STARTER_PLANNER_VERSION);
            KingdomsMod.LOGGER.info("Starter district for {} {} blocked after {} ms: {}", settlement.name(), settlement.id(),
                String.format(java.util.Locale.ROOT, "%.1f", millis), blocker);
            data.markChanged();
            return blocker;
        }
        final var district = planned.plan();
        if (!data.growth().putDistrict(district.layout(), district.buildings()))
        {
            if (data.growth().layout(settlement.id()).isPresent()) return null;
            state.starterDistrictBlocked("STARTER_TEMPLATE_COMMIT_CONFLICT", SettlementDistrictPlanner.STARTER_PLANNER_VERSION);
            data.markChanged();
            return "STARTER_TEMPLATE_COMMIT_CONFLICT";
        }
        for (int index = 0; index < district.buildings().size(); index++) state.allocateSequence();
        state.starterDistrictReady(SettlementDistrictPlanner.STARTER_PLANNER_VERSION);
        state.decision("starter template " + district.templateId() + " [" + district.styleFamily() + "] buildings="
            + district.buildings().size() + " terrainWork=" + district.terrainWorkVolume());
        KingdomsMod.LOGGER.info("Starter district for {} {} planned in {} ms: template={} style={} buildings={} terrainWork={}",
            settlement.name(), settlement.id(), String.format(java.util.Locale.ROOT, "%.1f", millis), district.templateId(),
            district.styleFamily(), district.buildings().size(), district.terrainWorkVolume());
        settlement.physicalState(com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState.GENERATING);
        data.markChanged();
        return null;
    }

    void applyDerivedEffects(final NPCColonyData colony, final SettlementGrowthState state,
        final List<SettlementBuildingRecord> buildings)
    {
        int housing = 0;
        int storage = 0;
        final EnumMap<EconomicResource, Double> production = new EnumMap<>(EconomicResource.class);
        for (final SettlementBuildingRecord building : buildings)
        {
            if (!building.status().contributesEffects()) continue;
            housing += building.type().housingContribution();
            storage += building.type().storageContribution();
            building.type().productionContributions().forEach((resource, amount) -> production.merge(resource, amount, Double::sum));
        }
        final int baseHousing = Math.max(0, colony.housingCapacity() - state.appliedHousing());
        final int baseStorage = Math.max(0, colony.storageCapacity() - state.appliedStorage());
        colony.updateCapacities(baseHousing + housing, baseStorage + storage);
        for (final EconomicResource resource : EconomicResource.values())
            economy.adjustProduction(colony, resource, production.getOrDefault(resource, 0.0D) - state.appliedProduction(resource));
        state.appliedEffects(housing, storage, production);
    }

    private static void advanceLogicalConstruction(final List<SettlementBuildingRecord> buildings,
        final long gameTime, final long delay)
    {
        buildings.stream().filter(building -> building.status() == SettlementBuildingStatus.PLANNED)
            .filter(building -> gameTime - building.createdAt() >= delay).forEach(building -> building.ready(gameTime));
    }

    static SettlementGrowthStage stageFor(final SettlementRecord settlement, final NPCColonyData colony,
        final List<SettlementBuildingRecord> buildings)
    {
        final long active = buildings.stream().filter(building -> building.status().contributesEffects()).count();
        if (active >= 7 && colony.population() >= settlement.type().minimumPopulation() + 10) return SettlementGrowthStage.PROSPEROUS;
        if (active >= 4 && colony.population() >= settlement.type().minimumPopulation() + 4) return SettlementGrowthStage.ESTABLISHED;
        if (active >= 2) return SettlementGrowthStage.GROWING;
        return SettlementGrowthStage.STARTER;
    }

    public boolean tryGrowPopulation(final NPCColonyData colony, final SettlementGrowthStage stage,
        final SettlementRecord settlement, final int configuredHardCap)
    {
        final int stageCapacity = settlement.type().maximumPopulation() + stage.ordinal() * 8;
        final int cap = Math.min(configuredHardCap, stageCapacity);
        if (colony.population() >= cap || !evaluator.populationAllowed(colony)) return false;
        final int nextPopulation = colony.population() + 1;
        final int desiredWorkers = Math.min(nextPopulation - colony.soldiers(), Math.max(colony.workers(), nextPopulation * 2 / 3));
        colony.updatePopulation(nextPopulation, desiredWorkers, colony.soldiers());
        return true;
    }
}
