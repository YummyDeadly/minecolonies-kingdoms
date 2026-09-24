package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.war.ArmyManager;
import com.minecolonies.kingdoms.war.ArmyRecord;
import com.minecolonies.kingdoms.war.BattleRecord;
import com.minecolonies.kingdoms.war.CampaignService;
import com.minecolonies.kingdoms.war.WarManager;
import com.minecolonies.kingdoms.war.WarRecord;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.world.road.RoadRoute;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Operator diagnostics and controls for wars, armies, and battles (Phase 10). */
final class WarCommands
{
    private WarCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> warCommand()
    {
        return Commands.literal("war")
            .requires(source -> source.hasPermission(2))
            .executes(context -> list(context.getSource(), false))
            .then(Commands.literal("list").executes(context -> list(context.getSource(), false))
                .then(Commands.literal("all").executes(context -> list(context.getSource(), true))))
            .then(Commands.literal("info").then(Commands.argument("war", StringArgumentType.word())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "war")))))
            .then(Commands.literal("declare").then(Commands.argument("attacker", StringArgumentType.word())
                .then(Commands.argument("defender", StringArgumentType.word())
                    .executes(context -> declare(context.getSource(), StringArgumentType.getString(context, "attacker"),
                        StringArgumentType.getString(context, "defender"), false))
                    .then(Commands.literal("force").executes(context -> declare(context.getSource(), StringArgumentType.getString(context, "attacker"),
                        StringArgumentType.getString(context, "defender"), true))))))
            .then(Commands.literal("mobilize").then(Commands.argument("war", StringArgumentType.word())
                .executes(context -> mobilize(context.getSource(), StringArgumentType.getString(context, "war")))))
            .then(Commands.literal("ceasefire").then(Commands.argument("war", StringArgumentType.word())
                .executes(context -> ceasefire(context.getSource(), StringArgumentType.getString(context, "war")))))
            .then(Commands.literal("peace").then(Commands.argument("war", StringArgumentType.word())
                .executes(context -> peace(context.getSource(), StringArgumentType.getString(context, "war"), WarRecord.Result.WHITE_PEACE))
                .then(Commands.literal("attacker").executes(context -> peace(context.getSource(), StringArgumentType.getString(context, "war"),
                    WarRecord.Result.ATTACKER_VICTORY)))
                .then(Commands.literal("defender").executes(context -> peace(context.getSource(), StringArgumentType.getString(context, "war"),
                    WarRecord.Result.DEFENDER_VICTORY)))))
            .then(Commands.literal("evaluate").executes(context -> evaluate(context.getSource())))
            .then(Commands.literal("battles").executes(context -> battles(context.getSource())))
            .then(Commands.literal("battle").then(Commands.argument("battle", StringArgumentType.word())
                .executes(context -> battle(context.getSource(), StringArgumentType.getString(context, "battle")))))
            .then(Commands.literal("resolve").then(Commands.argument("battle", StringArgumentType.word())
                .executes(context -> resolve(context.getSource(), StringArgumentType.getString(context, "battle")))))
            .then(Commands.literal("stats").executes(context -> stats(context.getSource())));
    }

    static ArgumentBuilder<CommandSourceStack, ?> armyCommand()
    {
        return Commands.literal("army")
            .requires(source -> source.hasPermission(2))
            .executes(context -> armies(context.getSource()))
            .then(Commands.literal("list").executes(context -> armies(context.getSource())))
            .then(Commands.literal("info").then(Commands.argument("army", StringArgumentType.word())
                .executes(context -> army(context.getSource(), StringArgumentType.getString(context, "army")))))
            .then(Commands.literal("raise").then(Commands.argument("war", StringArgumentType.word())
                .executes(context -> raise(context.getSource(), StringArgumentType.getString(context, "war")))))
            .then(Commands.literal("materialize").then(Commands.argument("army", StringArgumentType.word())
                .executes(context -> materialize(context.getSource(), StringArgumentType.getString(context, "army")))))
            .then(Commands.literal("dematerialize").then(Commands.argument("army", StringArgumentType.word())
                .executes(context -> dematerialize(context.getSource(), StringArgumentType.getString(context, "army")))));
    }

    // ------------------------------------------------------------------------------------------------ wars

    private static int list(final CommandSourceStack source, final boolean all)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<WarRecord> wars = data.war().wars().stream().filter(war -> all || war.open())
            .sorted(Comparator.comparingLong(WarRecord::declaredAt)).toList();
        source.sendSuccess(() -> Component.literal((all ? "Wars: " : "Open wars: ") + wars.size() + " (last evaluation at "
            + data.war().lastEvaluatedAt() + ")").withStyle(ChatFormatting.GOLD), false);
        for (final WarRecord war : wars)
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "- %s: %s vs %s, %s%s, score %d, battles %d/%d",
                war.id(), faction(data, war.attacker()), faction(data, war.defender()), war.status(),
                war.result() == null ? "" : " (" + war.result() + ")", war.score(), war.battlesWon(), war.battlesLost())), false);
        return wars.size();
    }

    private static int info(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WarRecord war = war(source, data, text);
        if (war == null) return 0;
        final long gameTime = source.getServer().overworld().getGameTime();
        final List<String> lines = List.of(
            "War " + war.id() + " (#" + war.ordinal() + " of the pair), cause " + war.cause() + " (evidence " + war.evidence() + ")",
            "  " + faction(data, war.attacker()) + " [" + war.attacker() + "] attacks " + faction(data, war.defender()) + " [" + war.defender() + "]",
            "  status " + war.status() + (war.status() == WarRecord.Status.DECLARED ? ", active at " + war.activeAt() + " (in "
                + Math.max(0L, war.activeAt() - gameTime) + " ticks)" : "") + (war.result() == null ? "" : ", result " + war.result()),
            "  score " + war.score() + " (attacker won " + war.battlesWon() + ", lost " + war.battlesLost() + "), armies raised " + war.armiesRaised(),
            "  soldiers at home: " + faction(data, war.attacker()) + " " + home(data, war.attacker()) + ", " + faction(data, war.defender()) + " "
                + home(data, war.defender()),
            "  peace: indemnity " + war.tribute() + (war.tributePaid() ? " paid" : "") + (war.peaceApplied() ? ", relation and truce applied" : ""),
            "  truce until " + data.war().state().truceUntil(war.attacker(), war.defender()));
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        data.war().armies().stream().filter(army -> army.warId().equals(war.id())).sorted(CampaignService.byRaisedAt())
            .forEach(army -> source.sendSuccess(() -> Component.literal("  army " + army.id() + ": " + army.status() + ", " + army.strength() + "/"
                + army.detached() + " soldiers"), false));
        return 1;
    }

    private static int declare(final CommandSourceStack source, final String attackerText, final String defenderText, final boolean force)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final Faction attacker = faction(source, data, attackerText);
        final Faction defender = attacker == null ? null : faction(source, data, defenderText);
        if (defender == null) return 0;
        final WarService.Declaration declaration = WarService.declare(data, attacker.id(), defender.id(), WarRecord.Cause.ADMIN, 0L,
            source.getServer().overworld().getGameTime(), WarManager.settingsFromConfig(), force);
        if (declaration.war().isEmpty())
        {
            source.sendFailure(Component.literal("Not declared: " + declaration.refusal().message()));
            return 0;
        }
        final WarRecord war = declaration.war().get();
        WarManager.getInstance().announceDeclaration(data, war);
        source.sendSuccess(() -> Component.literal("War " + war.id() + " declared; it becomes active at " + war.activeAt()
            + " (/kingdoms war mobilize " + war.id() + " to start now)"), true);
        return 1;
    }

    private static int mobilize(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WarRecord war = war(source, data, text);
        if (war == null) return 0;
        if (!WarService.mobilizeNow(data, war))
        {
            source.sendFailure(Component.literal("War " + war.id() + " is " + war.status() + ", not DECLARED"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("War " + war.id() + " is ACTIVE; its first army is raised at the next campaign update"), true);
        return 1;
    }

    private static int ceasefire(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WarRecord war = war(source, data, text);
        if (war == null) return 0;
        if (WarService.ceasefire(data, war, source.getServer().overworld().getGameTime()).isEmpty())
        {
            source.sendFailure(Component.literal("War " + war.id() + " is " + war.status()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Ceasefire in war " + war.id() + ": armies go home; peace follows after the ceasefire period"), true);
        return 1;
    }

    private static int peace(final CommandSourceStack source, final String text, final WarRecord.Result result)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WarRecord war = war(source, data, text);
        if (war == null) return 0;
        if (WarService.end(data, war, result, source.getServer().overworld().getGameTime(), WarManager.settingsFromConfig()).isEmpty())
        {
            source.sendFailure(Component.literal("War " + war.id() + " has already ended"));
            return 0;
        }
        WarManager.getInstance().announcePeace(data, war);
        source.sendSuccess(() -> Component.literal("War " + war.id() + " ended: " + result + "; armies go home"), true);
        return 1;
    }

    private static int evaluate(final CommandSourceStack source)
    {
        final WarService.Evaluation evaluation = WarManager.getInstance().evaluateNow(source.getServer());
        if (evaluation == null)
        {
            source.sendFailure(Component.literal("War evaluation failed; see the server log"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Wars evaluated: declared " + evaluation.declared().size() + ", activated "
            + evaluation.activated().size() + ", ended " + evaluation.ended().size()), true);
        return 1;
    }

    private static int stats(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var wars = WarManager.getInstance().stats();
        final var soldiers = ArmyManager.getInstance().stats();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Wars: open %d, armies in the field %d, open battles %d | evaluations=%d updates=%d avg=%.3fms max=%.3fms failures=%d",
            data.war().wars().stream().filter(WarRecord::open).count(), data.war().armies().stream().filter(ArmyRecord::open).count(),
            data.war().battles().stream().filter(BattleRecord::open).count(), wars.evaluations(), wars.updates(), wars.averageNanos() / 1_000_000.0D,
            wars.maximumNanos() / 1_000_000.0D, wars.failures())).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Soldiers: in world=%d squads=%d | materialized=%d dematerialized=%d deaths=%d stuck=%d failed spawns=%d | cycle avg=%.3fms max=%.3fms (%d cycles)",
            ArmyManager.getInstance().physicalSoldiers(), ArmyManager.getInstance().activeSquads(), soldiers.materialized(), soldiers.dematerialized(),
            soldiers.deaths(), soldiers.stuckRecoveries(), soldiers.failedSpawns(), soldiers.averageNanos() / 1_000_000.0D,
            soldiers.maximumNanos() / 1_000_000.0D, soldiers.cycles())), false);
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ battles

    private static int battles(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<BattleRecord> battles = data.war().battles().stream().sorted(Comparator.comparingLong(BattleRecord::startedAt)).toList();
        source.sendSuccess(() -> Component.literal("Battles: " + battles.size()).withStyle(ChatFormatting.GOLD), false);
        for (final BattleRecord battle : battles)
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "- %s: siege of %s, %s%s, %d vs %d",
                battle.id(), settlement(data, battle.settlementId()), battle.status(), battle.outcome() == null ? "" : " (" + battle.outcome() + ")",
                battle.attackerStrength(), battle.defenderStrength())), false);
        return battles.size();
    }

    private static int battle(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final BattleRecord battle = battle(source, data, text);
        if (battle == null) return 0;
        final long gameTime = source.getServer().overworld().getGameTime();
        final List<String> lines = List.of(
            "Battle " + battle.id() + ": siege of " + settlement(data, battle.settlementId()) + " at " + battle.position().toShortString(),
            "  " + faction(data, battle.attacker()) + " " + battle.attackerStrength() + " soldiers vs " + faction(data, battle.defender()) + " "
                + battle.defenderStrength() + " defenders (fortification " + battle.fortification() + ")",
            "  status " + battle.status() + (battle.open() ? ", decided at " + battle.resolveAt() + " (in " + Math.max(0L, battle.resolveAt() - gameTime)
                + " ticks)" : ", " + battle.outcome() + " at " + battle.resolvedAt()),
            "  fallen in the physical fight: attackers " + battle.attackerPhysicalLosses() + ", defenders " + battle.defenderPhysicalLosses(),
            "  losses applied: attackers " + battle.attackerLosses() + ", defenders " + battle.defenderLosses() + ", tribute " + battle.tributeTaken(),
            "  player defenders " + battle.defenders().size() + ", players who killed soldiers " + battle.attackersOfSoldiers().size());
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int resolve(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final BattleRecord battle = battle(source, data, text);
        if (battle == null) return 0;
        if (!battle.open())
        {
            source.sendFailure(Component.literal("Battle " + battle.id() + " is already " + battle.status()));
            return 0;
        }
        WarManager.getInstance().resolveNow(source.getServer(), battle);
        source.sendSuccess(() -> Component.literal("Battle " + battle.id() + ": " + battle.status() + " / " + battle.outcome()), true);
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ armies

    private static int armies(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final long gameTime = source.getServer().overworld().getGameTime();
        final List<ArmyRecord> armies = data.war().armies().stream().sorted(CampaignService.byRaisedAt()).toList();
        source.sendSuccess(() -> Component.literal("Armies: " + armies.size() + ", in the world " + ArmyManager.getInstance().physicalSoldiers()
            + " soldiers").withStyle(ChatFormatting.GOLD), false);
        for (final ArmyRecord army : armies)
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "- %s: %s from %s to %s, %s, %d/%d soldiers, progress %.2f%s",
                army.id(), faction(data, army.factionId()), settlement(data, army.originSettlementId()), settlement(data, army.targetSettlementId()),
                army.status(), army.strength(), army.detached(), army.progressAt(gameTime),
                ArmyManager.getInstance().soldiersOf(army.id()) > 0 ? ", " + ArmyManager.getInstance().soldiersOf(army.id()) + " in the world" : "")), false);
        return armies.size();
    }

    private static int army(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final ArmyRecord army = army(source, data, text);
        if (army == null) return 0;
        final long gameTime = source.getServer().overworld().getGameTime();
        final String where = position(data, army, gameTime).map(value -> String.format(Locale.ROOT, "%.0f %.0f %.0f", value.x, value.y, value.z))
            .orElse("?");
        final List<String> lines = List.of(
            "Army " + army.id() + " of " + faction(data, army.factionId()) + " (war " + army.warId() + ")",
            "  " + settlement(data, army.originSettlementId()) + " -> " + settlement(data, army.targetSettlementId()) + " over " + army.routeRoads().size()
                + " road(s), full trip " + army.fullTravelTicks() + " ticks",
            "  status " + army.status() + ", progress " + String.format(Locale.ROOT, "%.3f", army.progressAt(gameTime)) + ", now near " + where,
            "  soldiers " + army.strength() + " of " + army.detached() + " (skirmish losses " + army.skirmishLosses() + ")"
                + (army.open() ? "" : ", " + army.returned() + " returned home"),
            "  battle " + (army.battleId() == null ? "none" : army.battleId()) + "; in the world " + ArmyManager.getInstance().soldiersOf(army.id()));
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int raise(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final WarRecord war = war(source, data, text);
        if (war == null) return 0;
        if (data.war().armies().stream().anyMatch(army -> army.open() && army.warId().equals(war.id())))
        {
            source.sendFailure(Component.literal("War " + war.id() + " already has an army in the field"));
            return 0;
        }
        final Optional<ArmyRecord> army = CampaignService.raiseArmy(data, war, source.getServer().overworld().getGameTime(),
            WarManager.settingsFromConfig());
        if (army.isEmpty())
        {
            source.sendFailure(Component.literal("No army: the war must be ACTIVE and the attacker needs a settlement with at least "
                + WarManager.settingsFromConfig().minimumArmy() + " soldiers it can send (60% of those at home) and a road to the enemy"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Army " + army.get().id() + " raised: " + army.get().strength() + " soldiers, "
            + army.get().fullTravelTicks() + " ticks to the target"), true);
        return 1;
    }

    private static int materialize(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final ArmyRecord army = army(source, data, text);
        if (army == null) return 0;
        final int count = ArmyManager.getInstance().materializeNow(source.getServer(), army.id());
        source.sendSuccess(() -> Component.literal(count + " soldier(s) of army " + army.id() + " in the world (held for "
            + ArmyManager.MANUAL_HOLD_TICKS + " ticks; needs an entity-ticking chunk where the army is)"), true);
        return count;
    }

    private static int dematerialize(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final ArmyRecord army = army(source, data, text);
        if (army == null) return 0;
        final int count = ArmyManager.getInstance().dematerializeNow(source.getServer(), army.id());
        source.sendSuccess(() -> Component.literal("Removed " + count + " soldier(s); the army is unchanged"), true);
        return count;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Where an army is now (its road position, or the besieged gate). */
    static Optional<Vec3> position(final KingdomsSavedData data, final ArmyRecord army, final long gameTime)
    {
        if (army.status() == ArmyRecord.Status.BESIEGING)
            return data.settlements().get(army.targetSettlementId()).map(settlement -> Vec3.atBottomCenterOf(settlement.gate()));
        try
        {
            return Optional.of(new RoadShipmentPath(new RoadRoute(army.originSettlementId(), army.targetSettlementId(), army.routeSettlements(),
                army.routeRoads(), 0.0D), data.roads()).positionAt(army.progressAt(gameTime)));
        }
        catch (RuntimeException exception)
        {
            return Optional.empty();
        }
    }

    private static int home(final KingdomsSavedData data, final UUID factionId)
    {
        return data.faction(factionId).map(faction -> WarService.homeStrength(data, faction)).orElse(0);
    }

    private static String faction(final KingdomsSavedData data, final UUID factionId)
    {
        return data.faction(factionId).map(Faction::name).orElse("?");
    }

    private static String settlement(final KingdomsSavedData data, final UUID settlementId)
    {
        return data.settlements().get(settlementId).map(SettlementRecord::name).orElse("?");
    }

    /** A faction by its UUID, or the faction of a settlement given by its UUID. */
    private static Faction faction(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = BanditCommands.uuid(source, text);
        if (id == null) return null;
        final Optional<Faction> faction = data.faction(id).or(() -> data.colony(id).flatMap(colony -> data.faction(colony.factionId())));
        if (faction.isEmpty()) source.sendFailure(Component.literal("No faction or settlement " + id));
        return faction.orElse(null);
    }

    private static WarRecord war(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = BanditCommands.uuid(source, text);
        if (id == null) return null;
        final WarRecord war = data.war().war(id).orElse(null);
        if (war == null) source.sendFailure(Component.literal("Unknown war " + id));
        return war;
    }

    private static ArmyRecord army(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = BanditCommands.uuid(source, text);
        if (id == null) return null;
        final ArmyRecord army = data.war().army(id).orElse(null);
        if (army == null) source.sendFailure(Component.literal("Unknown army " + id));
        return army;
    }

    private static BattleRecord battle(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = BanditCommands.uuid(source, text);
        if (id == null) return null;
        final BattleRecord battle = data.war().battle(id).orElse(null);
        if (battle == null) source.sendFailure(Component.literal("Unknown battle " + id));
        return battle;
    }
}
