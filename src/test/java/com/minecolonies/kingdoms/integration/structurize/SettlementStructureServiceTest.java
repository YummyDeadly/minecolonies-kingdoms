package com.minecolonies.kingdoms.integration.structurize;

import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureCatalog;
import net.minecraft.core.HolderLookup;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementStructureServiceTest
{
    @Test
    void duplicateInitializeSharesOneScanAndPublishesAtomically() throws Exception
    {
        final AtomicInteger scans = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final SettlementStructureService service = SettlementStructureService.forTesting((ignored, cancelled) -> {
            scans.incrementAndGet();
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return SettlementStructureCatalog.empty();
        });
        try
        {
            final CompletableFuture<SettlementStructureService.State> first =
                service.initialize((HolderLookup.Provider) null);
            final CompletableFuture<SettlementStructureService.State> second =
                service.initialize((HolderLookup.Provider) null);
            assertSame(first, second);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, scans.get());
            assertEquals(SettlementStructureService.State.LOADING, service.state());
            assertFalse(service.ready());
            assertTrue(service.catalog().descriptors().isEmpty());
            release.countDown();
            assertEquals(SettlementStructureService.State.READY, first.get(2, TimeUnit.SECONDS));
            assertTrue(service.ready());
        }
        finally
        {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void failedScanHasExplicitStateAndCanRestartAfterShutdown() throws Exception
    {
        final AtomicInteger attempts = new AtomicInteger();
        final SettlementStructureService service = SettlementStructureService.forTesting((ignored, cancelled) -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("test failure");
            return SettlementStructureCatalog.empty();
        });
        assertEquals(SettlementStructureService.State.FAILED,
            service.initialize((HolderLookup.Provider) null).get(2, TimeUnit.SECONDS));
        assertTrue(service.failure().contains("test failure"));
        service.shutdown();
        try
        {
            assertEquals(SettlementStructureService.State.READY,
                service.initialize((HolderLookup.Provider) null).get(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
        finally
        {
            service.shutdown();
        }
    }

    @Test
    void shutdownCancelsLoadingWorkerAndLeavesNoCatalogThread()
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final SettlementStructureService service = SettlementStructureService.forTesting((ignored, cancelled) -> {
            entered.countDown();
            while (!cancelled.getAsBoolean())
            {
                try { Thread.sleep(10); }
                catch (InterruptedException ignoredInterrupt) { }
            }
            return SettlementStructureCatalog.empty();
        });
        service.initialize((HolderLookup.Provider) null);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(entered.await(1, TimeUnit.SECONDS)));
        assertTimeoutPreemptively(Duration.ofSeconds(2), service::shutdown);
        assertEquals(SettlementStructureService.State.UNINITIALIZED, service.state());
        assertFalse(Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> thread.isAlive() && thread.getName().equals("Kingdoms-Structure-Catalog")));
    }
}
