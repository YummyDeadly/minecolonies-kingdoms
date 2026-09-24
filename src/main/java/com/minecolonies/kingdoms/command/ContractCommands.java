package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractManager;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.contract.ContractText;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.diplomacy.ReputationTier;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Player contract/reputation commands (no permission needed; the clickable chat buttons run them) and operator
 * diagnostics. Player commands act only on the executing player and require being in the settlement.
 */
final class ContractCommands
{
    private ContractCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> contractCommand()
    {
        return Commands.literal("contract")
            .then(Commands.literal("list").executes(context -> list(context.getSource())))
            .then(Commands.literal("offers").executes(context -> offers(context.getSource())))
            .then(Commands.literal("accept").then(Commands.argument("contract", StringArgumentType.word())
                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "contract"), Action.ACCEPT))))
            .then(Commands.literal("deliver").then(Commands.argument("contract", StringArgumentType.word())
                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "contract"), Action.DELIVER))))
            .then(Commands.literal("abandon").then(Commands.argument("contract", StringArgumentType.word())
                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "contract"), Action.ABANDON))))
            .then(Commands.literal("all").requires(source -> source.hasPermission(2))
                .executes(context -> all(context.getSource(), null))
                .then(Commands.argument("settlement", StringArgumentType.word())
                    .executes(context -> all(context.getSource(), StringArgumentType.getString(context, "settlement")))))
            .then(Commands.literal("refresh").requires(source -> source.hasPermission(2))
                .then(Commands.argument("settlement", StringArgumentType.word())
                    .executes(context -> refresh(context.getSource(), StringArgumentType.getString(context, "settlement")))))
            .then(Commands.literal("cancel").requires(source -> source.hasPermission(2))
                .then(Commands.argument("contract", StringArgumentType.word())
                    .executes(context -> cancel(context.getSource(), StringArgumentType.getString(context, "contract")))))
            .then(Commands.literal("stats").requires(source -> source.hasPermission(2)).executes(context -> stats(context.getSource())));
    }

    static ArgumentBuilder<CommandSourceStack, ?> reputationCommand()
    {
        return Commands.literal("reputation")
            .executes(context -> reputation(context.getSource(), context.getSource().getPlayerOrException()))
            .then(Commands.literal("of").requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> reputation(context.getSource(), EntityArgument.getPlayer(context, "player")))))
            .then(Commands.literal("history").requires(source -> source.hasPermission(2))
                .executes(context -> history(context.getSource(), null))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> history(context.getSource(), EntityArgument.getPlayer(context, "player")))))
            .then(Commands.literal("set").requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("faction", StringArgumentType.word())
                        .then(Commands.argument("value", IntegerArgumentType.integer(-100, 100))
                            .executes(context -> setReputation(context.getSource(), EntityArgument.getPlayer(context, "player"),
                                StringArgumentType.getString(context, "faction"), IntegerArgumentType.getInteger(context, "value")))))));
    }

    private enum Action { ACCEPT, DELIVER, ABANDON }

    private static int act(final CommandSourceStack source, final String text, final Action action) throws CommandSyntaxException
    {
        final ServerPlayer player = source.getPlayerOrException();
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        ContractManager.getInstance().claimPending(player).ifPresent(player::sendSystemMessage);
        final Contract contract = data.contracts().resolve(text).orElse(null);
        if (contract == null)
        {
            source.sendFailure(Component.literal("Unknown contract " + text));
            return 0;
        }
        final Component result = switch (action)
        {
            case ACCEPT -> ContractManager.getInstance().accept(player, contract);
            case DELIVER -> ContractManager.getInstance().deliver(player, contract);
            case ABANDON -> ContractManager.getInstance().abandon(player, contract);
        };
        player.sendSystemMessage(result);
        return 1;
    }

    private static int list(final CommandSourceStack source) throws CommandSyntaxException
    {
        final ServerPlayer player = source.getPlayerOrException();
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final long gameTime = ContractManager.gameTime(player);
        ContractManager.getInstance().claimPending(player).ifPresent(player::sendSystemMessage);
        final List<Contract> active = data.contracts().active(player.getUUID());
        if (active.isEmpty())
        {
            player.sendSystemMessage(Component.literal("You have no active contracts. Ask a settlement's official or merchant for work.")
                .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        for (final Contract contract : active)
            player.sendSystemMessage(Component.literal(settlementName(data, contract.settlementId()) + ": "
                + ContractText.progress(contract) + ", " + contract.agreedReward() + " emeralds, "
                + ContractText.duration(contract.deadline() - gameTime) + " left ")
                .append(contract.kind() == Contract.Kind.DELIVERY
                    ? ContractText.button("Deliver", ChatFormatting.GOLD, "/kingdoms contract deliver " + contract.id(),
                        "Hand over what you carry (you must be in the settlement)", true)
                    : Component.empty()));
        return active.size();
    }

    private static int offers(final CommandSourceStack source) throws CommandSyntaxException
    {
        final ServerPlayer player = source.getPlayerOrException();
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final ContractSettings settings = ContractManager.settings();
        final SettlementRecord settlement = ContractManager.nearest(data, player, settings).orElse(null);
        if (settlement == null)
        {
            player.sendSystemMessage(Component.literal("You are not in a settlement.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        final UUID factionId = data.colony(settlement.id()).map(colony -> colony.factionId()).orElse(null);
        final ReputationTier tier = factionId == null ? ReputationTier.NEUTRAL : ReputationService.tier(data, player.getUUID(), factionId);
        if (!tier.contractsAllowed())
        {
            player.sendSystemMessage(Component.literal(settlement.name() + " refuses to deal with you.").withStyle(ChatFormatting.RED));
            return 0;
        }
        final List<Contract> offers = ContractService.refresh(data, settlement.id(), ContractManager.gameTime(player), settings, false);
        player.sendSystemMessage(Component.literal(settlement.name() + " (" + tier.displayName() + "): "
            + offers.size() + " offer(s)").withStyle(ChatFormatting.GOLD));
        for (final Contract contract : offers)
            player.sendSystemMessage(ContractText.button("Accept", ChatFormatting.GREEN, "/kingdoms contract accept " + contract.id(),
                    "Take this job", true)
                .append(Component.literal(" " + ContractText.describe(contract) + ", "
                    + com.minecolonies.kingdoms.contract.ContractRules.agreedReward(contract.objective().baseReward(), tier.rewardMultiplier())
                    + " emeralds, +" + contract.objective().reputationReward() + " reputation")));
        return offers.size();
    }

    private static int reputation(final CommandSourceStack source, final ServerPlayer player)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final Map<UUID, com.minecolonies.kingdoms.diplomacy.ReputationRegistry.Record> records = data.reputation().of(player.getUUID());
        final List<Faction> known = records.keySet().stream().map(id -> data.faction(id).orElse(null))
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingInt((Faction faction) -> -records.get(faction.id()).value()).thenComparing(Faction::name))
            .toList();
        final MutableComponent header = Component.literal("Reputation of " + player.getGameProfile().getName())
            .withStyle(ChatFormatting.GOLD);
        final SettlementRecord here = ContractManager.nearest(data, player, ContractManager.settings()).orElse(null);
        if (here != null)
        {
            final UUID factionId = data.colony(here.id()).map(colony -> colony.factionId()).orElse(null);
            if (factionId != null)
            {
                final ReputationTier tier = ReputationService.tier(data, player.getUUID(), factionId);
                header.append(Component.literal(" (here: " + here.name() + ", ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(tier.displayName()).withStyle(ContractText.color(tier)))
                    .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
            }
        }
        source.sendSuccess(() -> header, false);
        if (known.isEmpty()) source.sendSuccess(() -> Component.literal("Neutral with everyone.").withStyle(ChatFormatting.GRAY), false);
        for (final Faction faction : known)
        {
            final var record = records.get(faction.id());
            final ReputationTier tier = ReputationTier.of(record.value());
            source.sendSuccess(() -> Component.literal("- " + faction.name() + ": " + record.value() + " ")
                .append(Component.literal(tier.displayName()).withStyle(ContractText.color(tier)))
                .append(Component.literal(" (completed " + record.completed() + ", failed " + record.failed() + ", cancelled "
                    + record.cancelled() + ", killed " + record.killed() + ")").withStyle(ChatFormatting.GRAY)), false);
        }
        return known.size();
    }

    private static int setReputation(final CommandSourceStack source, final ServerPlayer player, final String factionText, final int value)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final UUID factionId = uuid(source, factionText);
        if (factionId == null) return 0;
        final Faction faction = data.faction(factionId).orElse(null);
        if (faction == null)
        {
            source.sendFailure(Component.literal("Unknown faction " + factionId));
            return 0;
        }
        ReputationService.set(data, player.getUUID(), factionId, value, source.getServer().overworld().getGameTime());
        source.sendSuccess(() -> Component.literal("Set reputation of " + player.getGameProfile().getName() + " with "
            + faction.name() + " to " + value + " (" + ReputationTier.of(value).displayName() + ")"), true);
        return 1;
    }

    private static int all(final CommandSourceStack source, final String settlementText)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final UUID filter = settlementText == null ? null : uuid(source, settlementText);
        if (settlementText != null && filter == null) return 0;
        final List<Contract> contracts = data.contracts().contracts().stream()
            .filter(value -> filter == null || value.settlementId().equals(filter)).toList();
        final long gameTime = source.getServer().overworld().getGameTime();
        source.sendSuccess(() -> Component.literal("Contracts: " + contracts.size()).withStyle(ChatFormatting.GOLD), false);
        for (final Contract contract : contracts)
            source.sendSuccess(() -> Component.literal(contract.id() + " #" + contract.sequence() + " " + contract.kind() + " " + contract.status()
                + (contract.closeReason() == null ? "" : "/" + contract.closeReason()) + " "
                + settlementName(data, contract.settlementId()) + " "
                + (contract.kind() == Contract.Kind.DELIVERY ? contract.resource() + " " + contract.delivered() + "/" + contract.amount()
                    : ContractText.describe(contract) + " [encounter " + contract.objective().targetEncounter() + "]") + " base=" + contract.objective().baseReward() + " agreed=" + contract.agreedReward()
                + " reserved=" + contract.reservedReward() + " rep=+" + contract.objective().reputationReward() + " severity="
                + contract.objective().severity() + (contract.holder() == null ? "" : " holder=" + holderName(source, contract.holder()))
                + (contract.status() == ContractStatus.OFFERED ? " expiresIn=" + ContractText.duration(contract.offerExpiresAt() - gameTime) : "")
                + (contract.status() == ContractStatus.ACCEPTED ? (contract.objective().security() ? " until the encounter ends"
                    : " dueIn=" + ContractText.duration(contract.deadline() - gameTime)) : "")
                + (contract.status() == ContractStatus.COMPLETED ? " rewardIssued=" + contract.rewardIssued() : "")), false);
        return contracts.size();
    }

    private static int refresh(final CommandSourceStack source, final String settlementText)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final UUID settlementId = uuid(source, settlementText);
        if (settlementId == null) return 0;
        final var colony = data.colony(settlementId).orElse(null);
        if (colony == null)
        {
            source.sendFailure(Component.literal("Unknown settlement " + settlementId));
            return 0;
        }
        final List<Contract> offers = ContractService.refresh(data, settlementId, source.getServer().overworld().getGameTime(),
            ContractManager.settings(), true);
        final long treasury = data.faction(colony.factionId()).map(Faction::treasury).orElse(0L);
        final String needs = colony.needs().stream().map(need -> need.type() + ":" + need.severity()).toList().toString();
        source.sendSuccess(() -> Component.literal(colony.name() + ": offers=" + offers.size() + " treasury=" + treasury
            + " needs=" + needs), true);
        offers.forEach(contract -> source.sendSuccess(() -> Component.literal("- " + contract.id() + " " + contract.resource() + " "
            + contract.amount() + " reward=" + contract.objective().baseReward() + " severity=" + contract.objective().severity()), false));
        return offers.size();
    }

    private static int stats(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var stats = ContractManager.getInstance().stats();
        final Map<ContractStatus, Long> counts = new java.util.EnumMap<>(ContractStatus.class);
        data.contracts().contracts().forEach(value -> counts.merge(value.status(), 1L, Long::sum));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Contracts: %s | session accepted=%d completed=%d failed=%d abandoned=%d units=%d rolledBack=%d | reputation: %d players, %d entries",
            counts, stats.accepted(), stats.completed(), stats.failed(), stats.abandoned(), stats.delivered(), stats.rolledBack(),
            data.reputation().players(), data.reputation().entries())).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int cancel(final CommandSourceStack source, final String text)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final Contract contract = data.contracts().resolve(text).orElse(null);
        if (contract == null || contract.status().terminal())
        {
            source.sendFailure(Component.literal("No open contract " + text));
            return 0;
        }
        final int reserved = contract.reservedReward();
        ContractService.cancel(data, contract, Contract.CloseReason.ADMIN, source.getServer().overworld().getGameTime());
        source.sendSuccess(() -> Component.literal("Cancelled contract " + contract.id() + "; " + reserved
            + " reserved emeralds returned to the treasury; no reputation change."), true);
        return 1;
    }

    private static int history(final CommandSourceStack source, final ServerPlayer player)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var events = data.reputation().events().stream()
            .filter(event -> player == null || event.player().equals(player.getUUID())).limit(20).toList();
        source.sendSuccess(() -> Component.literal("Reputation changes (newest first): " + events.size()).withStyle(ChatFormatting.GOLD), false);
        for (final var event : events)
            source.sendSuccess(() -> Component.literal("t=" + event.gameTime() + " " + holderName(source, event.player()) + " @ "
                + data.faction(event.faction()).map(Faction::name).orElse(event.faction().toString()) + ": "
                + (event.delta() > 0 ? "+" : "") + event.delta() + " -> " + event.after() + " " + event.cause()
                + (event.reference() == null ? "" : " ref=" + event.reference())), false);
        return events.size();
    }

    private static String settlementName(final KingdomsSavedData data, final UUID settlementId)
    {
        return data.settlements().get(settlementId).map(SettlementRecord::name)
            .or(() -> data.colony(settlementId).map(colony -> colony.name())).orElse(settlementId.toString());
    }

    private static String holderName(final CommandSourceStack source, final UUID playerId)
    {
        final ServerPlayer online = source.getServer().getPlayerList().getPlayer(playerId);
        return online == null ? playerId.toString() : online.getGameProfile().getName();
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
