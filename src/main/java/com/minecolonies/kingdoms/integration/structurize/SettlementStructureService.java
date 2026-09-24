package com.minecolonies.kingdoms.integration.structurize;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureCatalog;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Owns the immutable runtime structure catalog. Structurize 1.0.832 has no pack-ready event and its blueprint
 * accessors wait on a lifecycle barrier, so catalog discovery must never run on the Minecraft server thread.
 */
public final class SettlementStructureService
{
    public enum State { UNINITIALIZED, LOADING, READY, FAILED }

    @FunctionalInterface
    interface CatalogLoader
    {
        SettlementStructureCatalog load(HolderLookup.Provider registries, BooleanSupplier cancelled);
    }

    private static final SettlementStructureService INSTANCE = new SettlementStructureService(
        (registries, cancelled) -> new SettlementStructureCatalog(
            List.of(MineColoniesStructureSource.scan(registries, cancelled))));
    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        final Thread thread = new Thread(runnable, "Kingdoms-Structure-Catalog");
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((ignored, error) ->
            KingdomsMod.LOGGER.error("Uncaught structure catalog worker failure", error));
        return thread;
    };

    private final Object lifecycleLock = new Object();
    private final CatalogLoader loader;
    private final AtomicReference<SettlementStructureCatalog> catalog =
        new AtomicReference<>(SettlementStructureCatalog.empty());
    private final StructurizeBlueprintPlacer placer = new StructurizeBlueprintPlacer();
    private volatile State state = State.UNINITIALIZED;
    private volatile String failure = "";
    private ExecutorService executor;
    private Future<?> task;
    private AtomicBoolean cancellation;
    private CompletableFuture<State> completion = CompletableFuture.completedFuture(State.UNINITIALIZED);
    private long generation;

    private SettlementStructureService(final CatalogLoader loader)
    {
        this.loader = Objects.requireNonNull(loader);
    }

    static SettlementStructureService forTesting(final CatalogLoader loader)
    {
        return new SettlementStructureService(loader);
    }

    public static SettlementStructureService getInstance() { return INSTANCE; }
    public SettlementStructureCatalog catalog() { return catalog.get(); }
    public StructurizeBlueprintPlacer placer() { return placer; }
    public State state() { return state; }
    public boolean ready() { return state == State.READY; }
    public String failure() { return failure; }

    public CompletableFuture<State> initialize(final MinecraftServer server)
    {
        return initialize(server.registryAccess());
    }

    CompletableFuture<State> initialize(final HolderLookup.Provider registries)
    {
        synchronized (lifecycleLock)
        {
            if (state != State.UNINITIALIZED) return completion;
            final long requestGeneration = ++generation;
            state = State.LOADING;
            failure = "";
            catalog.set(SettlementStructureCatalog.empty());
            completion = new CompletableFuture<>();
            executor = Executors.newSingleThreadExecutor(THREAD_FACTORY);
            final ExecutorService requestExecutor = executor;
            cancellation = new AtomicBoolean();
            final AtomicBoolean requestCancellation = cancellation;
            task = requestExecutor.submit(() -> load(requestGeneration, registries, requestCancellation, requestExecutor));
            return completion;
        }
    }

    private void load(final long requestGeneration, final HolderLookup.Provider registries,
        final AtomicBoolean requestCancellation, final ExecutorService requestExecutor)
    {
        try
        {
            final SettlementStructureCatalog completed = Objects.requireNonNull(
                loader.load(registries, requestCancellation::get));
            synchronized (lifecycleLock)
            {
                if (generation != requestGeneration || state != State.LOADING) return;
                catalog.set(completed);
                state = State.READY;
                completion.complete(State.READY);
                KingdomsMod.LOGGER.info("Settlement structure catalog is ready with {} descriptors",
                    completed.descriptors().size());
            }
        }
        catch (Throwable error)
        {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            synchronized (lifecycleLock)
            {
                if (generation != requestGeneration || state != State.LOADING) return;
                failure = error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
                state = State.FAILED;
                completion.complete(State.FAILED);
                KingdomsMod.LOGGER.error("Settlement structure catalog initialization failed", error);
            }
        }
        finally
        {
            requestExecutor.shutdown();
        }
    }

    public void shutdown()
    {
        final ExecutorService stoppingExecutor;
        final Future<?> stoppingTask;
        final AtomicBoolean stoppingCancellation;
        synchronized (lifecycleLock)
        {
            generation++;
            stoppingExecutor = executor;
            stoppingTask = task;
            stoppingCancellation = cancellation;
            executor = null;
            task = null;
            cancellation = null;
            state = State.UNINITIALIZED;
            failure = "";
            catalog.set(SettlementStructureCatalog.empty());
            completion.complete(State.UNINITIALIZED);
            completion = CompletableFuture.completedFuture(State.UNINITIALIZED);
        }
        if (stoppingCancellation != null) stoppingCancellation.set(true);
        if (stoppingTask != null) stoppingTask.cancel(true);
        if (stoppingExecutor != null)
        {
            stoppingExecutor.shutdownNow();
            try
            {
                if (!stoppingExecutor.awaitTermination(5, TimeUnit.SECONDS))
                    KingdomsMod.LOGGER.warn("Structure catalog worker did not terminate within shutdown timeout");
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }
}
