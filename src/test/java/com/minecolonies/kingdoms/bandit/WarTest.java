package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.diplomacy.DiplomaticStance;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.military.GarrisonRecord;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecuritySettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.war.ArmyRecord;
import com.minecolonies.kingdoms.war.BattleRecord;
import com.minecolonies.kingdoms.war.CampaignService;
import com.minecolonies.kingdoms.war.WarRecord;
import com.minecolonies.kingdoms.war.WarRules;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.war.WarSettings;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 10: wars, armies, sieges, battles, and peace. Lives in the bandit package to use its two-settlement fixtures. */
class WarTest
{
    private static final WarSettings WAR = WarSettings.defaults();
    private static final long DAY = 24_000L;

    private static KingdomsSavedData armed(final int attackers, final int defenders)
    {
        final KingdomsSavedData data = world();
        MilitaryService.evaluate(data, 0L, SecuritySettings.defaults(), SETTINGS, CONTRACTS);
        MilitaryService.setStrength(data, A, attackers);
        MilitaryService.setStrength(data, B, defenders);
        return data;
    }

    private static Faction faction(final KingdomsSavedData data, final UUID id) { return data.faction(id).orElseThrow(); }

    private static GarrisonRecord garrison(final KingdomsSavedData data, final UUID id) { return data.military().garrison(id).orElseThrow(); }

    private static void hostile(final KingdomsSavedData data)
    {
        DiplomacyService.set(data, faction(data, FA), faction(data, FB), -80, DiplomacyState.Cause.ADMIN_SET, 0L, 0L);
    }

    private static WarRecord activeWar(final KingdomsSavedData data, final long gameTime)
    {
        final WarRecord war = WarService.declare(data, FA, FB, WarRecord.Cause.ADMIN, 0L, gameTime, WAR, false).war().orElseThrow();
        assertTrue(WarService.mobilizeNow(data, war));
        return war;
    }

    private static ArmyRecord onlyArmy(final KingdomsSavedData data)
    {
        return data.war().armies().stream().filter(ArmyRecord::open).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------------ declaration

    @Test
    void aHostileRelationAloneNeverStartsAWar()
    {
        final KingdomsSavedData data = armed(12, 6);
        hostile(data);
        WarService.evaluate(data, DAY, WAR);
        WarService.evaluate(data, 2 * DAY, WAR);
        assertTrue(data.war().wars().isEmpty(), "two hostile evaluations are not enough");
        // a friendly spell in between resets the streak
        DiplomacyService.set(data, faction(data, FA), faction(data, FB), 0, DiplomacyState.Cause.ADMIN_SET, 0L, 2 * DAY);
        WarService.evaluate(data, 3 * DAY, WAR);
        hostile(data);
        WarService.evaluate(data, 4 * DAY, WAR);
        WarService.evaluate(data, 5 * DAY, WAR);
        assertTrue(data.war().wars().isEmpty(), "the streak started again");
        // an attacker that cannot field an army never declares
        MilitaryService.setStrength(data, A, 3);
        MilitaryService.setStrength(data, B, 3);
        WarService.evaluate(data, 6 * DAY, WAR);
        assertTrue(data.war().wars().isEmpty(), "no army, no war");
        // switched off or at the cap: none either
        MilitaryService.setStrength(data, A, 12);
        final WarSettings passive = new WarSettings(true, false, 24_000, 3, 2, 4, 40, 12_000L, 168_000L, 50, 24_000L, 240_000L, 3.0D, 6_000L,
            24_000L, 72_000L, 8, 32, 16, 64, 96);
        WarService.evaluate(data, 7 * DAY, passive);
        final WarSettings capped = new WarSettings(true, true, 24_000, 3, 0, 4, 40, 12_000L, 168_000L, 50, 24_000L, 240_000L, 3.0D, 6_000L,
            24_000L, 72_000L, 8, 32, 16, 64, 96);
        WarService.evaluate(data, 8 * DAY, capped);
        assertTrue(data.war().wars().isEmpty());
        // lasting hostility and an army: war, declared by the stronger side, audited
        final WarService.Evaluation evaluation = WarService.evaluate(data, 9 * DAY, WAR);
        assertEquals(1, evaluation.declared().size());
        final WarRecord war = evaluation.declared().getFirst();
        assertEquals(FA, war.attacker());
        assertEquals(WarRecord.Cause.RELATION_COLLAPSE, war.cause());
        assertEquals(WarRecord.Status.DECLARED, war.status());
        assertTrue(war.evidence() >= WAR.hostileEvaluations(), "the war record carries its evidence: the hostility streak");
        // never a second war for the same factions
        for (long day = 10; day < 14; day++) WarService.evaluate(data, day * DAY, WAR);
        assertEquals(1, data.war().wars().size());
    }

    @Test
    void playersFactionsAndTrucesRefuseDeclarations()
    {
        final KingdomsSavedData data = armed(12, 6);
        final Faction original = faction(data, FB);
        data.putFaction(new Faction(FB, "Players", FactionType.PLAYER));
        assertEquals(WarService.Refusal.NOT_A_PARTICIPANT, WarService.declare(data, FA, FB, WarRecord.Cause.ADMIN, 0L, 0L, WAR, true).refusal());
        data.putFaction(original);
        final WarRecord war = activeWar(data, 0L);
        assertTrue(DiplomacyService.relation(faction(data, FA), faction(data, FB)) <= WarService.WAR_RELATION);
        assertTrue(data.diplomacy().events().stream().anyMatch(event -> event.cause() == DiplomacyState.Cause.WAR_DECLARED),
            "the relation change is audited");
        assertEquals(WarService.Refusal.ALREADY_AT_WAR, WarService.declare(data, FB, FA, WarRecord.Cause.ADMIN, 0L, 0L, WAR, false).refusal());
        WarService.end(data, war, WarRecord.Result.WHITE_PEACE, 100L, WAR);
        assertEquals(WarService.Refusal.TRUCE, WarService.declare(data, FA, FB, WarRecord.Cause.ADMIN, 0L, 200L, WAR, false).refusal());
        final WarRecord second = WarService.declare(data, FA, FB, WarRecord.Cause.ADMIN, 0L, 200L, WAR, true).war().orElseThrow();
        assertNotEquals(war.id(), second.id(), "war IDs are never reused");
        assertEquals(2, second.ordinal());
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    @Test
    void aWarRunsItsCampaignExactlyOnceAcrossRestarts()
    {
        KingdomsSavedData data = armed(12, 4);
        final long treasuries = faction(data, FA).treasury() + faction(data, FB).treasury();
        final WarRecord war = activeWar(data, 0L);
        // an army leaves: soldiers detached from the garrison in the same step
        CampaignService.update(data, 100L, WAR, CONTRACTS);
        final ArmyRecord army = onlyArmy(data);
        assertEquals(WarRules.armySize(12, WAR.minimumArmy(), WAR.maxArmySize()), army.strength());
        assertEquals(12 - army.strength(), garrison(data, A).strength());
        assertEquals(army.strength(), garrison(data, A).detached());
        CampaignService.update(data, 200L, WAR, CONTRACTS);
        assertEquals(1, data.war().armies().size(), "one army per war in the field");
        // halfway: a restart neither moves nor rewinds it
        final long half = 100L + army.fullTravelTicks() / 2;
        final double progress = army.progressAt(half);
        assertTrue(progress > 0.4D && progress < 0.6D);
        data = PersistenceTestAccess.reload(data);
        assertEquals(progress, data.war().army(army.id()).orElseThrow().progressAt(half), 1.0E-9);
        // arrival: a siege and a defence offer at the besieged settlement
        final long arrival = 100L + army.fullTravelTicks();
        final CampaignService.Update siege = CampaignService.update(data, arrival, WAR, CONTRACTS);
        assertEquals(1, siege.started().size());
        final BattleRecord battle = siege.started().getFirst();
        assertEquals(B, battle.settlementId());
        assertEquals(4, battle.defenderStrength());
        final Contract defend = data.contracts().targeting(battle.id()).getFirst();
        assertEquals(Contract.Kind.DEFEND_SETTLEMENT, defend.kind());
        assertEquals(B, defend.settlementId());
        CampaignService.update(data, arrival + 1_000L, WAR, CONTRACTS);
        assertTrue(battle.open(), "the siege lasts siegeTicks");
        // decision: once
        final long decided = battle.resolveAt();
        final CampaignService.Update result = CampaignService.update(data, decided, WAR, CONTRACTS);
        assertEquals(1, result.resolved().size());
        final BattleRecord resolved = data.war().battle(battle.id()).orElseThrow();
        assertEquals(BattleRecord.Status.RESOLVED, resolved.status());
        final ArmyRecord back = data.war().army(army.id()).orElseThrow();
        assertEquals(army.strength() - resolved.attackerLosses(), back.strength());
        assertEquals(4 - resolved.defenderLosses(), garrison(data, B).strength());
        final WarRecord current = data.war().war(war.id()).orElseThrow();
        assertEquals(resolved.outcome() == BattleRecord.Outcome.ATTACKER_VICTORY ? WarRules.scoreSwing() : -WarRules.scoreSwing(), current.score());
        assertEquals(ArmyRecord.Status.RETURNING, back.status());
        assertEquals(ContractStatus.CANCELLED, defend.status(), "an offer nobody took is withdrawn");
        final int survivors = back.strength();
        final int homeBefore = garrison(data, B).strength();
        // repeated updates and a restart change nothing
        data = PersistenceTestAccess.reload(data);
        assertFalse(CampaignService.resolveBattle(data, data.war().battle(battle.id()).orElseThrow(), decided + 5L, WAR, CONTRACTS).applied());
        CampaignService.update(data, decided + 10L, WAR, CONTRACTS);
        assertEquals(homeBefore, garrison(data, B).strength(), "defender losses once");
        assertEquals(1, data.war().war(war.id()).orElseThrow().battles().size(), "the score moved once");
        // the survivors come home once
        final long home = decided + data.war().army(army.id()).orElseThrow().legTravelTicks();
        CampaignService.update(data, home, WAR, CONTRACTS);
        final ArmyRecord done = data.war().army(army.id()).orElseThrow();
        assertEquals(ArmyRecord.Status.DISBANDED, done.status());
        assertEquals(survivors, done.returned());
        assertEquals(12 - army.strength() + survivors, garrison(data, A).strength());
        assertEquals(0, garrison(data, A).detached());
        data = PersistenceTestAccess.reload(data);
        CampaignService.update(data, home + 100L, WAR, CONTRACTS);
        assertEquals(12 - army.strength() + survivors, garrison(data, A).strength(), "never twice");
        // treasuries only move between the two factions (sack tribute), nothing is created
        assertEquals(treasuries, faction(data, FA).treasury() + faction(data, FB).treasury()
            + data.contracts().contracts().stream().mapToLong(Contract::reservedReward).sum());
    }

    @Test
    void physicalLossesFoldIntoTheSingleResolutionAndNeverApplyTwice()
    {
        final KingdomsSavedData data = armed(20, 6);
        activeWar(data, 0L);
        CampaignService.update(data, 100L, WAR, CONTRACTS);
        final ArmyRecord army = onlyArmy(data);
        final long arrival = 100L + army.fullTravelTicks();
        final BattleRecord battle = CampaignService.update(data, arrival, WAR, CONTRACTS).started().getFirst();
        // two guards and three soldiers fall in the physical fight; a player killed two of the soldiers
        assertTrue(CampaignService.defenderFell(data, battle));
        assertTrue(CampaignService.defenderFell(data, battle));
        assertTrue(CampaignService.attackerFell(data, battle, PLAYER));
        assertTrue(CampaignService.attackerFell(data, battle, PLAYER));
        assertTrue(CampaignService.attackerFell(data, battle, null));
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final BattleRecord reloaded = restarted.war().battle(battle.id()).orElseThrow();
        assertEquals(3, reloaded.attackerPhysicalLosses(), "recorded losses survive a restart");
        assertEquals(2, reloaded.defenderPhysicalLosses());
        final CampaignService.Resolution resolution = CampaignService.resolveBattle(restarted, reloaded, arrival + 10L, WAR, CONTRACTS);
        assertTrue(resolution.applied());
        assertTrue(reloaded.attackerLosses() >= 3 && reloaded.defenderLosses() >= 2, "recorded losses are part of the result, never added on top");
        assertTrue(reloaded.attackerLosses() <= reloaded.attackerStrength() && reloaded.defenderLosses() <= reloaded.defenderStrength());
        assertEquals(6 - reloaded.defenderLosses(), garrison(restarted, B).strength());
        assertFalse(CampaignService.attackerFell(restarted, reloaded, PLAYER), "a decided battle records nothing more");
        assertFalse(CampaignService.resolveBattle(restarted, reloaded, arrival + 20L, WAR, CONTRACTS).applied());
        assertEquals(6 - reloaded.defenderLosses(), garrison(restarted, B).strength());
        // the player who killed soldiers is judged once by the attacker's faction
        assertTrue(resolution.reputation().containsKey(PLAYER));
        assertTrue(ReputationService.standing(restarted, PLAYER, FA) < 0);
        // every besieger down physically decides the battle at once, for the defenders
        final KingdomsSavedData second = armed(20, 6);
        activeWar(second, 0L);
        CampaignService.update(second, 100L, WAR, CONTRACTS);
        final ArmyRecord other = onlyArmy(second);
        final BattleRecord fight = CampaignService.update(second, 100L + other.fullTravelTicks(), WAR, CONTRACTS).started().getFirst();
        for (int index = 0; index < fight.attackerStrength() + 5; index++) CampaignService.attackerFell(second, fight, PLAYER);
        assertEquals(fight.attackerStrength(), fight.attackerPhysicalLosses(), "bounded by the army");
        CampaignService.update(second, 100L + other.fullTravelTicks() + 100L, WAR, CONTRACTS);
        assertEquals(BattleRecord.Outcome.DEFENDER_VICTORY, fight.outcome());
        assertEquals(fight.attackerStrength(), fight.attackerLosses());
    }

    @Test
    void theDefenceContractIsPaidOnceToAHolderWhoFoughtAndFailsIfTheTownFalls()
    {
        final KingdomsSavedData data = armed(20, 10);
        activeWar(data, 0L);
        CampaignService.update(data, 100L, WAR, CONTRACTS);
        final ArmyRecord army = onlyArmy(data);
        final long arrival = 100L + army.fullTravelTicks();
        final BattleRecord battle = CampaignService.update(data, arrival, WAR, CONTRACTS).started().getFirst();
        final Contract defend = data.contracts().targeting(battle.id()).getFirst();
        assertTrue(ContractService.accept(data, PLAYER, defend, arrival + 10L, CONTRACTS).isEmpty());
        CampaignService.recordDefender(data, battle, OTHER);
        for (int index = 0; index < battle.attackerStrength(); index++) CampaignService.attackerFell(data, battle, PLAYER);
        final CampaignService.Update update = CampaignService.update(data, arrival + 100L, WAR, CONTRACTS);
        assertEquals(1, update.resolved().size());
        assertEquals(ContractStatus.COMPLETED, defend.status());
        assertTrue(defend.rewardPending(), "paid through the Phase 7 pending-reward path");
        assertEquals(CampaignService.DEFENDER_REPUTATION, ReputationService.standing(data, OTHER, FB), "a helper is thanked once");
        CampaignService.update(data, arrival + 200L, WAR, CONTRACTS);
        assertEquals(CampaignService.DEFENDER_REPUTATION, ReputationService.standing(data, OTHER, FB));
        // a holder whose town falls fails the contract
        final KingdomsSavedData lost = armed(40, 0);
        activeWar(lost, 0L);
        CampaignService.update(lost, 100L, WAR, CONTRACTS);
        final ArmyRecord big = onlyArmy(lost);
        final BattleRecord siege = CampaignService.update(lost, 100L + big.fullTravelTicks(), WAR, CONTRACTS).started().getFirst();
        final Contract doomed = lost.contracts().targeting(siege.id()).getFirst();
        assertTrue(ContractService.accept(lost, PLAYER, doomed, 100L + big.fullTravelTicks() + 10L, CONTRACTS).isEmpty());
        CampaignService.update(lost, siege.resolveAt(), WAR, CONTRACTS);
        assertEquals(BattleRecord.Outcome.ATTACKER_VICTORY, siege.outcome(), "no defenders: the town falls");
        assertEquals(ContractStatus.FAILED, doomed.status());
        assertTrue(lost.military().garrison(B).orElseThrow().vulnerableAt(siege.resolveAt() + 1L), "a sacked town is weaker for a while");
    }

    @Test
    void skirmishesCostTheArmyOnceAndADestroyedArmyStillSettlesItsGarrison()
    {
        final KingdomsSavedData data = armed(10, 4);
        activeWar(data, 0L);
        CampaignService.update(data, 100L, WAR, CONTRACTS);
        final ArmyRecord army = onlyArmy(data);
        final int size = army.strength();
        for (int index = 0; index < size + 3; index++) CampaignService.skirmishLoss(data, army);
        assertEquals(0, army.strength(), "bounded by the army");
        assertEquals(size, army.skirmishLosses());
        CampaignService.update(data, 200L, WAR, CONTRACTS);
        assertEquals(ArmyRecord.Status.DISBANDED, army.status(), "nobody left to march or walk home");
        assertEquals(0, army.returned());
        assertEquals(10 - size, garrison(data, A).strength());
        assertEquals(0, garrison(data, A).detached());
        assertTrue(data.war().battles().isEmpty());
    }

    @Test
    void endingAWarCallsOffItsSiegeWithoutLossesAndPeaceIsAppliedOnce()
    {
        final KingdomsSavedData data = armed(12, 4);
        final long treasuries = faction(data, FA).treasury() + faction(data, FB).treasury();
        final WarRecord war = activeWar(data, 0L);
        CampaignService.update(data, 100L, WAR, CONTRACTS);
        final ArmyRecord army = onlyArmy(data);
        final BattleRecord battle = CampaignService.update(data, 100L + army.fullTravelTicks(), WAR, CONTRACTS).started().getFirst();
        final Contract defend = data.contracts().targeting(battle.id()).getFirst();
        assertTrue(ContractService.accept(data, PLAYER, defend, 100L + army.fullTravelTicks() + 5L, CONTRACTS).isEmpty());
        final long end = 100L + army.fullTravelTicks() + 50L;
        assertTrue(WarService.end(data, war, WarRecord.Result.ATTACKER_VICTORY, end, WAR).isPresent());
        assertTrue(WarService.end(data, war, WarRecord.Result.DEFENDER_VICTORY, end, WAR).isEmpty(), "a war ends once");
        final int indemnity = war.tribute();
        assertTrue(indemnity > 0);
        CampaignService.update(data, end + 100L, WAR, CONTRACTS);
        assertEquals(BattleRecord.Status.CANCELLED, battle.status());
        assertEquals(0, battle.attackerLosses());
        assertEquals(4, garrison(data, B).strength(), "no losses from a called-off siege");
        assertEquals(ContractStatus.CANCELLED, defend.status());
        assertEquals(0, ReputationService.standing(data, PLAYER, FB), "cancelled without penalty");
        assertEquals(ArmyRecord.Status.RETURNING, army.status());
        final int relation = DiplomacyService.relation(faction(data, FA), faction(data, FB));
        assertTrue(relation >= DiplomaticStance.NEUTRAL.minimum(), "peace: trade can resume");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final WarRecord reloaded = restarted.war().war(war.id()).orElseThrow();
        WarService.evaluate(restarted, end + DAY, WAR);
        assertEquals(indemnity, reloaded.tribute());
        assertEquals(treasuries, faction(restarted, FA).treasury() + faction(restarted, FB).treasury(), "the indemnity only moves money");
        assertTrue(restarted.war().state().truceUntil(FA, FB) > end + DAY);
    }

    @Test
    void armiesFollowTheirPhysicalSquadWithoutRewindingOrOutrunningTheirSchedule()
    {
        final KingdomsSavedData data = armed(12, 4);
        activeWar(data, 0L);
        CampaignService.update(data, 0L, WAR, CONTRACTS);
        final ArmyRecord army = onlyArmy(data);
        final long half = army.fullTravelTicks() / 2;
        CampaignService.hold(data, army, 0.3D, half);
        assertEquals(0.3D, army.progressAt(half), 1.0E-9, "a slow squad slows its army");
        CampaignService.hold(data, army, 0.1D, half + 100L);
        assertEquals(0.3D, army.progressAt(half + 100L), 1.0E-9, "never back past the squad's last point");
        CampaignService.hold(data, army, 0.95D, half + 200L);
        assertTrue(army.progressAt(half + 200L) < 0.4D, "a squad projected far ahead never teleports its army");
        final double at = army.progressAt(half + 200L);
        final long rest = (long) Math.ceil(army.fullTravelTicks() * (1.0D - at));
        assertTrue(army.arrivedAt(half + 200L + rest));
        CampaignService.hold(data, army, Double.NaN, half + 300L);
        assertEquals(at, army.progressAt(half + 200L), 1.0E-9, "garbage is ignored");
        // a squad that is fighting holds its army where it stands
        CampaignService.hold(data, army, army.heldProgress(), half + 400L);
        final double held = army.progressAt(half + 400L);
        CampaignService.hold(data, army, army.heldProgress(), half + 1_400L);
        assertEquals(held, army.progressAt(half + 1_400L), 1.0E-9);
    }

    @Test
    void aSiegeWhoseArmyRecordIsLostIsCalledOffAndNothingIsDecidedAfterPeace()
    {
        final KingdomsSavedData data = armed(12, 4);
        final WarRecord war = activeWar(data, 0L);
        CampaignService.update(data, 100L, WAR, CONTRACTS);
        final ArmyRecord army = onlyArmy(data);
        final long arrival = 100L + army.fullTravelTicks();
        final BattleRecord battle = CampaignService.update(data, arrival, WAR, CONTRACTS).started().getFirst();
        final Contract defend = data.contracts().targeting(battle.id()).getFirst();
        assertTrue(ContractService.accept(data, PLAYER, defend, arrival + 5L, CONTRACTS).isEmpty());
        // the army record becomes unreadable: the siege must not stay open forever
        final net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag(), null);
        saved.getCompound("war").getList("armies", 10).getCompound(0).putString("status", "NOT_A_STATUS");
        final KingdomsSavedData damaged = PersistenceTestAccess.load(saved);
        assertTrue(damaged.war().army(army.id()).isEmpty());
        final CampaignService.Update update = CampaignService.update(damaged, arrival + 50L, WAR, CONTRACTS);
        assertEquals(1, update.cancelled().size());
        assertEquals(BattleRecord.Status.CANCELLED, damaged.war().battle(battle.id()).orElseThrow().status());
        final Contract reloaded = damaged.contracts().get(defend.id()).orElseThrow();
        assertEquals(ContractStatus.CANCELLED, reloaded.status(), "the holder is released without penalty");
        assertEquals(0, damaged.military().garrison(A).orElseThrow().detached(), "the lost army's soldiers are no longer counted away");
        // peace first, then an operator resolution: nothing is decided after peace
        final KingdomsSavedData second = armed(12, 4);
        final WarRecord other = activeWar(second, 0L);
        CampaignService.update(second, 100L, WAR, CONTRACTS);
        final ArmyRecord marching = onlyArmy(second);
        final BattleRecord siege = CampaignService.update(second, 100L + marching.fullTravelTicks(), WAR, CONTRACTS).started().getFirst();
        WarService.end(second, other, WarRecord.Result.WHITE_PEACE, 100L + marching.fullTravelTicks() + 10L, WAR);
        assertFalse(CampaignService.resolveBattle(second, siege, 100L + marching.fullTravelTicks() + 20L, WAR, CONTRACTS).applied());
        assertEquals(BattleRecord.Status.CANCELLED, siege.status());
        assertEquals(0, other.score(), "an ended war's score never moves");
        assertEquals(4, garrison(second, B).strength());
        assertNotNull(war);
    }

    @Test
    void aWarWhoseFactionIsGoneEndsInWhitePeace()
    {
        final KingdomsSavedData data = armed(12, 4);
        final WarRecord war = activeWar(data, 0L);
        data.removeFaction(FB);
        final WarService.Evaluation evaluation = WarService.evaluate(data, DAY, WAR);
        assertEquals(1, evaluation.ended().size());
        assertEquals(WarRecord.Result.WHITE_PEACE, war.result());
        assertTrue(WarService.openWarOf(data, FA).isEmpty(), "the surviving side is free again");
    }

    @Test
    void aWarThatCannotFieldAnArmyRetriesLaterNotEveryUpdate()
    {
        final KingdomsSavedData data = armed(3, 4);
        final WarRecord war = activeWar(data, 0L);
        CampaignService.update(data, 100L, WAR, CONTRACTS);
        assertTrue(data.war().armies().isEmpty(), "three soldiers cannot field an army of four");
        assertEquals(100L + CampaignService.RAISE_RETRY_TICKS, war.nextRaiseAt());
        MilitaryService.setStrength(data, A, 12);
        CampaignService.update(data, 200L, WAR, CONTRACTS);
        assertTrue(data.war().armies().isEmpty(), "waits for the retry time");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        CampaignService.update(restarted, 100L + CampaignService.RAISE_RETRY_TICKS, WAR, CONTRACTS);
        assertEquals(1, restarted.war().armies().size());
    }

    @Test
    void aGuardKilledByAMarchingArmyCostsItsGarrisonOnce()
    {
        final KingdomsSavedData data = armed(12, 6);
        final UUID guard = UUID.randomUUID();
        assertEquals(1, MilitaryService.skirmishLoss(data, B, guard, 100L));
        assertEquals(0, MilitaryService.skirmishLoss(data, B, guard, 110L), "one guard, one soldier, once");
        assertEquals(5, garrison(data, B).strength());
        assertEquals(1, MilitaryService.skirmishLoss(data, B, UUID.randomUUID(), 120L));
        assertEquals(4, PersistenceTestAccess.reload(data).military().garrison(B).orElseThrow().strength());
    }
}
