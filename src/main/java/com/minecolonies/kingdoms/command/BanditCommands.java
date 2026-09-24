package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.bandit.BanditEncounter;
import com.minecolonies.kingdoms.bandit.BanditManager;
import com.minecolonies.kingdoms.bandit.EncounterRules;
import com.minecolonies.kingdoms.bandit.EncounterService;
import com.minecolonies.kingdoms.bandit.RoadThreat;
import com.minecolonies.kingdoms.bandit.ThreatRules;
import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Operator diagnostics and controls for bandit threat and encounters (Phase 8). */
final class BanditCommands
{
    private BanditCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> banditCommand()
    {
        return Commands.literal("bandit")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("stats").executes(context -> stats(context.getSource())))
            .then(Commands.literal("list").executes(context -> list(context.getSource(), false))
                .then(Commands.literal("all").executes(context -> list(context.getSource(), true))))
            .then(Commands.literal("info").then(Commands.argument("encounter", StringArgumentType.word())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "encounter")))))
            .then(Commands.literal("threat").executes(context -> threats(context.getSource()))
                .then(Commands.argument("road-or-settlement", StringArgumentType.word())
                    .executes(context -> threat(context.getSource(), StringArgumentType.getString(context, "road-or-settlement")))))
            .then(Commands.literal("evaluate").executes(context -> evaluate(context.getSource())))
            .then(Commands.literal("spawn-test").then(Commands.argument("road", StringArgumentType.word())
                .executes(context -> spawnTest(context.getSource(), StringArgumentType.getString(context, "road"), false))
                .then(Commands.literal("ambush")
                    .executes(context -> spawnTest(context.getSource(), StringArgumentType.getString(context, "road"), false)))
                .then(Commands.literal("roadblock")
                    .executes(context -> spawnTest(context.getSource(), StringArgumentType.getString(context, "road"), true)))))
            .then(Commands.literal("set-threat").then(Commands.argument("road", StringArgumentType.word())
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                    .executes(context -> setThreat(context.getSource(), StringArgumentType.getString(context, "road"),
                        DoubleArgumentType.getDouble(context, "value"))))))
            .then(Commands.literal("materialize").then(Commands.argument("encounter", StringArgumentType.word())
                .executes(context -> materialize(context.getSource(), StringArgumentType.getString(context, "encounter")))))
            .then(Commands.literal("dematerialize").then(Commands.argument("encounter", StringArgumentType.word())
                .executes(context -> dematerialize(context.getSource(), StringArgumentType.getString(context, "encounter")))))
            .then(Commands.literal("resolve").then(Commands.argument("encounter", StringArgumentType.word())
                .then(Commands.argument("outcome", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        for (final EncounterRules.Outcome outcome : EncounterRules.Outcome.values())
                            builder.suggest(outcome.name().toLowerCase(Locale.ROOT));
                        builder.suggest("cancel");
                        return builder.buildFuture();
                    })
                    .executes(context -> resolve(context.getSource(), StringArgumentType.getString(context, "encounter"),
                        StringArgumentType.getString(context, "outcome"))))));
    }

    private static int stats(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var manager = BanditManager.getInstance();
        final var stats = manager.stats();
        final long planned = data.bandits().open().stream().filter(value -> value.status() == BanditEncounter.Status.PLANNED).count();
        final long active = data.bandits().open().size() - planned;
        final long dangerous = data.bandits().threats().stream().filter(value -> value.threat() >= ThreatRules.AMBUSH_FLOOR).count();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Bandits: roads tracked=%d (dangerous=%d) encounters planned=%d active=%d physical=%d bandits=%d assessed shipments=%d",
            data.bandits().threats().size(), dangerous, planned, active, manager.physicalEncounters(), manager.physicalBandits(),
            data.bandits().assessments())).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Camps: active=%d built=%d unbuildable=%d ended=%d | established=%d recruits=%d structure ops=%d",
            data.bandits().activeCamps().size(),
            data.bandits().camps().stream().filter(value -> value.structure() == com.minecolonies.kingdoms.bandit.BanditCamp.Structure.BUILT).count(),
            data.bandits().camps().stream().filter(value -> value.structure() == com.minecolonies.kingdoms.bandit.BanditCamp.Structure.UNBUILDABLE).count(),
            data.bandits().camps().stream().filter(value -> !value.active()).count(),
            stats.campsEstablished(), stats.campRecruits(), stats.campWorks())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "evaluations=%d planned=%d activated=%d abstract=%d physical=%d overruns=%d materialized=%d failures=%d dematerialized=%d deaths=%d fled=%d stalls=%d | cycle avg=%.3fms max=%.3fms (%d cycles)",
            stats.evaluations(), stats.planned(), stats.activated(), stats.abstractResolutions(), stats.physicalResolutions(),
            stats.overruns(), stats.materializations(), stats.materializationFailures(), stats.dematerializations(), stats.banditDeaths(),
            stats.fled(), stats.stalls(), stats.averageNanos() / 1_000_000.0D, stats.maximumNanos() / 1_000_000.0D, stats.cycles())), false);
        return 1;
    }

    private static int list(final CommandSourceStack source, final boolean all)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<BanditEncounter> encounters = data.bandits().encounters().stream().filter(value -> all || value.open())
            .sorted(Comparator.comparingLong(BanditEncounter::createdAt).reversed()).toList();
        source.sendSuccess(() -> Component.literal("Encounters: " + encounters.size() + (all ? " (including resolved)" : " open"))
            .withStyle(ChatFormatting.GOLD), false);
        for (final BanditEncounter encounter : encounters)
            source.sendSuccess(() -> Component.literal("- " + encounter.id() + " " + encounter.kind() + " " + encounter.status() + "/"
                + encounter.representation() + " " + roadName(data, encounter.roadId()) + " @ " + encounter.position().toShortString()
                + " strength " + encounter.remainingStrength() + "/" + encounter.strength()
                + (encounter.outcome() == null ? "" : " outcome=" + encounter.outcome() + " lost=" + encounter.cargoLost())), false);
        return encounters.size();
    }

    private static int info(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final BanditEncounter encounter = encounter(source, data, text);
        if (encounter == null) return 0;
        final long gameTime = source.getServer().overworld().getGameTime();
        final RoadThreat threat = data.bandits().threat(encounter.roadId()).orElse(null);
        final List<String> lines = new java.util.ArrayList<>();
        lines.add(encounter.kind() + " " + encounter.id() + ": " + encounter.status() + " (" + encounter.representation() + ")");
        lines.add("road " + roadName(data, encounter.roadId()) + " [" + encounter.roadId() + "] at " + encounter.position().toShortString()
            + " in " + encounter.dimension());
        lines.add(String.format(Locale.ROOT, "why: road threat %.1f when created (now %.1f); strength %d, remaining %d; seed %d",
            encounter.threatAtCreation(), threat == null ? 0.0D : threat.threat(), encounter.strength(), encounter.remainingStrength(),
            encounter.seed()));
        if (encounter.shipmentId() != null)
        {
            final String shipment = data.tradeLedger().shipment(encounter.shipmentId()).map(value -> value.status() + " "
                + value.amount() + " " + value.resource() + " (lost " + value.lostAmount() + ", deliverable " + value.deliverableAmount()
                + ", progress " + String.format(Locale.ROOT, "%.2f", EncounterService.progress(value, gameTime)) + ", held "
                + value.heldAt(gameTime) + ")").orElse("<gone>");
            lines.add("shipment " + encounter.shipmentId() + ": " + shipment + "; ambush at progress "
                + String.format(Locale.ROOT, "%.2f", encounter.triggerProgress()));
        }
        lines.add("times: created " + encounter.createdAt() + ", activated " + encounter.activatedAt() + ", abstract resolution at "
            + encounter.resolveAt() + ", expires " + encounter.expiresAt() + ", resolved " + encounter.resolvedAt() + " (now " + gameTime + ")");
        final String result;
        if (encounter.cause() == null) result = "none yet";
        else if (encounter.outcome() == null) result = encounter.status() + " (" + encounter.cause() + ")";
        else if (encounter.kind() == BanditEncounter.Kind.AMBUSH)
            result = encounter.outcome() + " by " + encounter.cause() + ", cargo " + encounter.cargoLost() + "/" + encounter.cargoBefore() + " "
                + encounter.cargoResource() + " lost, delay " + encounter.delayTicks();
        else result = encounter.outcome() + " by " + encounter.cause();
        lines.add("result: " + result + "; consequences applied " + encounter.consequencesApplied());
        lines.add("defenders: " + encounter.defenders().stream().map(id -> playerName(source, id)).toList());
        BanditManager.getInstance().physicalOf(encounter.id()).ifPresentOrElse(
            value -> lines.add("physical: " + value.bandits() + " bandit(s) since " + value.since() + ", observer "
                + (value.observer() == null ? "manual" : playerName(source, value.observer()))),
            () -> lines.add("physical: none"));
        final List<Contract> contracts = data.contracts().contracts().stream()
            .filter(value -> encounter.id().equals(value.objective().targetEncounter())).toList();
        lines.add("contracts: " + (contracts.isEmpty() ? "none" : contracts.stream()
            .map(value -> value.id() + " " + value.kind() + " " + value.status() + (value.holder() == null ? "" : " holder=" + playerName(source, value.holder())))
            .toList()));
        if (threat != null) lines.add("next encounter on this road: " + nextEncounter(threat, gameTime));
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int threats(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<RoadThreat> threats = data.bandits().threats().stream()
            .sorted(Comparator.comparingDouble(RoadThreat::threat).reversed()).limit(20).toList();
        source.sendSuccess(() -> Component.literal("Road threat (top 20 of " + data.bandits().threats().size() + "), last evaluation at "
            + data.bandits().lastEvaluatedAt()).withStyle(ChatFormatting.GOLD), false);
        threats.forEach(value -> source.sendSuccess(() -> threatLine(data, value, source.getServer().overworld().getGameTime()), false));
        return threats.size();
    }

    private static int threat(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final UUID id = uuid(source, text);
        if (id == null) return 0;
        final long gameTime = source.getServer().overworld().getGameTime();
        final Optional<RoadRecord> road = data.roads().get(id);
        if (road.isPresent())
        {
            final RoadThreat value = data.bandits().threat(id).orElse(null);
            if (value == null)
            {
                source.sendSuccess(() -> Component.literal(roadName(data, id) + ": not evaluated yet"), false);
                return 0;
            }
            source.sendSuccess(() -> threatLine(data, value, gameTime), false);
            final var c = value.lastContributors();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  target %.1f = base %.1f + traffic %.1f + remoteness %.1f + momentum %.1f + camp %.1f - security %.1f - suppression %.1f",
                c.target(), c.base(), c.traffic(), c.remoteness(), c.momentum(), c.camp(), c.security(), c.suppression())), false);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  camp pressure %d, camps so far %d, camp cooldown %s; active camp: %s", value.campPressure(), value.camps(),
                value.campCoolingDownAt(gameTime) ? "until " + value.campCooldownUntil() : "none",
                data.bandits().activeCampOn(id).map(camp -> camp.id() + " at " + camp.position().toShortString()).orElse("none"))), false);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  ambush chance per caravan now %.0f%%; raids %d, defeats %d, roadblocks %d; next encounter: %s",
                100.0D * ambushChanceNow(value, gameTime), value.raids(), value.defeats(), value.roadblocks(),
                nextEncounter(value, gameTime))), false);
            return 1;
        }
        final SettlementRecord settlement = data.settlements().get(id).orElse(null);
        if (settlement == null)
        {
            source.sendFailure(Component.literal("No road or settlement " + id));
            return 0;
        }
        final List<RoadRecord> roads = data.roads().incident(id);
        source.sendSuccess(() -> Component.literal(settlement.name() + ": " + roads.size() + " road(s)").withStyle(ChatFormatting.GOLD), false);
        for (final RoadRecord value : roads)
            data.bandits().threat(value.id()).ifPresentOrElse(threat -> source.sendSuccess(() -> threatLine(data, threat, gameTime), false),
                () -> source.sendSuccess(() -> Component.literal("- " + roadName(data, value.id()) + " [" + value.id() + "]: not tracked ("
                    + value.status() + ")"), false));
        return roads.size();
    }

    private static int evaluate(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        BanditManager.getInstance().evaluate(data, source.getServer().overworld().getGameTime(), BanditManager.settingsFromConfig(),
            com.minecolonies.kingdoms.contract.ContractManager.settings());
        source.sendSuccess(() -> Component.literal("Evaluated " + data.bandits().threats().size() + " road(s); open encounters "
            + data.bandits().open().size()), true);
        return 1;
    }

    private static int spawnTest(final CommandSourceStack source, final String text, final boolean roadblock)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final UUID id = uuid(source, text);
        if (id == null) return 0;
        final RoadRecord road = data.roads().get(id).orElse(null);
        if (road == null)
        {
            source.sendFailure(Component.literal("Unknown road " + id));
            return 0;
        }
        final Optional<BanditEncounter> created = BanditManager.getInstance().spawnTest(source.getServer(), road, roadblock);
        if (created.isEmpty())
        {
            source.sendFailure(Component.literal(roadblock
                ? "No roadblock: the road already has one, or no remote non-bridge point exists"
                : "No ambush: no in-transit shipment uses this road ahead of a remote point (see /kingdoms trade shipments)"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Created " + created.get().kind() + " " + created.get().id() + " at "
            + created.get().position().toShortString() + " (" + created.get().status() + ", strength " + created.get().strength() + ")"), true);
        return 1;
    }

    private static int setThreat(final CommandSourceStack source, final String text, final double value)
    {
        final UUID id = uuid(source, text);
        if (id == null) return 0;
        if (KingdomsSavedData.get(source.getLevel()).roads().get(id).isEmpty())
        {
            source.sendFailure(Component.literal("Unknown road " + id));
            return 0;
        }
        BanditManager.getInstance().setThreat(source.getServer(), id, value);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Road %s threat set to %.1f (it keeps moving towards its target by the configured step)",
            id, value)), true);
        return 1;
    }

    private static int materialize(final CommandSourceStack source, final String text)
    {
        final BanditEncounter encounter = encounter(source, KingdomsSavedData.get(source.getLevel()), text);
        if (encounter == null) return 0;
        final boolean physical = BanditManager.getInstance().materializeNow(source.getServer(), encounter.id());
        source.sendSuccess(() -> Component.literal(physical ? "Materialized " + BanditManager.getInstance().physicalOf(encounter.id())
            .map(value -> value.bandits()).orElse(0) + " bandit(s); held for " + BanditManager.MANUAL_HOLD_TICKS + " ticks"
            : "Not materialized (not ACTIVE, peaceful difficulty, chunk not entity-ticking, caps reached, or no safe outdoor position); status " + encounter.status()), true);
        return physical ? 1 : 0;
    }

    private static int dematerialize(final CommandSourceStack source, final String text)
    {
        final BanditEncounter encounter = encounter(source, KingdomsSavedData.get(source.getLevel()), text);
        if (encounter == null) return 0;
        final boolean was = BanditManager.getInstance().dematerializeNow(source.getServer(), encounter.id());
        source.sendSuccess(() -> Component.literal((was ? "Dematerialized" : "Was not physical") + "; suppressed for "
            + BanditManager.MANUAL_HOLD_TICKS + " ticks. Strategic state unchanged."), true);
        return 1;
    }

    private static int resolve(final CommandSourceStack source, final String text, final String outcomeText)
    {
        final BanditEncounter encounter = encounter(source, KingdomsSavedData.get(source.getLevel()), text);
        if (encounter == null) return 0;
        final EncounterService.Resolution resolution;
        if ("cancel".equalsIgnoreCase(outcomeText)) resolution = BanditManager.getInstance().cancelNow(source.getServer(), encounter);
        else
        {
            final EncounterRules.Outcome outcome;
            try { outcome = EncounterRules.Outcome.valueOf(outcomeText.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception)
            {
                source.sendFailure(Component.literal("Unknown outcome " + outcomeText));
                return 0;
            }
            resolution = BanditManager.getInstance().resolveNow(source.getServer(), encounter, outcome);
        }
        if (!resolution.applied())
        {
            source.sendFailure(Component.literal("Encounter " + encounter.id() + " is already " + encounter.status() + "; nothing changed"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Encounter " + encounter.id() + " -> " + encounter.status() + " (" + encounter.outcome()
            + "), cargo lost " + resolution.cargoLost() + ", contracts closed " + resolution.contracts().size()), true);
        return 1;
    }

    private static Component threatLine(final KingdomsSavedData data, final RoadThreat value, final long gameTime)
    {
        return Component.literal(String.format(Locale.ROOT, "- %s [%s]: threat %.1f (target %.1f, traffic %.1f, momentum %.1f)%s",
            roadName(data, value.roadId()), value.roadId(), value.threat(), value.lastContributors().target(), value.recentTraffic(),
            value.raidMomentum(), value.suppressedAt(gameTime) ? " suppressed" : ""));
    }

    static String roadName(final KingdomsSavedData data, final UUID roadId)
    {
        return data.roads().get(roadId).map(road -> name(data, road.firstSettlementId()) + " - " + name(data, road.secondSettlementId()))
            .orElse("<removed road>");
    }

    private static String name(final KingdomsSavedData data, final UUID settlementId)
    {
        return data.settlements().get(settlementId).map(SettlementRecord::name).orElse("?");
    }

    static String playerName(final CommandSourceStack source, final UUID playerId)
    {
        final var player = source.getServer().getPlayerList().getPlayer(playerId);
        return player == null ? playerId.toString() : player.getGameProfile().getName();
    }

    private static BanditEncounter encounter(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = uuid(source, text);
        if (id == null) return null;
        final BanditEncounter encounter = data.bandits().encounter(id).orElse(null);
        if (encounter == null) source.sendFailure(Component.literal("Unknown encounter " + id));
        return encounter;
    }

    static UUID uuid(final CommandSourceStack source, final String text)
    {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException exception)
        {
            source.sendFailure(Component.literal("Invalid UUID: " + text));
            return null;
        }
    }

    /** The chance a newly assessed caravan is ambushed on this road right now (zero while suppressed or cooling down). */
    private static double ambushChanceNow(final RoadThreat threat, final long gameTime)
    {
        if (threat.suppressedAt(gameTime) || threat.coolingDownAt(gameTime)) return 0.0D;
        return ThreatRules.ambushChance(threat.threat(), BanditManager.settingsFromConfig().ambushChanceAtMaxThreat());
    }

    private static String nextEncounter(final RoadThreat threat, final long gameTime)
    {
        final long after = Math.max(threat.coolingDownAt(gameTime) ? threat.cooldownUntil() : gameTime,
            threat.suppressedAt(gameTime) ? threat.suppressedUntil() : gameTime);
        if (after <= gameTime) return "possible now (if the threat allows it)";
        return "not before " + after + " (in " + (after - gameTime) + " ticks" + (threat.suppressedAt(gameTime) ? ", bandits suppressed" : "") + ")";
    }
}
