package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.bandit.BanditCamp;
import com.minecolonies.kingdoms.bandit.BanditEncounter;
import com.minecolonies.kingdoms.bandit.BanditManager;
import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator diagnostics and controls for bandit camps (Phase 8.1). A camp's fight is an ordinary bandit encounter, so
 * {@code /kingdoms bandit materialize|dematerialize|resolve <encounter>} work on it too.
 */
final class CampCommands
{
    private CampCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> campCommand()
    {
        return Commands.literal("camp")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("list").executes(context -> list(context.getSource(), false))
                .then(Commands.literal("all").executes(context -> list(context.getSource(), true))))
            .then(Commands.literal("info").then(Commands.argument("camp", StringArgumentType.word())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "camp")))))
            .then(Commands.literal("spawn-test").then(Commands.argument("road", StringArgumentType.word())
                .executes(context -> spawnTest(context.getSource(), StringArgumentType.getString(context, "road")))))
            .then(Commands.literal("build").then(Commands.argument("camp", StringArgumentType.word())
                .executes(context -> build(context.getSource(), StringArgumentType.getString(context, "camp")))))
            .then(Commands.literal("remove").then(Commands.argument("camp", StringArgumentType.word())
                .executes(context -> remove(context.getSource(), StringArgumentType.getString(context, "camp")))))
            .then(Commands.literal("disband").then(Commands.argument("camp", StringArgumentType.word())
                .executes(context -> disband(context.getSource(), StringArgumentType.getString(context, "camp")))));
    }

    private static int list(final CommandSourceStack source, final boolean all)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<BanditCamp> camps = data.bandits().camps().stream().filter(value -> all || value.active())
            .sorted(Comparator.comparingLong(BanditCamp::createdAt).reversed()).toList();
        source.sendSuccess(() -> Component.literal("Bandit camps: " + camps.size() + (all ? " (including ended)" : " active"))
            .withStyle(ChatFormatting.GOLD), false);
        for (final BanditCamp camp : camps)
        {
            final String fight = data.bandits().encounter(camp.encounterId())
                .map(value -> value.status() + "/" + value.representation() + " " + value.remainingStrength() + "/" + value.strength())
                .orElse("<no encounter>");
            source.sendSuccess(() -> Component.literal("- " + camp.id() + " " + camp.status() + " " + BanditCommands.roadName(data, camp.roadId())
                + " @ " + camp.position().toShortString() + " bandits " + fight + " structure " + camp.structure()), false);
        }
        return camps.size();
    }

    private static int info(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final BanditCamp camp = camp(source, data, text);
        if (camp == null) return 0;
        final long gameTime = source.getServer().overworld().getGameTime();
        final List<String> lines = new ArrayList<>();
        lines.add("Camp " + camp.id() + " (#" + camp.ordinal() + " on its road): " + camp.status()
            + (camp.endCause() == null ? "" : " by " + camp.endCause() + " at " + camp.endedAt()));
        lines.add("road " + BanditCommands.roadName(data, camp.roadId()) + " [" + camp.roadId() + "] in " + camp.dimension());
        lines.add("site " + (camp.siteIndex() + 1) + "/" + camp.sites().size() + " at " + camp.position().toShortString() + "; candidates "
            + camp.sites().stream().map(value -> value.toShortString()).toList());
        lines.add("strength " + camp.strength() + ", created " + camp.createdAt() + ", breaks up at " + camp.expiresAt() + ", last recruit "
            + camp.lastRecruitAt() + " (now " + gameTime + ")");
        final Optional<BanditEncounter> encounter = data.bandits().encounter(camp.encounterId());
        lines.add("fight (encounter " + camp.encounterId() + "): " + encounter.map(value -> value.status() + "/" + value.representation()
            + ", bandits " + value.remainingStrength() + "/" + value.strength() + ", defenders " + value.defenders().size()).orElse("<pruned>"));
        lines.add("structure " + camp.structure() + (camp.structureNote().isEmpty() ? "" : " (" + camp.structureNote() + ")") + ", "
            + camp.placed().size() + " placed block(s), changed at " + camp.structureChangedAt());
        final List<Contract> contracts = data.contracts().contracts().stream()
            .filter(value -> camp.encounterId().equals(value.objective().targetEncounter())).toList();
        lines.add("contracts: " + (contracts.isEmpty() ? "none" : contracts.stream().map(value -> value.id() + " " + value.kind() + " "
            + value.status() + (value.holder() == null ? "" : " holder=" + BanditCommands.playerName(source, value.holder()))).toList()));
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int spawnTest(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final UUID id = BanditCommands.uuid(source, text);
        if (id == null) return 0;
        final RoadRecord road = data.roads().get(id).orElse(null);
        if (road == null)
        {
            source.sendFailure(Component.literal("Unknown road " + id));
            return 0;
        }
        final Optional<BanditCamp> camp = BanditManager.getInstance().establishCampNow(source.getServer(), road);
        if (camp.isEmpty())
        {
            source.sendFailure(Component.literal("No camp: the road already has one, or it has no remote plain stretch outside settlement zones"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Established camp " + camp.get().id() + " at " + camp.get().position().toShortString()
            + " with " + camp.get().strength() + " bandits (encounter " + camp.get().encounterId() + ")"), true);
        return 1;
    }

    private static int build(final CommandSourceStack source, final String text)
    {
        final BanditCamp camp = camp(source, KingdomsSavedData.get(source.getLevel()), text);
        if (camp == null) return 0;
        if (!camp.active())
        {
            source.sendFailure(Component.literal("Camp " + camp.id() + " is " + camp.status()));
            return 0;
        }
        final Optional<String> result = BanditManager.getInstance().buildCampNow(source.getServer(), camp);
        source.sendSuccess(() -> Component.literal(result.orElse("Not built now: its chunks are not loaded, it is already built, or its bandits are physical")), true);
        return camp.structure() == BanditCamp.Structure.BUILT ? 1 : 0;
    }

    private static int remove(final CommandSourceStack source, final String text)
    {
        final BanditCamp camp = camp(source, KingdomsSavedData.get(source.getLevel()), text);
        if (camp == null) return 0;
        final boolean removed = BanditManager.getInstance().removeCampNow(source.getServer(), camp);
        source.sendSuccess(() -> Component.literal(removed ? "Took down the blocks of camp " + camp.id() + " (the camp itself is unchanged)"
            : "Nothing taken down: not built, or its chunks are not loaded"), true);
        return removed ? 1 : 0;
    }

    private static int disband(final CommandSourceStack source, final String text)
    {
        final BanditCamp camp = camp(source, KingdomsSavedData.get(source.getLevel()), text);
        if (camp == null) return 0;
        if (!camp.active())
        {
            source.sendFailure(Component.literal("Camp " + camp.id() + " is already " + camp.status()));
            return 0;
        }
        final boolean ended = BanditManager.getInstance().disbandCampNow(source.getServer(), camp);
        source.sendSuccess(() -> Component.literal(ended ? "Camp " + camp.id() + " disbanded (its fight cancelled; its blocks go once nobody is near)"
            : "Camp " + camp.id() + " could not be disbanded"), true);
        return ended ? 1 : 0;
    }

    private static BanditCamp camp(final CommandSourceStack source, final KingdomsSavedData data, final String text)
    {
        final UUID id = BanditCommands.uuid(source, text);
        if (id == null) return null;
        final BanditCamp camp = data.bandits().camp(id).or(() -> data.bandits().campByEncounter(id)).orElse(null);
        if (camp == null) source.sendFailure(Component.literal("Unknown camp " + id));
        return camp;
    }
}
