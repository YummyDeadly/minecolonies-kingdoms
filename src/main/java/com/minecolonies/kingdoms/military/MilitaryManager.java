package com.minecolonies.kingdoms.military;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.bandit.BanditManager;
import com.minecolonies.kingdoms.contract.ContractManager;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.server.MinecraftServer;

/**
 * Schedules the coarse strategic security evaluation (Phase 9): once per configured interval, linear in NPC
 * settlements plus at most one patrol per garrison. Physical guards are handled by {@link GarrisonManager}.
 */
public final class MilitaryManager
{
    private static final MilitaryManager INSTANCE = new MilitaryManager();
    private MinecraftServer server;
    private long evaluations;
    private long totalNanos;
    private long maximumNanos;
    private MilitaryService.Report lastReport;

    private MilitaryManager() {}
    public static MilitaryManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        server = value;
        evaluations = totalNanos = maximumNanos = 0L;
        lastReport = null;
    }

    public void shutdown()
    {
        server = null;
        lastReport = null;
    }

    public void tick(final MinecraftServer value)
    {
        if (server == null || server != value) return;
        final long gameTime = value.overworld().getGameTime();
        if (gameTime % 20 != 0) return;
        final SecuritySettings settings = GarrisonManager.settingsFromConfig();
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final long last = data.military().lastEvaluatedAt();
        if (last >= 0L && gameTime - last < settings.evaluationIntervalTicks()) return;
        evaluate(value, data, gameTime, settings);
    }

    public MilitaryService.Report evaluateNow(final MinecraftServer value)
    {
        return evaluate(value, KingdomsSavedData.get(value.overworld()), value.overworld().getGameTime(), GarrisonManager.settingsFromConfig());
    }

    private MilitaryService.Report evaluate(final MinecraftServer value, final KingdomsSavedData data, final long gameTime,
        final SecuritySettings settings)
    {
        final long started = System.nanoTime();
        MilitaryService.Report report = null;
        try
        {
            report = MilitaryService.evaluate(data, gameTime, settings, BanditManager.settingsFromConfig(), ContractManager.settings(),
                BanditManager.getInstance()::observed);
            report.resolutions().forEach(resolution -> BanditManager.getInstance().announce(data, resolution));
            if (report.sorties() > 0)
                KingdomsMod.LOGGER.info("Garrison patrols: {} sortie(s), {} camp(s) cleared", report.sorties(), report.campsCleared());
            lastReport = report;
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error("Security evaluation failed", exception);
            MilitaryService.evaluationFailed(data, gameTime);
        }
        final long elapsed = System.nanoTime() - started;
        evaluations++;
        totalNanos += elapsed;
        maximumNanos = Math.max(maximumNanos, elapsed);
        return report;
    }

    public record Stats(long evaluations, double averageNanos, long maximumNanos, MilitaryService.Report lastReport) {}

    public Stats stats()
    {
        return new Stats(evaluations, evaluations == 0 ? 0.0D : (double) totalNanos / evaluations, maximumNanos, lastReport);
    }
}
