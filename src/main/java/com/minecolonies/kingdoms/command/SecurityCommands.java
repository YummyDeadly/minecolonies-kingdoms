package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.military.GarrisonManager;
import com.minecolonies.kingdoms.military.GarrisonRecord;
import com.minecolonies.kingdoms.military.MilitaryManager;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecurityRules;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
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

/** Operator diagnostics and controls for settlement security, garrisons, patrols, and guards (Phase 9). */
final class SecurityCommands
{
    private SecurityCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> securityCommand()
    {
        return Commands.literal("security")
            .requires(source -> source.hasPermission(2))
            .executes(context -> list(context.getSource()))
            .then(Commands.literal("list").executes(context -> list(context.getSource())))
            .then(Commands.literal("stats").executes(context -> stats(context.getSource())))
            .then(Commands.literal("evaluate").executes(context -> evaluate(context.getSource())))
            .then(Commands.literal("info").then(Commands.argument("settlement", StringArgumentType.word())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "settlement")))))
            .then(Commands.literal("set-garrison").then(Commands.argument("settlement", StringArgumentType.word())
                .then(Commands.argument("soldiers", IntegerArgumentType.integer(0, 1_000))
                    .executes(context -> setGarrison(context.getSource(), StringArgumentType.getString(context, "settlement"),
                        IntegerArgumentType.getInteger(context, "soldiers"))))))
            .then(Commands.literal("guards")
                .then(Commands.literal("materialize").then(Commands.argument("settlement", StringArgumentType.word())
                    .executes(context -> materialize(context.getSource(), StringArgumentType.getString(context, "settlement")))))
                .then(Commands.literal("dematerialize").then(Commands.argument("settlement", StringArgumentType.word())
                    .executes(context -> dematerialize(context.getSource(), StringArgumentType.getString(context, "settlement"))))));
    }

    private static int list(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<GarrisonRecord> garrisons = data.military().garrisons().stream()
            .sorted(Comparator.comparingInt(GarrisonRecord::security).reversed()).toList();
        source.sendSuccess(() -> Component.literal("Garrisons: " + garrisons.size() + ", last evaluation at " + data.military().lastEvaluatedAt())
            .withStyle(ChatFormatting.GOLD), false);
        final long gameTime = source.getServer().overworld().getGameTime();
        for (final GarrisonRecord garrison : garrisons)
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "- %s [%s]: security %d (level %d), soldiers %d/%d%s%s, guards %d",
                name(data, garrison.settlementId()), garrison.settlementId(), garrison.security(), SecurityRules.level(garrison.security()),
                garrison.strength(), garrison.capacity(), garrison.detached() > 0 ? " (+" + garrison.detached() + " away)" : "",
                garrison.alertAt(gameTime) ? ", on alert" : "", GarrisonManager.getInstance().guardsOf(garrison.settlementId()))), false);
        return garrisons.size();
    }

    private static int info(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final SettlementRecord settlement = settlement(source, data, text);
        if (settlement == null) return 0;
        final GarrisonRecord garrison = data.military().garrison(settlement.id()).orElse(null);
        if (garrison == null)
        {
            source.sendFailure(Component.literal(settlement.name() + " has no garrison yet (not an NPC settlement, or not evaluated; try /kingdoms security evaluate)"));
            return 0;
        }
        final long gameTime = source.getServer().overworld().getGameTime();
        final int civic = MilitaryService.civicBuildings(data, settlement.id());
        final SecurityRules.Breakdown breakdown = MilitaryService.breakdown(data, settlement, garrison, civic, gameTime);
        final List<String> lines = new ArrayList<>();
        lines.add(settlement.name() + " (" + settlement.type() + ") security " + garrison.security() + " (level " + SecurityRules.level(garrison.security())
            + ", lends " + 8 * SecurityRules.level(garrison.security()) + " threat reduction to its roads)");
        lines.add(String.format(Locale.ROOT, "  = archetype %d + garrison %d + civic %d + defences %d - road danger %d - sacked %d (now %d)",
            breakdown.archetype(), breakdown.garrison(), breakdown.civic(), breakdown.defences(), breakdown.roadDanger(), breakdown.sacked(),
            breakdown.total()));
        lines.add(String.format(Locale.ROOT, "soldiers %d at home / capacity %d (%d away with armies); recruitment progress %.2f; recruited %d, lost %d",
            garrison.strength(), garrison.capacity(), garrison.detached(), garrison.recruitProgress(), garrison.recruited(), garrison.casualties()));
        lines.add("recent defences " + garrison.recentDefences() + ", alert " + (garrison.alertAt(gameTime) ? "until " + garrison.alertUntil() : "no")
            + ", sacked " + (garrison.vulnerableAt(gameTime) ? "until " + garrison.vulnerableUntil() : "no"));
        lines.add("patrols sent " + garrison.sorties() + (garrison.lastSortieAt() == Long.MIN_VALUE ? "" : ", last at " + garrison.lastSortieAt())
            + "; next possible " + (MilitaryService.sortieReady(garrison, gameTime, GarrisonManager.settingsFromConfig()) ? "now" : "later")
            + "; target now: " + MilitaryService.sortieTarget(data, settlement, GarrisonManager.settingsFromConfig(),
                    com.minecolonies.kingdoms.bandit.BanditManager.getInstance()::observed)
                .map(encounter -> "camp fight " + encounter.id() + " at " + encounter.position().toShortString()).orElse("none"));
        lines.add("guards in the world: " + GarrisonManager.getInstance().guardsOf(settlement.id()) + " (responders "
            + GarrisonManager.getInstance().respondersOf(settlement.id()) + "); applied casualty events " + garrison.appliedEvents().size());
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int stats(final CommandSourceStack source)
    {
        final var guards = GarrisonManager.getInstance().stats();
        final var military = MilitaryManager.getInstance().stats();
        final var report = military.lastReport();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Security: evaluations=%d avg=%.3fms max=%.3fms; last: garrisons=%d recruited=%d patrols=%d camps cleared=%d",
            military.evaluations(), military.averageNanos() / 1_000_000.0D, military.maximumNanos() / 1_000_000.0D,
            report == null ? 0 : report.garrisons(), report == null ? 0 : report.recruited(), report == null ? 0 : report.sorties(),
            report == null ? 0 : report.campsCleared())).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Guards: in world=%d active settlements=%d | materialized=%d dematerialized=%d deaths=%d responders=%d stuck=%d failed spawns=%d errors=%d | cycle avg=%.3fms max=%.3fms (%d cycles)",
            GarrisonManager.getInstance().physicalGuards(), GarrisonManager.getInstance().activeSettlements(), guards.materialized(),
            guards.dematerialized(), guards.deaths(), guards.respondersSent(), guards.stuckRecoveries(), guards.failedSpawns(), guards.failures(),
            guards.averageNanos() / 1_000_000.0D, guards.maximumNanos() / 1_000_000.0D, guards.cycles())), false);
        return 1;
    }

    private static int evaluate(final CommandSourceStack source)
    {
        final var report = MilitaryManager.getInstance().evaluateNow(source.getServer());
        if (report == null)
        {
            source.sendFailure(Component.literal("Security evaluation failed; see the server log"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Evaluated " + report.garrisons() + " garrison(s): recruited " + report.recruited() + ", patrols "
            + report.sorties() + ", camps cleared " + report.campsCleared()), true);
        return report.garrisons();
    }

    private static int setGarrison(final CommandSourceStack source, final String text, final int soldiers)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final SettlementRecord settlement = settlement(source, data, text);
        if (settlement == null) return 0;
        final var before = MilitaryService.setStrength(data, settlement.id(), soldiers);
        if (before.isEmpty())
        {
            source.sendFailure(Component.literal(settlement.name() + " has no garrison yet; run /kingdoms security evaluate first"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(settlement.name() + " garrison " + before.get() + " -> " + soldiers
            + " (operator; security is recomputed at the next evaluation)"), true);
        return 1;
    }

    private static int materialize(final CommandSourceStack source, final String text)
    {
        final SettlementRecord settlement = settlement(source, KingdomsSavedData.get(source.getLevel()), text);
        if (settlement == null) return 0;
        final int count = GarrisonManager.getInstance().materializeNow(source.getServer(), settlement.id());
        source.sendSuccess(() -> Component.literal(count + " guard(s) at " + settlement.name() + " (held for " + GarrisonManager.MANUAL_HOLD_TICKS
            + " ticks; needs an entity-ticking anchor chunk and soldiers at home)"), true);
        return count;
    }

    private static int dematerialize(final CommandSourceStack source, final String text)
    {
        final SettlementRecord settlement = settlement(source, KingdomsSavedData.get(source.getLevel()), text);
        if (settlement == null) return 0;
        final int count = GarrisonManager.getInstance().dematerializeNow(source.getServer(), settlement.id());
        source.sendSuccess(() -> Component.literal("Removed " + count + " guard(s) at " + settlement.name() + "; garrison unchanged"), true);
        return count;
    }

    private static String name(final KingdomsSavedData data, final UUID settlementId)
    {
        return data.settlements().get(settlementId).map(SettlementRecord::name).orElse("?");
    }

    private static SettlementRecord settlement(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = BanditCommands.uuid(source, text);
        if (id == null) return null;
        final SettlementRecord settlement = data.settlements().get(id).orElse(null);
        if (settlement == null) source.sendFailure(Component.literal("Unknown settlement " + id));
        return settlement;
    }
}
