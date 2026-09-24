package com.minecolonies.kingdoms.war;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.diplomacy.ReputationRegistry;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.military.GarrisonRecord;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRoute;
import com.minecolonies.kingdoms.world.road.RoadRoutePlanner;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of armies and battles (Phase 10). An army is raised from one garrison in one transaction (soldiers
 * detached), marches along the road graph by time, besieges a settlement, and comes home in one transaction (survivors
 * re-attached exactly once). A battle is resolved exactly once by {@link #resolveBattle}; physical fighting only records
 * losses on the battle, which the resolution folds in ({@code max(recorded, rolled)} per side), so a fight seen by
 * players and the same fight unseen never both apply losses. Soldiers that unload or die to mobs are not casualties.
 */
public final class CampaignService
{
    public static final int DEFENDER_REPUTATION = 3;
    public static final int SOLDIER_KILL_REPUTATION = 3;
    public static final int DEFEND_CONTRACT_REPUTATION = 8;
    public static final int BATTLE_RELATION = -10;
    /** A war that could not raise an army tries again after this long (not every campaign update). */
    public static final long RAISE_RETRY_TICKS = 2_400L;

    private CampaignService() {}

    public record Update(List<ArmyRecord> raised, List<BattleRecord> started, List<Resolution> resolved, List<ArmyRecord> disbanded,
        List<BattleRecord> cancelled) {}

    /** {@code reputation}: player -> the reputation changes applied for this battle. */
    public record Resolution(boolean applied, BattleRecord battle, List<ContractService.Closure> contracts,
        Map<UUID, List<ReputationService.Result>> reputation)
    {
        static Resolution notApplied(final BattleRecord battle) { return new Resolution(false, battle, List.of(), Map.of()); }
    }

    /** Where an army would come from and go: the attacker's best garrison and the defender's nearest settlement by road. */
    public record Plan(SettlementRecord origin, SettlementRecord target, RoadRoute route, int size) {}

    // ------------------------------------------------------------------------------------------------ cycle

    public static Update update(final KingdomsSavedData data, final long gameTime, final WarSettings settings, final ContractSettings contracts)
    {
        final List<ArmyRecord> raised = new ArrayList<>();
        final List<BattleRecord> started = new ArrayList<>();
        final List<Resolution> resolved = new ArrayList<>();
        final List<ArmyRecord> disbanded = new ArrayList<>();
        final List<BattleRecord> cancelled = new ArrayList<>();
        // 0. a siege whose army is gone (an unreadable record) or no longer at it is called off, never left open
        for (final BattleRecord battle : List.copyOf(data.war().battles()))
        {
            if (!battle.open()) continue;
            final ArmyRecord army = data.war().army(battle.armyId()).orElse(null);
            if (army == null || !army.open() || army.status() != ArmyRecord.Status.BESIEGING || !battle.id().equals(army.battleId()))
            {
                cancelBattle(data, battle, gameTime, contracts);
                cancelled.add(battle);
            }
        }
        // 1. one army per active war in the field at a time
        if (settings.enabled())
            for (final WarRecord war : List.copyOf(data.war().wars()))
            {
                if (war.status() != WarRecord.Status.ACTIVE) continue;
                if (data.war().armies().stream().anyMatch(army -> army.open() && army.warId().equals(war.id()))) continue;
                if (war.lastArmyAt() != Long.MIN_VALUE && gameTime - war.lastArmyAt() < settings.armyCooldownTicks()) continue;
                if (gameTime < war.nextRaiseAt()) continue;
                try
                {
                    final Optional<ArmyRecord> army = raiseArmy(data, war, gameTime, settings);
                    if (army.isPresent()) raised.add(army.get());
                    else war.raiseFailed(gameTime + RAISE_RETRY_TICKS);
                }
                catch (RuntimeException exception)
                {
                    KingdomsMod.LOGGER.error("Raising an army for war {} failed", war.id(), exception);
                }
            }
        // 2. armies: arrivals, sieges, returns (armies keep moving home even while wars are switched off); each in isolation
        for (final ArmyRecord army : data.war().armies().stream().filter(ArmyRecord::open).sorted(byRaisedAt()).toList())
        {
            try
            {
                updateArmy(data, army, gameTime, settings, contracts, started, resolved, disbanded, cancelled);
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Campaign update of army {} failed", army.id(), exception);
            }
        }
        reconcileGarrisons(data);
        return new Update(List.copyOf(raised), List.copyOf(started), List.copyOf(resolved), List.copyOf(disbanded), List.copyOf(cancelled));
    }

    private static void updateArmy(final KingdomsSavedData data, final ArmyRecord army, final long gameTime, final WarSettings settings,
        final ContractSettings contracts, final List<BattleRecord> started, final List<Resolution> resolved, final List<ArmyRecord> disbanded,
        final List<BattleRecord> cancelled)
    {
        final WarRecord war = data.war().war(army.warId()).orElse(null);
        final boolean fighting = settings.enabled() && war != null && war.status().fighting();
        if (army.status() == ArmyRecord.Status.MARCHING)
        {
            if (!fighting || army.strength() <= 0) army.returnHome(gameTime);
            else if (army.arrivedAt(gameTime)) startSiege(data, war, army, gameTime, settings, contracts).ifPresent(started::add);
        }
        else if (army.status() == ArmyRecord.Status.BESIEGING)
        {
            final BattleRecord battle = army.battleId() == null ? null : data.war().battle(army.battleId()).orElse(null);
            if (battle == null || !battle.open())
            {
                army.battleOver();
                army.returnHome(gameTime);
            }
            else if (!fighting)
            {
                cancelBattle(data, battle, gameTime, contracts);
                cancelled.add(battle);
            }
            else if (gameTime >= battle.resolveAt() || decidedPhysically(battle))
                resolved.add(resolveBattle(data, battle, gameTime, settings, contracts));
        }
        if (army.status() == ArmyRecord.Status.RETURNING && (army.arrivedAt(gameTime) || army.strength() <= 0))
        {
            // home, or nobody left to walk home
            disband(data, army, gameTime);
            disbanded.add(army);
        }
        data.markChanged();
    }

    /** Soldiers counted away with armies must match the armies in the field (heals an unreadable army record). */
    private static void reconcileGarrisons(final KingdomsSavedData data)
    {
        final Map<UUID, Integer> inTheField = new HashMap<>();
        for (final ArmyRecord army : data.war().armies())
            if (army.open()) inTheField.merge(army.originSettlementId(), army.detached(), Integer::sum);
        for (final GarrisonRecord garrison : List.copyOf(data.military().garrisons()))
            if (garrison.detached() > inTheField.getOrDefault(garrison.settlementId(), 0))
                MilitaryService.reconcileDetached(data, garrison.settlementId(), inTheField.getOrDefault(garrison.settlementId(), 0));
    }

    // ------------------------------------------------------------------------------------------------ armies

    /**
     * The attacker's best army plan against the defender: from each of its settlements that can field an army, to each
     * defender settlement reachable by road in the same dimension; the shortest route wins, then the larger army, then
     * the lower IDs (deterministic). Bounded by the two factions' settlements.
     */
    public static Optional<Plan> plan(final KingdomsSavedData data, final Faction attacker, final Faction defender, final WarSettings settings)
    {
        final RoadRoutePlanner planner = new RoadRoutePlanner();
        Plan best = null;
        for (final UUID originId : attacker.settlementIds().stream().sorted().toList())
        {
            final SettlementRecord origin = data.settlements().get(originId).orElse(null);
            final GarrisonRecord garrison = data.military().garrison(originId).orElse(null);
            if (origin == null || garrison == null) continue;
            final int size = WarRules.armySize(garrison.strength(), settings.minimumArmy(), settings.maxArmySize());
            if (size <= 0) continue;
            for (final UUID targetId : defender.settlementIds().stream().sorted().toList())
            {
                final SettlementRecord target = data.settlements().get(targetId).orElse(null);
                if (target == null || !target.dimension().equals(origin.dimension())) continue;
                final Optional<RoadRoute> route = planner.shortest(data.roads(), originId, targetId);
                if (route.isEmpty()) continue;
                if (best == null || route.get().length() < best.route().length()
                    || (route.get().length() == best.route().length() && size > best.size()))
                    best = new Plan(origin, target, route.get(), size);
            }
        }
        return Optional.ofNullable(best);
    }

    /** Raises the attacker's army for a war: soldiers leave the garrison in the same transaction that creates the army. */
    public static Optional<ArmyRecord> raiseArmy(final KingdomsSavedData data, final WarRecord war, final long gameTime, final WarSettings settings)
    {
        if (war.status() != WarRecord.Status.ACTIVE) return Optional.empty();
        final Faction attacker = data.faction(war.attacker()).orElse(null);
        final Faction defender = data.faction(war.defender()).orElse(null);
        if (attacker == null || defender == null) return Optional.empty();
        final Plan plan = plan(data, attacker, defender, settings).orElse(null);
        if (plan == null) return Optional.empty();
        final RoadShipmentPath path;
        try
        {
            path = new RoadShipmentPath(plan.route(), data.roads());
        }
        catch (RuntimeException exception)
        {
            return Optional.empty(); // a road of the route has no usable geometry
        }
        final long travel = Math.max(200L, (long) Math.ceil(path.effectiveDistance() * settings.armyTicksPerBlock()));
        final UUID id = WarRules.armyId(war.id(), war.nextArmyOrdinal());
        if (data.war().army(id).isPresent()) return Optional.empty();
        final int left = MilitaryService.detach(data, plan.origin().id(), plan.size());
        if (left <= 0) return Optional.empty();
        final ArmyRecord army = new ArmyRecord(id, war.id(), attacker.id(), plan.origin().id(), plan.target().id(), plan.origin().dimension(),
            plan.route().roadIds(), plan.route().settlementIds(), left, gameTime, travel);
        data.war().putArmy(army);
        war.armyRaised(gameTime);
        data.markChanged();
        return Optional.of(army);
    }

    /** Survivors go back to their garrison once; the army ends. */
    static void disband(final KingdomsSavedData data, final ArmyRecord army, final long gameTime)
    {
        if (!army.open()) return;
        army.disband(army.strength(), gameTime);
        MilitaryService.reattach(data, army.originSettlementId(), army.detached(), army.returned(), army.id());
        data.markChanged();
    }

    /**
     * A marching or returning squad's leader reached {@code progress} along the route: the army follows its physical
     * representation (never backwards), so soldiers seen by players are where the army is.
     */
    public static void hold(final KingdomsSavedData data, final ArmyRecord army, final double progress, final long gameTime)
    {
        if (!army.open() || army.status() == ArmyRecord.Status.BESIEGING || !Double.isFinite(progress)) return;
        army.hold(progress, gameTime);
        data.markChanged();
    }

    /** A soldier of a marching or returning army was killed by a player or an enemy guard (a skirmish): the army loses it now. */
    public static boolean skirmishLoss(final KingdomsSavedData data, final ArmyRecord army)
    {
        if (!army.open() || army.status() == ArmyRecord.Status.BESIEGING) return false;
        final boolean lost = army.skirmishLoss() > 0;
        data.markChanged();
        return lost;
    }

    // ------------------------------------------------------------------------------------------------ battles

    static Optional<BattleRecord> startSiege(final KingdomsSavedData data, final WarRecord war, final ArmyRecord army, final long gameTime,
        final WarSettings settings, final ContractSettings contracts)
    {
        final SettlementRecord target = data.settlements().get(army.targetSettlementId()).orElse(null);
        if (target == null || data.war().openBattleAt(target.id()).isPresent())
        {
            army.returnHome(gameTime); // gone, or already besieged by another army
            return Optional.empty();
        }
        final GarrisonRecord garrison = data.military().garrison(target.id()).orElse(null);
        final int defenders = garrison == null ? 0 : garrison.strength();
        final int fortification = garrison == null ? 0 : garrison.security();
        final UUID id = WarRules.battleId(army.id());
        if (data.war().battle(id).isPresent())
        {
            army.returnHome(gameTime);
            return Optional.empty();
        }
        final BattleRecord battle = new BattleRecord(id, war.id(), army.id(), war.attacker(), war.defender(), target.id(), target.dimension(),
            target.gate(), army.strength(), defenders, fortification, gameTime, gameTime + settings.siegeTicks());
        data.war().putBattle(battle);
        army.besiege(id, gameTime);
        postDefenceOffer(data, battle, gameTime, contracts);
        data.markChanged();
        return Optional.of(battle);
    }

    /** The besieged settlement asks players for help: one DEFEND_SETTLEMENT offer through the Phase 7 contract service. */
    static Optional<Contract> postDefenceOffer(final KingdomsSavedData data, final BattleRecord battle, final long gameTime,
        final ContractSettings contracts)
    {
        final int soldiers = Math.max(1, battle.attackerStrength());
        final Contract.Objective objective = new Contract.Objective(Contract.Kind.DEFEND_SETTLEMENT, null, soldiers, NeedSeverity.CRITICAL,
            0.0D, 0.0D, 8 + 2 * Math.min(20, soldiers), DEFEND_CONTRACT_REPUTATION, battle.id(), null, battle.position());
        return ContractService.postSecurityOffer(data, battle.settlementId(), objective, gameTime, Math.max(gameTime + 1_200L, battle.resolveAt()),
            contracts);
    }

    /** A soldier of the besieging army died in the siege (killed by a guard or a player); returns whether it counted. */
    public static boolean attackerFell(final KingdomsSavedData data, final BattleRecord battle, final UUID player)
    {
        if (!battle.open() || !battle.attackerFell()) return false;
        if (player != null)
        {
            battle.defender(player, holds(data, battle, player));
            battle.soldierKilledBy(player);
        }
        data.markChanged();
        return true;
    }

    /** A guard of the besieged settlement died in the siege (killed by a soldier of the besieging army). */
    public static boolean defenderFell(final KingdomsSavedData data, final BattleRecord battle)
    {
        if (!battle.open() || !battle.defenderFell()) return false;
        data.markChanged();
        return true;
    }

    /** A player helped the defence (hit a besieging soldier). */
    public static void recordDefender(final KingdomsSavedData data, final BattleRecord battle, final UUID player)
    {
        if (!battle.open() || battle.defenders().contains(player)) return;
        battle.defender(player, holds(data, battle, player));
        data.markChanged();
    }

    private static boolean holds(final KingdomsSavedData data, final BattleRecord battle, final UUID player)
    {
        return data.contracts().targeting(battle.id()).stream()
            .anyMatch(contract -> contract.status() == ContractStatus.ACCEPTED && player.equals(contract.holder()));
    }

    static boolean decidedPhysically(final BattleRecord battle)
    {
        return (battle.attackerStrength() > 0 && battle.attackerPhysicalLosses() >= battle.attackerStrength())
            || (battle.defenderStrength() > 0 && battle.defenderPhysicalLosses() >= battle.defenderStrength());
    }

    /**
     * The single battle resolution: decide (seed + frozen strengths + recorded physical losses), apply losses to the
     * army and the garrison once, move the war score once, sack on an attacker victory (tribute from the defender's free
     * treasury, temporary security loss), a relation event, close the defence contracts, apply player reputation once,
     * and send the army home. A second call changes nothing.
     */
    public static Resolution resolveBattle(final KingdomsSavedData data, final BattleRecord battle, final long gameTime, final WarSettings settings,
        final ContractSettings contracts)
    {
        if (!battle.open()) return Resolution.notApplied(battle);
        if (!data.war().war(battle.warId()).map(war -> war.status().fighting()).orElse(false))
        {
            cancelBattle(data, battle, gameTime, contracts); // the war is over: no result after peace
            return Resolution.notApplied(battle);
        }
        final WarRules.Battle decision = WarRules.decide(battle.seed(), battle.attackerStrength(), battle.defenderStrength(), battle.fortification(),
            battle.attackerPhysicalLosses(), battle.defenderPhysicalLosses());
        battle.resolve(decision.outcome(), decision.attackerLosses(), decision.defenderLosses(), gameTime);
        final ArmyRecord army = data.war().army(battle.armyId()).orElse(null);
        if (army != null) army.lose(battle.attackerLosses());
        MilitaryService.battleLosses(data, battle.settlementId(), battle.id(), battle.defenderLosses(), gameTime);
        final boolean attackerWon = battle.outcome() == BattleRecord.Outcome.ATTACKER_VICTORY;
        data.war().war(battle.warId()).ifPresent(war -> war.battle(battle.id(), attackerWon, WarRules.scoreSwing()));
        final Faction attacker = data.faction(battle.attacker()).orElse(null);
        final Faction defender = data.faction(battle.defender()).orElse(null);
        if (attackerWon)
        {
            if (defender != null) ContractService.accrueTreasury(data, defender, gameTime);
            final int tribute = WarRules.sackTribute(defender == null ? 0L : defender.treasury(), battle.defenderStrength());
            battle.tribute(ContractService.transferTreasury(data, battle.defender(), battle.attacker(), tribute, gameTime));
            MilitaryService.sacked(data, battle.settlementId(), gameTime + settings.sackedTicks());
        }
        else MilitaryService.defendedBattle(data, battle.settlementId(), battle.id(), gameTime);
        if (attacker != null && defender != null)
            DiplomacyService.change(data, attacker, defender, BATTLE_RELATION, DiplomacyState.Cause.WAR_BATTLE, attackerWon ? 1 : 0, gameTime);
        final boolean playersWon = !attackerWon && !battle.defenders().isEmpty();
        final List<ContractService.Closure> closures = ContractService.onEncounterResolved(data, battle.id(), playersWon, attackerWon, false,
            battle.defenders(), gameTime, contracts);
        final Map<UUID, List<ReputationService.Result>> reputation = new LinkedHashMap<>();
        final List<UUID> holders = closures.stream().map(closure -> closure.contract().holder()).toList();
        if (!attackerWon)
            for (final UUID player : battle.defenders())
                if (!holders.contains(player))
                    reputation.computeIfAbsent(player, key -> new ArrayList<>()).add(ReputationService.adjust(data, player, battle.defender(),
                        DEFENDER_REPUTATION, ReputationRegistry.Cause.BATTLE_DEFENDED, battle.id(), gameTime));
        for (final UUID player : battle.attackersOfSoldiers())
            reputation.computeIfAbsent(player, key -> new ArrayList<>()).add(ReputationService.adjust(data, player, battle.attacker(),
                -SOLDIER_KILL_REPUTATION, ReputationRegistry.Cause.SOLDIERS_KILLED, battle.id(), gameTime));
        battle.markConsequencesApplied();
        if (army != null)
        {
            army.battleOver();
            army.returnHome(gameTime);
        }
        data.markChanged();
        com.minecolonies.kingdoms.worldevent.WorldHistory.record(data, com.minecolonies.kingdoms.worldevent.WorldHistory.Entry.of(com.minecolonies.kingdoms.worldevent.WorldHistory.Kind.BATTLE, battle.id(), gameTime, battle.attacker(), battle.defender(),
            "Battle at " + data.settlements().get(battle.settlementId()).map(value -> value.name()).orElse("?") + ": "
                + battle.outcome().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " (losses " + battle.attackerLosses() + "/"
                + battle.defenderLosses() + ")"));
        return new Resolution(true, battle, closures, Map.copyOf(reputation));
    }

    /** The war ended (or wars were switched off) mid-siege: no result, no losses, contracts cancelled without penalty. */
    static void cancelBattle(final KingdomsSavedData data, final BattleRecord battle, final long gameTime, final ContractSettings contracts)
    {
        if (!battle.open()) return;
        battle.cancel(gameTime);
        ContractService.onEncounterResolved(data, battle.id(), false, false, false, List.of(), gameTime, contracts);
        battle.markConsequencesApplied();
        data.war().army(battle.armyId()).ifPresent(army -> {
            army.battleOver();
            army.returnHome(gameTime);
        });
        data.markChanged();
    }

    public static Comparator<ArmyRecord> byRaisedAt() { return Comparator.comparingLong(ArmyRecord::raisedAt).thenComparing(ArmyRecord::id); }
}
