package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.worldevent.WorldEventManager;
import com.minecolonies.kingdoms.worldevent.WorldEventRecord;
import com.minecolonies.kingdoms.worldevent.WorldEventService;
import com.minecolonies.kingdoms.worldevent.WorldEventSettings;
import com.minecolonies.kingdoms.worldevent.WorldEventType;
import com.minecolonies.kingdoms.worldevent.WorldHistory;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** {@code /kingdoms event ...} and {@code /kingdoms history}: world-event diagnostics and operator controls (Phase 11). */
public final class EventCommands
{
    private EventCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> eventCommand()
    {
        return Commands.literal("event")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("stats").executes(context -> stats(context.getSource())))
            .then(Commands.literal("list").executes(context -> list(context.getSource(), false))
                .then(Commands.literal("all").executes(context -> list(context.getSource(), true))))
            .then(Commands.literal("info").then(Commands.argument("event", StringArgumentType.word())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "event")))))
            .then(Commands.literal("candidates").executes(context -> candidates(context.getSource())))
            .then(Commands.literal("history").executes(context -> history(context.getSource(), 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                    .executes(context -> history(context.getSource(), IntegerArgumentType.getInteger(context, "count")))))
            .then(Commands.literal("evaluate").executes(context -> evaluate(context.getSource())))
            .then(Commands.literal("trigger").then(Commands.argument("type", StringArgumentType.word())
                .suggests((context, builder) -> {
                    for (final WorldEventType type : WorldEventType.values()) builder.suggest(type.name().toLowerCase(Locale.ROOT));
                    return builder.buildFuture();
                })
                .then(Commands.argument("subject", StringArgumentType.word())
                    .executes(context -> trigger(context.getSource(), StringArgumentType.getString(context, "type"),
                        StringArgumentType.getString(context, "subject"), null))
                    .then(Commands.argument("other", StringArgumentType.word())
                        .executes(context -> trigger(context.getSource(), StringArgumentType.getString(context, "type"),
                            StringArgumentType.getString(context, "subject"), StringArgumentType.getString(context, "other")))))))
            .then(Commands.literal("resolve").then(Commands.argument("event", StringArgumentType.word())
                .executes(context -> end(context.getSource(), StringArgumentType.getString(context, "event"), false))))
            .then(Commands.literal("cancel").then(Commands.argument("event", StringArgumentType.word())
                .executes(context -> end(context.getSource(), StringArgumentType.getString(context, "event"), true))));
    }

    static ArgumentBuilder<CommandSourceStack, ?> historyCommand()
    {
        return Commands.literal("history")
            .requires(source -> source.hasPermission(2))
            .executes(context -> history(context.getSource(), 10))
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                .executes(context -> history(context.getSource(), IntegerArgumentType.getInteger(context, "count"))));
    }

    private static long now(final CommandSourceStack source)
    {
        return source.getServer().overworld().getGameTime();
    }

    private static void line(final CommandSourceStack source, final String text)
    {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int stats(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WorldEventSettings settings = WorldEventManager.settingsFromConfig();
        final var registry = data.worldEvents();
        final long planned = registry.open().stream().filter(value -> value.status() == WorldEventRecord.Status.PLANNED).count();
        final long active = registry.open().size() - planned;
        final var stats = WorldEventManager.getInstance().stats();
        final long next = registry.lastEvaluatedAt() < 0L ? 0L : Math.max(0L, registry.lastEvaluatedAt() + settings.evaluationIntervalTicks() - now(source));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "World events: enabled=%s planned=%d active=%d (cap %d, %d per settlement) kept=%d cooldowns=%d evaluations=%d next evaluation in %d ticks",
            settings.enabled(), planned, active, settings.maxActiveGlobal(), settings.maxActivePerSettlement(), registry.events().size(),
            registry.cooldowns(), registry.evaluations(), next)).withStyle(ChatFormatting.GOLD), false);
        line(source, String.format(Locale.ROOT,
            "session: updates=%d evaluations=%d planned=%d started=%d ended=%d failures=%d last candidates=%d | update avg=%.3fms max=%.3fms",
            stats.updates(), stats.evaluations(), stats.planned(), stats.activated(), stats.ended(), stats.failures(), stats.lastCandidates(),
            stats.averageNanos() / 1_000_000.0D, stats.maximumNanos() / 1_000_000.0D));
        line(source, "history: " + data.history().size() + "/" + data.history().limit() + " entries");
        return 1;
    }

    private static int list(final CommandSourceStack source, final boolean all)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final long gameTime = now(source);
        final List<WorldEventRecord> events = data.worldEvents().events().stream().filter(value -> all || value.open())
            .sorted(Comparator.comparingLong(WorldEventRecord::plannedAt).reversed()).toList();
        source.sendSuccess(() -> Component.literal("World events: " + events.size() + (all ? " (including ended)" : " open"))
            .withStyle(ChatFormatting.GOLD), false);
        for (final WorldEventRecord event : events)
            line(source, "- " + event.id() + " " + event.type() + " " + event.status() + " " + WorldEventService.subjectName(data, event)
                + (event.open() ? " (" + (event.status() == WorldEventRecord.Status.PLANNED ? "starts in " + (event.startsAt() - gameTime)
                    : "ends in " + (event.endsAt() - gameTime)) + " ticks)" : " -> " + event.outcome()));
        return events.size();
    }

    private static WorldEventRecord event(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final WorldEventRecord event = data.worldEvents().resolve(text).orElse(null);
        if (event == null) source.sendFailure(Component.literal("Unknown world event " + text));
        return event;
    }

    private static int info(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WorldEventRecord event = event(source, data, text);
        if (event == null) return 0;
        final long gameTime = now(source);
        final List<String> lines = new ArrayList<>();
        lines.add(event.type() + " " + event.id() + ": " + event.status() + " (" + event.cause() + ")");
        lines.add("subject: " + WorldEventService.subjectName(data, event) + (event.resource() == null ? ""
            : " resource " + event.resource().name().toLowerCase(Locale.ROOT)));
        lines.add("why: " + event.reason());
        lines.add(String.format(Locale.ROOT, "times: planned %d, starts %d, ends %d, resolved %d (now %d); magnitude %.2f; seed %d",
            event.plannedAt(), event.startsAt(), event.endsAt(), event.resolvedAt(), gameTime, event.magnitude(), event.seed()));
        lines.add(String.format(Locale.ROOT, "effect applied %s, given back %s: production %+.1f/day, stock lost %d, soldiers %+d, relation %+d, people moved %d",
            event.applied(), event.reverted(), event.productionDelta(), event.stockLost(), event.soldiers(), event.relationChange(),
            event.peopleMoved()));
        lines.add("outcome: " + (event.outcome().isEmpty() ? "none yet" : event.outcome()));
        lines.addAll(links(data, event, gameTime));
        final UUID started = WorldHistory.entryId(WorldHistory.Kind.EVENT_STARTED, event.id());
        final UUID ended = WorldHistory.entryId(WorldHistory.Kind.EVENT_ENDED, event.id());
        lines.add("history: started " + (data.history().contains(started) ? "recorded" : "no") + ", ended "
            + (data.history().contains(ended) ? "recorded" : "no"));
        lines.forEach(value -> line(source, value));
        return 1;
    }

    /** What the event is linked to right now (the systems it acts through). */
    private static List<String> links(final KingdomsSavedData data, final WorldEventRecord event, final long gameTime)
    {
        final List<String> lines = new ArrayList<>();
        switch (event.type())
        {
            case HARVEST_FAILURE, TRADE_FAIR -> data.colony(event.settlementId()).ifPresent(colony -> {
                final var resource = colony.economy().resource(event.resource() == null ? com.minecolonies.kingdoms.economy.EconomicResource.FOOD
                    : event.resource());
                lines.add(String.format(Locale.ROOT, "economy now: stock %d (reserve %d), production %.1f/day, consumption %.1f/day; needs %s",
                    resource.stockpile().amount(), resource.stockpile().desiredReserve(), resource.flow().productionPerDay(),
                    resource.flow().consumptionPerDay(), colony.needs().stream().map(need -> need.type() + ":" + need.severity()).toList()));
                lines.add("contracts: delivery offers of " + WorldEventService.subjectName(data, event) + " come from these needs ("
                    + data.contracts().offers(event.settlementId()).size() + " open offers)");
            });
            case BANDIT_SURGE -> data.bandits().threat(event.roadId()).ifPresent(threat -> lines.add(String.format(Locale.ROOT,
                "road threat now %.1f (target %.1f, event contributor %.1f now, %.1f in the last evaluation)", threat.threat(),
                threat.lastContributors().target(), WorldEventService.threatContribution(data, event.roadId(), gameTime),
                threat.lastContributors().event())));
            case GARRISON_FEVER, MILITIA_MUSTER -> data.military().garrison(event.settlementId()).ifPresent(garrison -> lines.add(
                "garrison now: " + garrison.strength() + "/" + garrison.capacity() + " soldiers, security " + garrison.security()
                    + ", event applied to garrison: " + garrison.applied(event.id())));
            case BORDER_INCIDENT, ENVOY_VISIT ->
            {
                final Faction a = data.faction(event.factionA()).orElse(null);
                final Faction b = data.faction(event.factionB()).orElse(null);
                if (a != null && b != null)
                    lines.add("relation now " + DiplomacyService.relation(a, b) + "; war: "
                        + WarService.openWarBetween(data, a.id(), b.id()).map(war -> war.id() + " " + war.status()).orElse("none")
                        + "; hostility streak " + data.war().state().hostility(a.id(), b.id()));
            }
            case MIGRATION -> lines.add("population now: " + data.colony(event.settlementId()).map(NPCColonyData::population).orElse(-1)
                + " → " + data.colony(event.otherSettlementId()).map(NPCColonyData::population).orElse(-1));
        }
        return lines;
    }

    private static int candidates(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<WorldEventService.Candidate> candidates = WorldEventService.candidates(data, now(source), WorldEventManager.settingsFromConfig());
        source.sendSuccess(() -> Component.literal("Eligible world events now (after caps and cooldowns): " + candidates.size())
            .withStyle(ChatFormatting.GOLD), false);
        for (final WorldEventService.Candidate candidate : candidates.subList(0, Math.min(30, candidates.size())))
            line(source, String.format(Locale.ROOT, "- %s weight %.1f: %s", candidate.type(), candidate.weight(), candidate.reason()));
        return candidates.size();
    }

    private static int history(final CommandSourceStack source, final int count)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<WorldHistory.Entry> entries = data.history().latest(count);
        source.sendSuccess(() -> Component.literal("World history (newest first, " + entries.size() + " of " + data.history().size() + "):")
            .withStyle(ChatFormatting.GOLD), false);
        for (final WorldHistory.Entry entry : entries)
            line(source, "- [" + entry.gameTime() + "] " + entry.kind() + ": " + entry.detail());
        return entries.size();
    }

    private static int evaluate(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final long gameTime = now(source);
        final WorldEventService.Report report = WorldEventService.evaluateNow(data, gameTime, WorldEventManager.settingsFromConfig());
        source.sendSuccess(() -> Component.literal("Evaluated: " + report.candidates() + " candidate(s); planned "
            + report.planned().map(event -> event.type() + " " + event.id() + " at " + WorldEventService.subjectName(data, event)).orElse("nothing")
            + "; started " + report.activated().size() + ", ended " + report.ended().size()), true);
        return 1;
    }

    private static int trigger(final CommandSourceStack source, final String typeText, final String subjectText, final String otherText)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WorldEventType type = WorldEventType.parse(typeText).orElse(null);
        if (type == null)
        {
            source.sendFailure(Component.literal("Unknown event type " + typeText));
            return 0;
        }
        UUID subject = subject(data, subjectText);
        UUID other = otherText == null ? null : subject(data, otherText);
        if (type.scope() == WorldEventType.Scope.PAIR)
        {
            // a settlement stands for its faction in diplomatic events
            subject = factionOf(data, subject);
            other = factionOf(data, other);
        }
        if (subject == null || (otherText != null && other == null))
        {
            source.sendFailure(Component.literal("Unknown subject (use a settlement, road, or faction UUID or a unique prefix of 6+ characters)"));
            return 0;
        }
        final var created = WorldEventService.trigger(data, type, subject, other, now(source), WorldEventManager.settingsFromConfig(), true);
        if (created.isEmpty())
        {
            source.sendFailure(Component.literal("Not eligible: " + type + " for " + subjectText
                + " (see /kingdoms event candidates for what can happen and why)"));
            return 0;
        }
        final WorldEventRecord event = created.get();
        source.sendSuccess(() -> Component.literal("Planned " + event.type() + " " + event.id() + " at " + WorldEventService.subjectName(data, event)
            + "; it starts in " + (event.startsAt() - now(source)) + " ticks"), true);
        return 1;
    }

    private static int end(final CommandSourceStack source, final String text, final boolean cancel)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WorldEventRecord event = event(source, data, text);
        if (event == null) return 0;
        final boolean changed = cancel ? WorldEventService.cancel(data, event, now(source)) : WorldEventService.resolveNow(data, event, now(source));
        source.sendSuccess(() -> Component.literal(changed ? "Event " + event.id() + " is now " + event.status() + ": " + event.outcome()
            : "Event " + event.id() + " is already " + event.status() + "; nothing changed"), true);
        return changed ? 1 : 0;
    }

    private static UUID factionOf(final KingdomsSavedData data, final UUID id)
    {
        if (id == null) return null;
        return data.colony(id).map(NPCColonyData::factionId).orElse(id);
    }

    /** A settlement, road, or faction UUID, or a unique prefix (6+ characters) of one. */
    private static UUID subject(final KingdomsSavedData data, final String text)
    {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException ignored) { /* prefix */ }
        final String prefix = text.toLowerCase(Locale.ROOT);
        if (prefix.length() < 6) return null;
        final List<UUID> matches = new ArrayList<>();
        data.settlements().records().stream().map(SettlementRecord::id).filter(id -> id.toString().startsWith(prefix)).forEach(matches::add);
        data.roads().roads().stream().map(RoadRecord::id).filter(id -> id.toString().startsWith(prefix)).forEach(matches::add);
        data.factions().stream().map(Faction::id).filter(id -> id.toString().startsWith(prefix)).forEach(matches::add);
        final List<UUID> distinct = matches.stream().distinct().toList();
        return distinct.size() == 1 ? distinct.getFirst() : null;
    }
}
