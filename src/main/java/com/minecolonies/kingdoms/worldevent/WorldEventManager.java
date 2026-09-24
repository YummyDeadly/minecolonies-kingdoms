package com.minecolonies.kingdoms.worldevent;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.citizen.interaction.SettlementCitizenInteractionService;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Schedules world events (Phase 11): a {@link WorldEventService#update} every {@value #CYCLE_TICKS} ticks (due
 * transitions, and a planning evaluation when its interval has passed), then tells nearby players what happened. It
 * holds no authority; all state changes happen in {@link WorldEventService}.
 */
public final class WorldEventManager
{
    private static final WorldEventManager INSTANCE = new WorldEventManager();
    public static final int CYCLE_TICKS = 100;
    /** Players within this distance of an event's subject hear about it (diplomatic events: everyone). */
    public static final double NEWS_RADIUS = 512.0D;

    private MinecraftServer server;
    private long updates;
    private long evaluations;
    private long planned;
    private long activated;
    private long ended;
    private long failures;
    private long totalNanos;
    private long maximumNanos;
    private int lastCandidates;

    private WorldEventManager() {}
    public static WorldEventManager getInstance() { return INSTANCE; }

    public record Stats(long updates, long evaluations, long planned, long activated, long ended, long failures, double averageNanos,
        long maximumNanos, int lastCandidates) {}

    public void initialize(final MinecraftServer value)
    {
        server = value;
        updates = evaluations = planned = activated = ended = failures = totalNanos = maximumNanos = 0L;
        lastCandidates = 0;
        SettlementCitizenInteractionService.getInstance().register(new WorldEventNewsHandler());
    }

    public void shutdown()
    {
        server = null;
    }

    public void tick(final MinecraftServer value)
    {
        if (server == null || server != value) return;
        final long gameTime = value.overworld().getGameTime();
        if (gameTime % CYCLE_TICKS != 0) return;
        update(value, gameTime);
    }

    /** One update now (also used by the operator {@code evaluate} command). */
    public WorldEventService.Report update(final MinecraftServer value, final long gameTime)
    {
        final long started = System.nanoTime();
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        data.worldEvents().seedIfUnset(value.overworld().getSeed());
        final long evaluationsBefore = data.worldEvents().evaluations();
        WorldEventService.Report report = new WorldEventService.Report(java.util.Optional.empty(), List.of(), List.of(), 0);
        try
        {
            report = WorldEventService.update(data, gameTime, settingsFromConfig());
        }
        catch (RuntimeException exception)
        {
            failures++;
            KingdomsMod.LOGGER.error("World event update failed", exception);
        }
        final long nanos = System.nanoTime() - started;
        updates++;
        totalNanos += nanos;
        maximumNanos = Math.max(maximumNanos, nanos);
        if (data.worldEvents().evaluations() > evaluationsBefore)
        {
            evaluations++;
            lastCandidates = report.candidates();
        }
        report.planned().ifPresent(event -> {
            planned++;
            KingdomsMod.LOGGER.info("World event {} planned: {} at {} ({})", event.id(), event.type(), WorldEventService.subjectName(data, event),
                event.reason());
            announce(data, event, Component.literal("Rumours: " + rumour(data, event)).withStyle(ChatFormatting.GRAY));
        });
        for (final WorldEventRecord event : report.activated())
        {
            activated++;
            KingdomsMod.LOGGER.info("World event {} started: {} at {}", event.id(), event.type(), WorldEventService.subjectName(data, event));
            announce(data, event, Component.literal(event.type().displayName() + " at " + WorldEventService.subjectName(data, event) + ".")
                .withStyle(ChatFormatting.GOLD));
        }
        for (final WorldEventRecord event : report.ended())
        {
            ended++;
            KingdomsMod.LOGGER.info("World event {} ended: {} ({})", event.id(), event.status(), event.outcome());
            if (event.status() == WorldEventRecord.Status.RESOLVED && event.type().reversible())
                announce(data, event, Component.literal(event.type().displayName() + " at " + WorldEventService.subjectName(data, event) + " is over.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return report;
    }

    static String rumour(final KingdomsSavedData data, final WorldEventRecord event)
    {
        final String where = WorldEventService.subjectName(data, event);
        return switch (event.type())
        {
            case HARVEST_FAILURE -> "blight is spreading in the fields of " + where + ".";
            case TRADE_FAIR -> "merchants are gathering for a fair in " + where + ".";
            case BANDIT_SURGE -> "travellers see more bandits gathering on " + where + ".";
            case GARRISON_FEVER -> "a fever is spreading in the barracks of " + where + ".";
            case MILITIA_MUSTER -> "the people of " + where + " talk about taking up arms.";
            case BORDER_INCIDENT -> "tension is rising at the border between " + where + ".";
            case ENVOY_VISIT -> "envoys are travelling between " + where + ".";
            case MIGRATION -> "families are packing up to leave: " + where + ".";
        };
    }

    /** Tells the players near the event's subject (everyone in the dimension for faction pairs). */
    private void announce(final KingdomsSavedData data, final WorldEventRecord event, final Component message)
    {
        if (server == null) return;
        final List<BlockPos> places = places(data, event);
        final double limit = NEWS_RADIUS * NEWS_RADIUS;
        for (final ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (event.dimension() != null && !player.level().dimension().location().equals(event.dimension())) continue;
            if (places.isEmpty() || places.stream().anyMatch(place -> player.blockPosition().distSqr(place) <= limit)) player.sendSystemMessage(message);
        }
    }

    private static List<BlockPos> places(final KingdomsSavedData data, final WorldEventRecord event)
    {
        final List<BlockPos> places = new ArrayList<>();
        for (final UUID settlement : new UUID[] {event.settlementId(), event.otherSettlementId()})
            if (settlement != null) data.settlements().get(settlement).map(SettlementRecord::anchor).ifPresent(places::add);
        if (event.roadId() != null)
            data.roads().get(event.roadId()).ifPresent(road -> endpoints(data, road).forEach(places::add));
        return places;
    }

    private static List<BlockPos> endpoints(final KingdomsSavedData data, final RoadRecord road)
    {
        final List<BlockPos> result = new ArrayList<>();
        data.settlements().get(road.firstSettlementId()).map(SettlementRecord::anchor).ifPresent(result::add);
        data.settlements().get(road.secondSettlementId()).map(SettlementRecord::anchor).ifPresent(result::add);
        return result;
    }

    public Stats stats()
    {
        return new Stats(updates, evaluations, planned, activated, ended, failures, updates == 0 ? 0.0D : (double) totalNanos / updates,
            maximumNanos, lastCandidates);
    }

    public static WorldEventSettings settingsFromConfig()
    {
        final var config = KingdomsConfig.SERVER;
        return new WorldEventSettings(config.eventsEnabled.get(), config.eventsEvaluationIntervalTicks.get(), config.eventsChance.get(),
            config.eventsMaxActiveGlobal.get(), config.eventsMaxActivePerSettlement.get(), config.eventsCooldownTicks.get(),
            config.eventsNoticeTicks.get(), config.eventsDurationTicks.get(), config.eventsHistoryLimit.get(),
            config.growthPopulationHardCap.get());
    }

    static String when(final long ticks)
    {
        final long clamped = Math.max(0L, ticks);
        return clamped >= 24_000L ? String.format(Locale.ROOT, "%.1f days", clamped / 24_000.0D) : (clamped / 1_000L) + " hours";
    }
}
