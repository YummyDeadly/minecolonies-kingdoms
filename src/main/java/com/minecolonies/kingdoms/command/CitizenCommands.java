package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.citizen.CitizenRole;
import com.minecolonies.kingdoms.citizen.SettlementCitizenManager;
import com.minecolonies.kingdoms.citizen.SettlementRepresentative;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Operator diagnostics for physical settlement representatives. */
final class CitizenCommands
{
    private CitizenCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> citizenCommand()
    {
        return Commands.literal("citizen")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("stats").executes(context -> stats(context.getSource())))
            .then(Commands.literal("list").then(Commands.argument("settlement", StringArgumentType.word())
                .executes(context -> list(context.getSource(), StringArgumentType.getString(context, "settlement")))))
            .then(Commands.literal("info").then(Commands.argument("citizen", StringArgumentType.word())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "citizen")))))
            .then(Commands.literal("materialize").then(Commands.argument("settlement", StringArgumentType.word())
                .executes(context -> materialize(context.getSource(), StringArgumentType.getString(context, "settlement")))))
            .then(Commands.literal("dematerialize").then(Commands.argument("settlement", StringArgumentType.word())
                .executes(context -> dematerialize(context.getSource(), StringArgumentType.getString(context, "settlement")))));
    }

    private static int stats(final CommandSourceStack source)
    {
        final var manager = SettlementCitizenManager.getInstance();
        final var stats = manager.stats();
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Citizens: physical=%d activeSettlements=%d rosters=%d representatives=%d",
            manager.physical(null).size(), manager.activeSettlements(), data.citizens().settlements().size(),
            data.citizens().representativeCount())).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "materialized=%d dematerialized=%d failedSpawns=%d deaths=%d stuckRecoveries=%d cycles=%d avg=%.3fms max=%.3fms",
            stats.materialized(), stats.dematerialized(), stats.failedSpawns(), stats.deaths(), stats.stuckRecoveries(),
            stats.cycles(), stats.averageNanos() / 1_000_000.0D, stats.maximumNanos() / 1_000_000.0D)), false);
        return 1;
    }

    private static int list(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final SettlementRecord settlement = settlement(source, data, text);
        if (settlement == null) return 0;
        final var roster = data.citizens().roster(settlement.id());
        final var physical = SettlementCitizenManager.getInstance().physical(settlement.id());
        final int population = data.colony(settlement.id()).map(colony -> colony.population()).orElse(0);
        final Map<CitizenRole, Integer> roles = new EnumMap<>(CitizenRole.class);
        roster.forEach(value -> roles.merge(value.role(), 1, Integer::sum));
        source.sendSuccess(() -> Component.literal(settlement.name() + ": logical population=" + population
            + " roster=" + roster.size() + " physical=" + physical.size() + " failedSpawns="
            + SettlementCitizenManager.getInstance().failedSpawns(settlement.id())).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("roles=" + roles), false);
        for (final SettlementRepresentative value : roster)
        {
            final var physicalState = SettlementCitizenManager.getInstance().physicalOf(value.id()).orElse(null);
            source.sendSuccess(() -> Component.literal("- " + value.name() + " " + value.id() + " " + value.role()
                + " home=" + building(data, value.homeBuildingId()) + " work=" + building(data, value.workBuildingId())
                + (physicalState == null ? " [abstract]" : " [physical " + physicalState.activity().description()
                    + " waypoint " + physicalState.waypointIndex() + "/" + physicalState.waypoints() + "]")), false);
        }
        return roster.size();
    }

    private static int info(final CommandSourceStack source, final String text)
    {
        final UUID id = uuid(source, text);
        if (id == null) return 0;
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final SettlementRepresentative value = data.citizens().find(id).orElse(null);
        if (value == null) { source.sendFailure(Component.literal("Unknown representative " + id)); return 0; }
        final String settlementName = data.settlements().get(value.settlementId()).map(SettlementRecord::name).orElse("?");
        source.sendSuccess(() -> Component.literal(value.name() + " - " + value.role().displayName() + " of " + settlementName)
            .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("id=" + value.id() + " settlement=" + value.settlementId()
            + " female=" + value.female() + " seed=" + value.cosmeticSeed()), false);
        source.sendSuccess(() -> Component.literal("home=" + building(data, value.homeBuildingId())
            + " work=" + building(data, value.workBuildingId())), false);
        final var physical = SettlementCitizenManager.getInstance().physicalOf(id).orElse(null);
        if (physical == null)
        {
            source.sendSuccess(() -> Component.literal("representation=ABSTRACT"), false);
            return 1;
        }
        Entity entity = null;
        for (final var level : source.getServer().getAllLevels())
        {
            entity = level.getEntity(physical.entityId());
            if (entity != null) break;
        }
        final Entity found = entity;
        source.sendSuccess(() -> Component.literal("representation=PHYSICAL entity=" + physical.entityId()
            + " activity=" + physical.activity() + " waypoint=" + physical.waypointIndex() + "/" + physical.waypoints()
            + " observer=" + (physical.observer() == null ? "manual" : physical.observer())
            + (found == null ? "" : " @ " + found.blockPosition().toShortString())), false);
        return 1;
    }

    private static int materialize(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final SettlementRecord settlement = settlement(source, data, text);
        if (settlement == null) return 0;
        final int count = SettlementCitizenManager.getInstance().materializeNow(source.getServer(), settlement.id());
        source.sendSuccess(() -> Component.literal("Held " + settlement.name() + " active for "
            + SettlementCitizenManager.MANUAL_HOLD_TICKS + " ticks; physical=" + count
            + " (representatives keep appearing gradually)"), true);
        return 1;
    }

    private static int dematerialize(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final SettlementRecord settlement = settlement(source, data, text);
        if (settlement == null) return 0;
        final int count = SettlementCitizenManager.getInstance().dematerializeNow(source.getServer(), settlement.id());
        source.sendSuccess(() -> Component.literal("Dematerialized " + count + " representatives of " + settlement.name()
            + "; suppressed for " + SettlementCitizenManager.MANUAL_HOLD_TICKS + " ticks. Logical population unchanged."), true);
        return 1;
    }

    private static String building(final KingdomsSavedData data, final UUID id)
    {
        if (id == null) return "none";
        return data.growth().building(id).map(value -> value.type() + "@" + value.anchor().toShortString()).orElse("missing");
    }

    private static SettlementRecord settlement(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = uuid(source, text);
        if (id == null) return null;
        final SettlementRecord settlement = data.settlements().get(id).orElse(null);
        if (settlement == null) source.sendFailure(Component.literal("Unknown settlement " + id));
        return settlement;
    }

    private static UUID uuid(final CommandSourceStack source, final String text)
    {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException exception)
        {
            source.sendFailure(Component.literal("Invalid UUID: " + text));
            return null;
        }
    }
}
