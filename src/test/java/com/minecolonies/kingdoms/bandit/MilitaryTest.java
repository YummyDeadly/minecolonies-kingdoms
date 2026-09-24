package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.military.GarrisonRecord;
import com.minecolonies.kingdoms.military.GuardBudget;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecurityRules;
import com.minecolonies.kingdoms.military.SecuritySettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 9: security, garrisons, patrols, casualties, and guard caps. Lives in the bandit package to use its fixtures. */
class MilitaryTest
{
    private static final SecuritySettings SECURITY = SecuritySettings.defaults();
    private static final long DAY = 24_000L;

    private static GarrisonRecord garrison(final KingdomsSavedData data, final java.util.UUID settlement)
    {
        return data.military().garrison(settlement).orElseThrow();
    }

    private static void evaluate(final KingdomsSavedData data, final long gameTime)
    {
        MilitaryService.evaluate(data, gameTime, SECURITY, SETTINGS, CONTRACTS);
    }

    // ------------------------------------------------------------------------------------------------ security model

    @Test
    void securityGrowsWithArchetypeGarrisonCivicAndDefencesAndFallsWithDanger()
    {
        final SecurityRules.Breakdown village = SecurityRules.security(SettlementType.VILLAGE, 2, 4, 0, 0, 0.0D, false);
        final SecurityRules.Breakdown castle = SecurityRules.security(SettlementType.CASTLE, 10, 10, 2, 0, 0.0D, false);
        assertTrue(castle.total() > village.total());
        assertTrue(SecurityRules.security(SettlementType.TOWN, 4, 4, 0, 0, 0.0D, false).total()
            > SecurityRules.security(SettlementType.TOWN, 1, 4, 0, 0, 0.0D, false).total(), "soldiers at home matter");
        assertTrue(SecurityRules.security(SettlementType.TOWN, 4, 4, 0, 3, 0.0D, false).total()
            > SecurityRules.security(SettlementType.TOWN, 4, 4, 0, 0, 0.0D, false).total(), "recent defences matter");
        assertTrue(SecurityRules.security(SettlementType.TOWN, 4, 4, 0, 0, 80.0D, false).total()
            < SecurityRules.security(SettlementType.TOWN, 4, 4, 0, 0, 0.0D, false).total(), "dangerous roads lower security");
        assertTrue(SecurityRules.security(SettlementType.TOWN, 4, 4, 0, 0, 0.0D, true).total()
            < SecurityRules.security(SettlementType.TOWN, 4, 4, 0, 0, 0.0D, false).total(), "a sacked town is weaker");
        for (int strength = 0; strength <= 50; strength += 5)
        {
            final int total = SecurityRules.security(SettlementType.CASTLE, strength, 10, 5, 10, 0.0D, false).total();
            assertTrue(total >= 0 && total <= 100);
            assertTrue(SecurityRules.level(total) >= 0 && SecurityRules.level(total) <= 5);
        }
        assertEquals(0, SecurityRules.capacity(SettlementType.CASTLE, 0, 2), "no people, no soldiers");
        assertTrue(SecurityRules.capacity(SettlementType.CASTLE, 60, 2) > SecurityRules.capacity(SettlementType.VILLAGE, 60, 0));
        assertTrue(SecurityRules.capacity(SettlementType.FORT, 9, 2) <= 3, "never more than a third of the people");
    }

    @Test
    void garrisonsStartFromTheColonyAndRecruitTowardsCapacityOverDays()
    {
        final KingdomsSavedData data = world();
        evaluate(data, 0L);
        final GarrisonRecord village = garrison(data, A);
        assertEquals(2, village.strength(), "seeded from the colony's soldiers");
        final int capacity = village.capacity();
        assertTrue(capacity >= 2);
        evaluate(data, DAY / 2);
        assertEquals(2, village.strength(), "half a day recruits nobody yet");
        for (long time = DAY; time <= 30 * DAY; time += DAY) evaluate(data, time);
        assertEquals(capacity, village.strength(), "recruitment stops at capacity");
        assertTrue(village.recruited() > 0);
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        assertEquals(capacity, garrison(restarted, A).strength());
        assertEquals(village.security(), garrison(restarted, A).security());
    }

    @Test
    void roadSecurityComesFromGarrisonsAndFeedsTheThreatModel()
    {
        final KingdomsSavedData data = world();
        assertEquals(ThreatRules.security(SettlementType.VILLAGE), MilitaryService.level(data, A), "before evaluation: the Phase 8 archetype value");
        evaluate(data, 0L);
        final int before = MilitaryService.level(data, A) + MilitaryService.level(data, B);
        ThreatEvaluator.evaluate(data, value -> 1_000.0D, anchors(data), 0L, SETTINGS, CONTRACTS);
        final double withGarrisons = data.bandits().threatFor(ROAD).lastContributors().security();
        assertEquals(Math.min(40.0D, 8.0D * before), withGarrisons);
        MilitaryService.setStrength(data, A, 0);
        MilitaryService.setStrength(data, B, 0);
        evaluate(data, 10L);
        ThreatEvaluator.evaluate(data, value -> 1_000.0D, anchors(data), 1_210L, SETTINGS, CONTRACTS);
        assertTrue(data.bandits().threatFor(ROAD).lastContributors().security() < withGarrisons, "empty barracks, more danger");
    }

    // ------------------------------------------------------------------------------------------------ patrols

    @Test
    void aPatrolClearsANearbyCampOnceWithCasualtiesAppliedOnce()
    {
        final KingdomsSavedData data = world();
        evaluate(data, 0L);
        MilitaryService.setStrength(data, B, 30);
        final BanditCamp camp = CampService.establish(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 10L, SETTINGS, true).orElseThrow();
        SecurityContracts.post(data, 20L, SETTINGS, CONTRACTS);
        final Contract clear = data.contracts().targeting(camp.encounterId()).getFirst();
        assertEquals(ContractStatus.OFFERED, clear.status(), "an offer nobody accepted does not reserve the camp");
        final SecuritySettings wide = new SecuritySettings(true, 2_400, true, 96, 128, 4, 24, 12, 320, 3, 5_000, 0L, 4, 1.0D);
        int attempts = 0;
        long time = DAY;
        while (camp.active() && attempts++ < 20)
        {
            MilitaryService.evaluate(data, time, wide, SETTINGS, CONTRACTS);
            time += DAY;
        }
        assertEquals(BanditCamp.Status.CLEARED, camp.status(), "a strong garrison clears the camp within a few patrols");
        final BanditEncounter fight = data.bandits().encounter(camp.encounterId()).orElseThrow();
        assertEquals(BanditEncounter.Status.RESOLVED_GARRISON, fight.status());
        assertEquals(BanditEncounter.Cause.GARRISON_PATROL, fight.cause());
        assertEquals(ContractStatus.CANCELLED, clear.status(), "the open offer is withdrawn");
        assertEquals(Contract.CloseReason.OBJECTIVE_GONE, clear.closeReason());
        final int strength = garrison(data, B).strength();
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        MilitaryService.onEncounterEnded(restarted, restarted.bandits().encounter(camp.encounterId()).orElseThrow(), time + 1L);
        assertEquals(strength, garrison(restarted, B).strength(), "the patrol's losses never apply twice");
        assertTrue(garrison(data, B).recentDefences() > 0 || garrison(data, A).recentDefences() > 0, "a won patrol counts as a defence");
    }

    @Test
    void patrolsAreDeterministicAndNeverTouchAFightPlayersAreIn()
    {
        final SecurityRules.Sortie first = SecurityRules.sortie(SecurityRules.sortieSeed(ROAD, A, 1), 8, 4, 40);
        assertEquals(first, SecurityRules.sortie(SecurityRules.sortieSeed(ROAD, A, 1), 8, 4, 40), "same attempt, same outcome");
        final KingdomsSavedData data = world();
        evaluate(data, 0L);
        MilitaryService.setStrength(data, B, 30);
        final BanditCamp camp = CampService.establish(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 10L, SETTINGS, true).orElseThrow();
        data.bandits().encounter(camp.encounterId()).orElseThrow().representation(BanditEncounter.Representation.PHYSICAL);
        final SecuritySettings wide = new SecuritySettings(true, 2_400, true, 96, 128, 4, 24, 12, 320, 3, 5_000, 0L, 4, 1.0D);
        MilitaryService.evaluate(data, DAY, wide, SETTINGS, CONTRACTS);
        assertTrue(camp.active(), "players are fighting it: no patrol");
        assertEquals(0, garrison(data, B).sorties());
    }

    @Test
    void patrolsLeaveFightsThatPlayersHaveAClaimOn()
    {
        final SecuritySettings wide = new SecuritySettings(true, 2_400, true, 96, 128, 4, 24, 12, 320, 3, 5_000, 0L, 4, 1.0D);
        // an accepted contract reserves the camp for its holder
        final KingdomsSavedData reserved = world();
        evaluate(reserved, 0L);
        MilitaryService.setStrength(reserved, B, 30);
        final BanditCamp first = CampService.establish(reserved, reserved.roads().get(ROAD).orElseThrow(), anchors(reserved), 10L, SETTINGS, true)
            .orElseThrow();
        SecurityContracts.post(reserved, 20L, SETTINGS, CONTRACTS);
        final Contract clear = reserved.contracts().targeting(first.encounterId()).getFirst();
        assertTrue(ContractService.accept(reserved, PLAYER, clear, 30L, CONTRACTS).isEmpty());
        for (long time = DAY; time <= 5 * DAY; time += DAY) MilitaryService.evaluate(reserved, time, wide, SETTINGS, CONTRACTS);
        assertTrue(first.active(), "the holder keeps the camp");
        assertEquals(0, garrison(reserved, B).sorties());
        assertEquals(ContractStatus.ACCEPTED, clear.status());
        // players fought it (then died, logged out, or the fight stalled): a patrol never takes it over
        final KingdomsSavedData fought = world();
        evaluate(fought, 0L);
        MilitaryService.setStrength(fought, B, 30);
        final BanditCamp second = CampService.establish(fought, fought.roads().get(ROAD).orElseThrow(), anchors(fought), 10L, SETTINGS, true)
            .orElseThrow();
        EncounterService.recordDefender(fought, fought.bandits().encounter(second.encounterId()).orElseThrow(), PLAYER);
        MilitaryService.evaluate(fought, DAY, wide, SETTINGS, CONTRACTS);
        assertTrue(second.active());
        assertEquals(0, garrison(fought, B).sorties());
        // the physical world has a stake in it (a player nearby, bandits held or suppressed after a stall)
        final KingdomsSavedData watched = world();
        evaluate(watched, 0L);
        MilitaryService.setStrength(watched, B, 30);
        final BanditCamp third = CampService.establish(watched, watched.roads().get(ROAD).orElseThrow(), anchors(watched), 10L, SETTINGS, true)
            .orElseThrow();
        MilitaryService.evaluate(watched, DAY, wide, SETTINGS, CONTRACTS, encounter -> encounter.id().equals(third.encounterId()));
        assertTrue(third.active());
        assertEquals(0, garrison(watched, B).sorties());
        // bandits switched off: patrols pause too
        final BanditSettings frozen = new BanditSettings(false, SETTINGS.evaluationIntervalTicks(), SETTINGS.materializationRadius(),
            SETTINGS.dematerializationRadius(), SETTINGS.maxBanditsPerEncounter(), SETTINGS.maxPhysicalBanditsGlobal(),
            SETTINGS.maxPhysicalBanditsPerPlayer(), SETTINGS.settlementExclusionRadius(), SETTINGS.baseThreat(), SETTINGS.threatStep(),
            SETTINGS.encounterCooldownTicks(), SETTINGS.ambushChanceAtMaxThreat(), SETTINGS.abstractResolveTicks(), SETTINGS.roadblockThreshold(),
            SETTINGS.roadblockLifetimeTicks(), SETTINGS.suppressionTicks(), SETTINGS.maxActiveEncounters(), SETTINGS.camps());
        MilitaryService.evaluate(watched, 2 * DAY, wide, frozen, CONTRACTS);
        assertTrue(third.active());
        assertEquals(0, garrison(watched, B).sorties());
        // nothing claims it: the patrol goes
        MilitaryService.evaluate(watched, 3 * DAY, wide, SETTINGS, CONTRACTS);
        assertEquals(1, garrison(watched, B).sorties());
    }

    @Test
    void recordedGarrisonLossesAreBoundedAndReportedAsRecorded()
    {
        final KingdomsSavedData data = world();
        evaluate(data, 0L);
        final BanditEncounter roadblock = EncounterService.createRoadblock(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 0L,
            SETTINGS, true).orElseThrow();
        assertEquals(3, EncounterService.recordGarrisonDefence(data, roadblock, A, 3));
        assertEquals(13, EncounterService.recordGarrisonDefence(data, roadblock, A, 40), "capped per garrison and encounter");
        assertEquals(0, EncounterService.recordGarrisonDefence(data, roadblock, A, 1));
        assertFalse(EncounterService.recordGarrisonLoss(data, roadblock, A), "nothing beyond the cap is recorded");
        assertEquals(16, PersistenceTestAccess.reload(data).bandits().encounter(roadblock.id()).orElseThrow().garrisonLosses().get(A));
    }

    @Test
    void corruptOrPartialGarrisonDataLoadsSafely()
    {
        final net.minecraft.nbt.CompoundTag entry = new net.minecraft.nbt.CompoundTag();
        entry.putUUID("settlement", A);
        entry.putInt("strength", -5);
        entry.putInt("capacity", 4);
        entry.putDouble("recruitProgress", Double.NaN);
        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(entry);
        final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.put("garrisons", list);
        final GarrisonRecord loaded = com.minecolonies.kingdoms.military.MilitaryRegistry.load(tag).garrison(A).orElseThrow();
        assertEquals(0, loaded.strength());
        assertEquals(0.0D, loaded.recruitProgress());
        assertEquals(-1L, loaded.lastEvaluatedAt(), "a garrison without an evaluation time was never evaluated");
        assertEquals(-1L, loaded.lastDefenceAt());
    }

    // ------------------------------------------------------------------------------------------------ casualties

    @Test
    void onlyAnAuthoritativeResolutionCostsSoldiersAndOnlyOnce()
    {
        final KingdomsSavedData data = world();
        evaluate(data, 0L);
        MilitaryService.setStrength(data, A, 10);
        final BanditEncounter roadblock = EncounterService.createRoadblock(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 0L,
            SETTINGS, true).orElseThrow();
        // guards dematerialize, unload, or die to a mob: nothing is recorded, so nothing is lost
        assertEquals(10, garrison(data, A).strength());
        // two responders are killed by the bandits: recorded on the encounter, applied when it ends
        EncounterService.recordGarrisonLoss(data, roadblock, A);
        EncounterService.recordGarrisonLoss(data, roadblock, A);
        assertEquals(10, garrison(data, A).strength(), "not before the encounter ends");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final BanditEncounter reloaded = restarted.bandits().encounter(roadblock.id()).orElseThrow();
        assertEquals(2, reloaded.garrisonLosses().get(A), "recorded losses survive a restart");
        EncounterService.recordGarrisonDefender(restarted, reloaded, A);
        while (reloaded.remainingStrength() > 0) reloaded.banditLost();
        EncounterService.resolveDue(restarted, 100L, SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.RESOLVED_GARRISON, reloaded.status(), "the guards won");
        assertEquals(8, garrison(restarted, A).strength(), "two soldiers lost, once");
        MilitaryService.onEncounterEnded(restarted, reloaded, 200L);
        assertEquals(8, garrison(restarted, A).strength(), "never twice");
    }

    // ------------------------------------------------------------------------------------------------ guard budget

    @Test
    void guardCapsHoldForManyPlayersAndSettlements()
    {
        assertEquals(0, GuardBudget.patrolTarget(0, 4));
        assertEquals(1, GuardBudget.patrolTarget(1, 4));
        assertEquals(3, GuardBudget.patrolTarget(8, 4));
        assertEquals(4, GuardBudget.patrolTarget(100, 4), "per-settlement cap");
        assertEquals(0, GuardBudget.responders(1, 3), "one soldier stays home");
        assertEquals(3, GuardBudget.responders(30, 3));
        assertEquals(0, GuardBudget.allowance(4, 24, 24, 0, 12), "global cap");
        assertEquals(0, GuardBudget.allowance(4, 10, 24, 12, 12), "per-player cap");
        assertEquals(2, GuardBudget.allowance(2, 10, 24, 3, 12));
        assertTrue(GuardBudget.active(false, 90.0D, SECURITY));
        assertFalse(GuardBudget.active(false, 100.0D, SECURITY));
        assertTrue(GuardBudget.active(true, 120.0D, SECURITY), "hysteresis keeps them until the outer radius");
        assertFalse(GuardBudget.active(true, 130.0D, SECURITY));
    }
}
