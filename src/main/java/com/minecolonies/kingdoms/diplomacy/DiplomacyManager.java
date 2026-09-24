package com.minecolonies.kingdoms.diplomacy;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.server.MinecraftServer;

/**
 * Low-frequency evaluation of relations between neighbouring NPC factions (default once per Minecraft day): first
 * contacts and trade deliveries become audited relation events. The evaluation time is persisted, so restarts neither
 * skip nor double-count a trade window.
 */
public final class DiplomacyManager
{
    private static final DiplomacyManager INSTANCE = new DiplomacyManager();
    private static final int CHECK_INTERVAL_TICKS = 200;
    private MinecraftServer server;
    private long evaluations;
    private double lastMillis;

    private DiplomacyManager() {}
    public static DiplomacyManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value) { server = value; evaluations = 0L; lastMillis = 0.0D; }
    public void shutdown() { server = null; }

    public void tick(final MinecraftServer value)
    {
        if (server == null || server != value || !KingdomsConfig.SERVER.diplomacyEnabled.get()) return;
        final long gameTime = value.overworld().getGameTime();
        if (gameTime % CHECK_INTERVAL_TICKS != 0) return;
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final long last = data.diplomacy().lastEvaluatedAt();
        if (last >= 0L && gameTime - last < KingdomsConfig.SERVER.diplomacyEvaluationIntervalTicks.get()) return;
        evaluateNow(value);
    }

    public DiplomacyEvaluator.Report evaluateNow(final MinecraftServer value)
    {
        final long started = System.nanoTime();
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final DiplomacyEvaluator.Report report;
        try
        {
            report = DiplomacyEvaluator.evaluate(data, value.overworld().getGameTime());
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error("Diplomacy evaluation failed", exception);
            return new DiplomacyEvaluator.Report(0, java.util.List.of());
        }
        lastMillis = (System.nanoTime() - started) / 1_000_000.0D;
        evaluations++;
        for (final DiplomacyState.Event event : report.events())
            if (event.stanceBefore() != event.stanceAfter() || event.cause() == DiplomacyState.Cause.FIRST_CONTACT)
                KingdomsMod.LOGGER.info("Diplomacy: {} and {} are now {} ({} -> {}, {})", name(data, event.first()),
                    name(data, event.second()), event.stanceAfter().displayName(), event.before(), event.after(), event.cause());
        return report;
    }

    public long evaluations() { return evaluations; }
    public double lastMillis() { return lastMillis; }

    static String name(final KingdomsSavedData data, final java.util.UUID factionId)
    {
        return data.faction(factionId).map(com.minecolonies.kingdoms.faction.Faction::name).orElse(factionId.toString());
    }
}
