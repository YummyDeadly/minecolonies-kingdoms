package com.minecolonies.kingdoms.war;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.contract.ContractManager;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.contract.ContractText;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Schedules the strategic war layer (Phase 10): one war evaluation per configured interval (declarations, mobilization,
 * ends) and a campaign update every {@value #CAMPAIGN_TICKS} ticks (armies, sieges, battle resolutions, returns), then
 * tells players what happened. It holds no authority: {@link WarService} and {@link CampaignService} change state.
 */
public final class WarManager
{
    private static final WarManager INSTANCE = new WarManager();
    public static final int CAMPAIGN_TICKS = 100;
    /** Players within this distance of a siege hear about it (everyone hears declarations and peace). */
    static final double NEWS_RADIUS = 512.0D;

    private MinecraftServer server;
    private long evaluations;
    private long updates;
    private long totalNanos;
    private long maximumNanos;
    private long failures;
    private WarService.Evaluation lastEvaluation;

    private WarManager() {}
    public static WarManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        server = value;
        evaluations = updates = totalNanos = maximumNanos = failures = 0L;
        lastEvaluation = null;
    }

    public void shutdown()
    {
        server = null;
        lastEvaluation = null;
    }

    public void tick(final MinecraftServer value)
    {
        if (server == null || server != value) return;
        final long gameTime = value.overworld().getGameTime();
        if (gameTime % CAMPAIGN_TICKS != 0) return;
        final WarSettings settings = settingsFromConfig();
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final long last = data.war().lastEvaluatedAt();
        if (last < 0L || gameTime - last >= settings.evaluationIntervalTicks()) evaluate(data, gameTime, settings);
        update(data, gameTime, settings);
    }

    public WarService.Evaluation evaluateNow(final MinecraftServer value)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final WarService.Evaluation evaluation = evaluate(data, value.overworld().getGameTime(), settingsFromConfig());
        update(data, value.overworld().getGameTime(), settingsFromConfig());
        return evaluation;
    }

    /** Operator: decide a siege now through the single battle resolution, then tell the people involved. */
    public CampaignService.Resolution resolveNow(final MinecraftServer value, final BattleRecord battle)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final CampaignService.Resolution resolution = CampaignService.resolveBattle(data, battle, value.overworld().getGameTime(), settingsFromConfig(),
            ContractManager.settings());
        announceBattle(data, resolution);
        return resolution;
    }

    public CampaignService.Update updateNow(final MinecraftServer value)
    {
        return update(KingdomsSavedData.get(value.overworld()), value.overworld().getGameTime(), settingsFromConfig());
    }

    private WarService.Evaluation evaluate(final KingdomsSavedData data, final long gameTime, final WarSettings settings)
    {
        final long started = System.nanoTime();
        try
        {
            final WarService.Evaluation evaluation = WarService.evaluate(data, gameTime, settings);
            evaluation.declared().forEach(war -> announceDeclaration(data, war));
            evaluation.activated().forEach(war -> broadcast(Component.literal("The war between " + faction(data, war.attacker()) + " and "
                + faction(data, war.defender()) + " has begun: armies may march.").withStyle(ChatFormatting.RED)));
            evaluation.ended().forEach(war -> announcePeace(data, war));
            lastEvaluation = evaluation;
            evaluations++;
            return evaluation;
        }
        catch (RuntimeException exception)
        {
            failures++;
            KingdomsMod.LOGGER.error("War evaluation failed", exception);
            data.war().setLastEvaluatedAt(gameTime); // next attempt after the normal interval
            data.markChanged();
            return null;
        }
        finally
        {
            record(System.nanoTime() - started);
        }
    }

    private CampaignService.Update update(final KingdomsSavedData data, final long gameTime, final WarSettings settings)
    {
        final long started = System.nanoTime();
        try
        {
            final CampaignService.Update update = CampaignService.update(data, gameTime, settings, ContractManager.settings());
            update.raised().forEach(army -> {
                KingdomsMod.LOGGER.info("Army {} raised: {} soldiers from {} against {}", army.id(), army.strength(), army.originSettlementId(),
                    army.targetSettlementId());
                broadcast(Component.literal("An army of " + army.strength() + " soldiers leaves " + settlement(data, army.originSettlementId())
                    + " to besiege " + settlement(data, army.targetSettlementId()) + ".").withStyle(ChatFormatting.GOLD));
            });
            update.started().forEach(battle -> announceSiege(data, battle));
            update.resolved().forEach(resolution -> announceBattle(data, resolution));
            update.cancelled().forEach(battle -> near(battle, Component.literal("The siege of " + settlement(data, battle.settlementId())
                + " is called off.").withStyle(ChatFormatting.YELLOW)));
            update.disbanded().forEach(army -> KingdomsMod.LOGGER.info("Army {} home: {} of {} soldiers returned", army.id(), army.returned(),
                army.detached()));
            updates++;
            return update;
        }
        catch (RuntimeException exception)
        {
            failures++;
            KingdomsMod.LOGGER.error("Campaign update failed", exception);
            return null;
        }
        finally
        {
            record(System.nanoTime() - started);
        }
    }

    // ------------------------------------------------------------------------------------------------ announcements

    public void announceDeclaration(final KingdomsSavedData data, final WarRecord war)
    {
        KingdomsMod.LOGGER.info("War {} declared: {} against {} ({})", war.id(), war.attacker(), war.defender(), war.cause());
        broadcast(Component.literal(faction(data, war.attacker()) + " declares war on " + faction(data, war.defender()) + "!")
            .withStyle(ChatFormatting.RED));
    }

    public void announcePeace(final KingdomsSavedData data, final WarRecord war)
    {
        KingdomsMod.LOGGER.info("War {} ended: {} (score {}, battles {}/{}, indemnity {})", war.id(), war.result(), war.score(), war.battlesWon(),
            war.battlesLost(), war.tribute());
        final String outcome = switch (war.result())
        {
            case ATTACKER_VICTORY -> faction(data, war.attacker()) + " wins the war against " + faction(data, war.defender());
            case DEFENDER_VICTORY -> faction(data, war.defender()) + " holds off " + faction(data, war.attacker());
            case WHITE_PEACE -> faction(data, war.attacker()) + " and " + faction(data, war.defender()) + " make peace";
        };
        broadcast(Component.literal(outcome + (war.tribute() > 0 ? " (indemnity " + war.tribute() + ")" : "") + ".").withStyle(ChatFormatting.GOLD));
    }

    private void announceSiege(final KingdomsSavedData data, final BattleRecord battle)
    {
        KingdomsMod.LOGGER.info("Siege {} of {} begins: {} attackers vs {} defenders", battle.id(), battle.settlementId(), battle.attackerStrength(),
            battle.defenderStrength());
        near(battle, Component.literal(settlement(data, battle.settlementId()) + " is besieged by " + battle.attackerStrength()
            + " soldiers of " + faction(data, battle.attacker()) + "! Its council asks for defenders.").withStyle(ChatFormatting.RED));
    }

    private void announceBattle(final KingdomsSavedData data, final CampaignService.Resolution resolution)
    {
        if (!resolution.applied()) return;
        final BattleRecord battle = resolution.battle();
        KingdomsMod.LOGGER.info("Battle {} resolved: {} (attackers lost {} of {}, defenders lost {} of {}, tribute {})", battle.id(), battle.outcome(),
            battle.attackerLosses(), battle.attackerStrength(), battle.defenderLosses(), battle.defenderStrength(), battle.tributeTaken());
        final String name = settlement(data, battle.settlementId());
        final Component text = Component.literal(battle.outcome() == BattleRecord.Outcome.ATTACKER_VICTORY
            ? name + " falls to " + faction(data, battle.attacker()) + " and is sacked" + (battle.tributeTaken() > 0 ? " (tribute " + battle.tributeTaken() + ")" : "") + "."
            : name + " holds! The besiegers retreat.").withStyle(battle.outcome() == BattleRecord.Outcome.ATTACKER_VICTORY ? ChatFormatting.RED : ChatFormatting.GREEN);
        near(battle, text);
        if (server == null) return;
        final Set<UUID> told = new LinkedHashSet<>(battle.defenders());
        resolution.contracts().forEach(closure -> { if (closure.contract().holder() != null) told.add(closure.contract().holder()); });
        told.addAll(resolution.reputation().keySet());
        for (final UUID playerId : told)
        {
            final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            for (final ContractService.Closure closure : resolution.contracts())
            {
                if (!playerId.equals(closure.contract().holder())) continue;
                player.sendSystemMessage(Component.literal(switch (closure.contract().status())
                {
                    case COMPLETED -> "Contract fulfilled.";
                    case FAILED -> "Contract failed: the settlement fell.";
                    default -> "Your contract was cancelled: the siege ended without you.";
                }).withStyle(closure.contract().status() == ContractStatus.COMPLETED ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                if (!closure.reputation().isEmpty()) player.sendSystemMessage(ContractText.reputation(data, closure.reputation()));
            }
            final List<com.minecolonies.kingdoms.diplomacy.ReputationService.Result> reputation = resolution.reputation().get(playerId);
            if (reputation != null) reputation.stream().filter(result -> !result.isEmpty())
                .forEach(result -> player.sendSystemMessage(ContractText.reputation(data, result)));
            ContractManager.getInstance().claimPending(player).ifPresent(player::sendSystemMessage);
        }
    }

    private void broadcast(final Component message)
    {
        if (server != null) server.getPlayerList().getPlayers().forEach(player -> player.sendSystemMessage(message));
    }

    private void near(final BattleRecord battle, final Component message)
    {
        if (server == null) return;
        server.getPlayerList().getPlayers().stream()
            .filter(player -> player.level().dimension().location().equals(battle.dimension())
                && player.blockPosition().distSqr(battle.position()) <= NEWS_RADIUS * NEWS_RADIUS)
            .forEach(player -> player.sendSystemMessage(message));
    }

    static String faction(final KingdomsSavedData data, final UUID factionId)
    {
        return data.faction(factionId).map(Faction::name).orElse("?");
    }

    static String settlement(final KingdomsSavedData data, final UUID settlementId)
    {
        return data.settlements().get(settlementId).map(SettlementRecord::name).orElse("?");
    }

    // ------------------------------------------------------------------------------------------------ stats

    private void record(final long elapsed)
    {
        totalNanos += elapsed;
        maximumNanos = Math.max(maximumNanos, elapsed);
    }

    public record Stats(long evaluations, long updates, double averageNanos, long maximumNanos, long failures, WarService.Evaluation lastEvaluation) {}

    public Stats stats()
    {
        final long runs = evaluations + updates;
        return new Stats(evaluations, updates, runs == 0 ? 0.0D : (double) totalNanos / runs, maximumNanos, failures, lastEvaluation);
    }

    public static WarSettings settingsFromConfig()
    {
        final var config = KingdomsConfig.SERVER;
        return new WarSettings(config.warEnabled.get(), config.warAutonomous.get(), config.warEvaluationIntervalTicks.get(),
            config.warHostileEvaluations.get(), config.warMaxWars.get(), config.warMinimumArmy.get(),
            Math.max(config.warMaxArmySize.get(), config.warMinimumArmy.get()), config.warMobilizationTicks.get(), config.warMaxDurationTicks.get(),
            config.warVictoryScore.get(), config.warCeasefireTicks.get(), config.warTruceTicks.get(), config.warArmyTicksPerBlock.get(),
            config.warSiegeTicks.get(), config.warArmyCooldownTicks.get(), config.warSackedTicks.get(), config.soldiersMaxPerArmy.get(),
            config.soldiersMaxGlobal.get(), config.soldiersMaxPerPlayer.get(), config.soldiersMaterializationRadius.get(),
            Math.max(config.soldiersDematerializationRadius.get(), config.soldiersMaterializationRadius.get() + 16));
    }
}
