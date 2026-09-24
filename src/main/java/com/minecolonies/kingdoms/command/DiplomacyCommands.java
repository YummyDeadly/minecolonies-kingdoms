package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.diplomacy.DiplomacyEvaluator;
import com.minecolonies.kingdoms.diplomacy.DiplomacyManager;
import com.minecolonies.kingdoms.diplomacy.DiplomacyRules;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.diplomacy.DiplomaticStance;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Operator diagnostics and controls for relations between NPC factions. */
final class DiplomacyCommands
{
    private DiplomacyCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> diplomacyCommand()
    {
        return Commands.literal("diplomacy")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("list").executes(context -> list(context.getSource())))
            .then(Commands.literal("info").then(Commands.argument("faction", StringArgumentType.word())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "faction")))))
            .then(Commands.literal("events").executes(context -> events(context.getSource())))
            .then(Commands.literal("evaluate").executes(context -> evaluate(context.getSource())))
            .then(Commands.literal("set").then(Commands.argument("first", StringArgumentType.word())
                .then(Commands.argument("second", StringArgumentType.word())
                    .then(Commands.argument("value", IntegerArgumentType.integer(DiplomacyRules.MINIMUM, DiplomacyRules.MAXIMUM))
                        .executes(context -> set(context.getSource(), StringArgumentType.getString(context, "first"),
                            StringArgumentType.getString(context, "second"), IntegerArgumentType.getInteger(context, "value")))))));
    }

    private static int list(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final Set<DiplomacyEvaluator.Pair> pairs = DiplomacyEvaluator.neighbourPairs(data);
        final long last = data.diplomacy().lastEvaluatedAt();
        source.sendSuccess(() -> Component.literal("Diplomatic neighbours: " + pairs.size() + " pair(s); last evaluation "
            + (last < 0L ? "never" : "at " + last) + "; evaluations this session=" + DiplomacyManager.getInstance().evaluations()
            + String.format(Locale.ROOT, " (%.2f ms)", DiplomacyManager.getInstance().lastMillis())).withStyle(ChatFormatting.GOLD), false);
        for (final DiplomacyEvaluator.Pair pair : pairs)
        {
            final Faction first = data.faction(pair.first()).orElse(null);
            final Faction second = data.faction(pair.second()).orElse(null);
            if (first == null || second == null) continue;
            final int value = DiplomacyEvaluator.relation(first, second);
            source.sendSuccess(() -> line(first, second, value, DiplomacyRules.baseline(first.id(), second.id())), false);
        }
        return pairs.size();
    }

    private static int info(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final Faction faction = faction(source, data, text);
        if (faction == null) return 0;
        source.sendSuccess(() -> Component.literal(faction.name() + " [" + faction.type() + "] treasury=" + faction.treasury()
            + " settlements=" + faction.settlementIds().size() + " relations=" + faction.relations().size())
            .withStyle(ChatFormatting.GOLD), false);
        faction.relations().forEach((otherId, value) -> {
            final Faction other = data.faction(otherId).orElse(null);
            if (other != null) source.sendSuccess(() -> line(faction, other, value, DiplomacyRules.baseline(faction.id(), otherId)), false);
        });
        return 1;
    }

    private static int events(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var events = data.diplomacy().events().stream().limit(30).toList();
        source.sendSuccess(() -> Component.literal("Relation changes (newest first, " + data.diplomacy().events().size()
            + " kept): showing " + events.size()).withStyle(ChatFormatting.GOLD), false);
        for (final DiplomacyState.Event event : events)
            source.sendSuccess(() -> Component.literal("t=" + event.gameTime() + " " + name(data, event.first()) + " / "
                + name(data, event.second()) + ": " + event.before() + " -> " + event.after() + " (" + event.stanceAfter().displayName()
                + ") " + event.cause() + (event.evidence() == 0L ? "" : " evidence=" + event.evidence())), false);
        return events.size();
    }

    private static int evaluate(final CommandSourceStack source)
    {
        final DiplomacyEvaluator.Report report = DiplomacyManager.getInstance().evaluateNow(source.getServer());
        source.sendSuccess(() -> Component.literal("Evaluated " + report.pairs() + " pair(s): events=" + report.events().size()
            + " stance changes=" + report.stanceChanges() + String.format(Locale.ROOT, " in %.2f ms",
            DiplomacyManager.getInstance().lastMillis())), true);
        return report.pairs();
    }

    private static int set(final CommandSourceStack source, final String firstText, final String secondText, final int value)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final Faction first = faction(source, data, firstText);
        final Faction second = first == null ? null : faction(source, data, secondText);
        if (first == null || second == null) return 0;
        if (first.id().equals(second.id()))
        {
            source.sendFailure(Component.literal("A faction has no relation with itself"));
            return 0;
        }
        com.minecolonies.kingdoms.diplomacy.DiplomacyService.set(data, first, second, value, DiplomacyState.Cause.ADMIN_SET, 0L,
            source.getServer().overworld().getGameTime());
        source.sendSuccess(() -> line(first, second, value, DiplomacyRules.baseline(first.id(), second.id())), true);
        return 1;
    }

    private static Component line(final Faction first, final Faction second, final int value, final int baseline)
    {
        final DiplomaticStance stance = DiplomaticStance.of(value);
        final ChatFormatting color = switch (stance)
        {
            case HOSTILE -> ChatFormatting.DARK_RED;
            case TENSE -> ChatFormatting.RED;
            case NEUTRAL -> ChatFormatting.GRAY;
            case FRIENDLY -> ChatFormatting.GREEN;
            case ALLIED -> ChatFormatting.AQUA;
        };
        return Component.literal("- " + first.name() + " / " + second.name() + ": " + value + " ")
            .append(Component.literal(stance.displayName()).withStyle(color))
            .append(Component.literal(" (disposition " + baseline + (stance.allowsTrade() ? "" : ", no trade") + ")").withStyle(ChatFormatting.GRAY));
    }

    private static String name(final KingdomsSavedData data, final UUID id)
    {
        return data.faction(id).map(Faction::name).orElse(id.toString());
    }

    private static Faction faction(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id;
        try { id = UUID.fromString(text); }
        catch (IllegalArgumentException exception)
        {
            source.sendFailure(Component.literal("Invalid UUID: " + text));
            return null;
        }
        final Faction faction = data.faction(id).orElse(null);
        if (faction == null) source.sendFailure(Component.literal("Unknown faction " + id));
        return faction;
    }
}
