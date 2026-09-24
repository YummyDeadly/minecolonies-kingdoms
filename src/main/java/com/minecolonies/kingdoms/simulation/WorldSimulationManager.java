package com.minecolonies.kingdoms.simulation;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.colony.AIColonyController;
import com.minecolonies.kingdoms.colony.ColonyController;
import com.minecolonies.kingdoms.colony.ColonyControllerResult;
import com.minecolonies.kingdoms.colony.ColonyMetricsProvider;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.ColonyResourceStorage;
import com.minecolonies.kingdoms.colony.ColonyUpdateContext;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.PlayerColonyController;
import com.minecolonies.kingdoms.colony.SimulationMode;
import com.minecolonies.kingdoms.colony.decision.StrategicDecisionEvaluator;
import com.minecolonies.kingdoms.colony.need.NeedEvaluator;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.integration.minecolonies.economy.MinecraftItemResourceValueProvider;
import com.minecolonies.kingdoms.integration.minecolonies.economy.MineColoniesColonyMetricsAdapter;
import com.minecolonies.kingdoms.integration.minecolonies.economy.MineColoniesWarehouseResourceStorage;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.UUID;

public final class WorldSimulationManager
{
    private static final WorldSimulationManager INSTANCE = new WorldSimulationManager(
        new MineColoniesColonyMetricsAdapter(),
        new MineColoniesWarehouseResourceStorage(new MinecraftItemResourceValueProvider()),
        new EconomyManager(),
        new SimulationModeResolver());

    private final RoundRobinBatchScheduler<UUID> scheduler = new RoundRobinBatchScheduler<>();
    private final SimulationProfiler profiler = new SimulationProfiler();
    private final ColonyMetricsProvider metricsProvider;
    private final ColonyResourceStorage resourceStorage;
    private final EconomyManager economyManager;
    private final SimulationModeResolver modeResolver;
    private final ColonyController playerController = new PlayerColonyController();
    private final ColonyController aiController = new AIColonyController(new NeedEvaluator(), new StrategicDecisionEvaluator());
    private long nextCycleGameTime;

    WorldSimulationManager(
        final ColonyMetricsProvider metricsProvider,
        final ColonyResourceStorage resourceStorage,
        final EconomyManager economyManager,
        final SimulationModeResolver modeResolver)
    {
        this.metricsProvider = metricsProvider;
        this.resourceStorage = resourceStorage;
        this.economyManager = economyManager;
        this.modeResolver = modeResolver;
    }

    public static WorldSimulationManager getInstance()
    {
        return INSTANCE;
    }

    public void initialize(final MinecraftServer server)
    {
        scheduler.clear();
        profiler.reset();
        nextCycleGameTime = server.overworld().getGameTime();
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        data.markChanged();
        KingdomsMod.LOGGER.info(
            "Kingdoms SavedData initialized (schema {}, {} tracked colonies)",
            KingdomsSavedData.DATA_VERSION,
            data.colonies().size());
    }

    public void tick(final MinecraftServer server)
    {
        if (!KingdomsConfig.SERVER.simulationEnabled.get())
        {
            return;
        }
        final long gameTime = server.overworld().getGameTime();
        final KingdomsSavedData savedData = KingdomsSavedData.get(server.overworld());
        if (scheduler.isEmpty() && gameTime >= nextCycleGameTime)
        {
            scheduler.refill(
                savedData.colonies().stream().map(NPCColonyData::id).toList(),
                Comparator.naturalOrder());
            nextCycleGameTime = gameTime + KingdomsConfig.SERVER.strategicIntervalTicks.get();
        }
        if (scheduler.isEmpty())
        {
            return;
        }

        final BatchExecutionResult batch = scheduler.process(
            KingdomsConfig.SERVER.batchSize.get(),
            KingdomsConfig.SERVER.maxWorkNanosPerTick.get(),
            colonyId -> updateColony(server, savedData, colonyId, gameTime));
        profiler.recordBatch(batch.processed());
    }

    public SimulationStats stats()
    {
        return profiler.snapshot(scheduler.remaining());
    }

    public void shutdown()
    {
        scheduler.clear();
        profiler.reset();
        nextCycleGameTime = 0L;
    }

    private void updateColony(
        final MinecraftServer server,
        final KingdomsSavedData savedData,
        final UUID colonyId,
        final long gameTime)
    {
        final NPCColonyData colony = savedData.colony(colonyId).orElse(null);
        if (colony == null)
        {
            return;
        }
        final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, colony.dimension());
        final ServerLevel level = server.getLevel(dimension);
        if (level == null)
        {
            return;
        }

        final long started = System.nanoTime();
        try
        {
            final SimulationMode previousMode = colony.simulationMode();
            final SimulationMode resolvedMode = colony.kind() == ColonyKind.NPC_ABSTRACT
                ? SimulationMode.ABSTRACT
                : modeResolver.resolve(
                    level,
                    colony,
                    KingdomsConfig.SERVER.activeRadius.get(),
                    KingdomsConfig.SERVER.effectiveDeactivationRadius());
            if (previousMode != resolvedMode)
            {
                colony.setSimulationMode(resolvedMode);
                KingdomsMod.LOGGER.info(
                    "[Kingdoms] Colony {} switched {} -> {}",
                    colonyLogId(colony),
                    previousMode,
                    resolvedMode);
            }

            final ColonyController controller = colony.playerManaged() ? playerController : aiController;
            final ColonyControllerResult result = controller.tick(new ColonyUpdateContext(
                level,
                colony,
                gameTime,
                KingdomsConfig.SERVER.economyIntervalTicks.get(),
                metricsProvider,
                resourceStorage,
                economyManager));
            profiler.recordColony(System.nanoTime() - started, result.economyUpdated(), result.aiEvaluated());
            savedData.markChanged();
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error(
                "Failed strategic update for MineColonies colony {} in {}",
                colonyLogId(colony),
                colony.dimension(),
                exception);
        }
    }

    private static String colonyLogId(final NPCColonyData colony)
    {
        return colony.mineColoniesColonyId().isPresent()
            ? "MineColonies #" + colony.mineColoniesColonyId().getAsInt()
            : colony.id().toString();
    }
}
