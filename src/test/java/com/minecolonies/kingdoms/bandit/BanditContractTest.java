package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractRules;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.trade.TradeShipment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BanditContractTest
{
    /** Emerald sink that records payments. */
    private static final class Wallet implements ContractService.InventoryPort
    {
        int emeralds;
        @Override public List<ContractRules.Slot> slots(final EconomicResource resource) { return List.of(); }
        @Override public Runnable remove(final List<ContractRules.Removal> removals) { return () -> { }; }
        @Override public void give(final int amount) { emeralds += amount; }
    }

    private static Contract escortFor(final KingdomsSavedData data, final BanditEncounter encounter)
    {
        SecurityContracts.post(data, 10L, SETTINGS, CONTRACTS);
        final List<Contract> targeting = data.contracts().targeting(encounter.id());
        assertEquals(1, targeting.size());
        return targeting.getFirst();
    }

    private static EncounterService.Resolution defeat(final KingdomsSavedData data, final BanditEncounter encounter, final long at,
        final java.util.UUID... defenders)
    {
        for (final java.util.UUID defender : defenders) encounter.defender(defender);
        return EncounterService.resolve(data, encounter, new EncounterRules.Decision(EncounterRules.Outcome.BANDITS_DEFEATED, 0.0D, 0L),
            defenders.length == 0 ? BanditEncounter.Cause.CARAVAN_GUARDS : BanditEncounter.Cause.PLAYER_VICTORY, List.of(), at, SETTINGS, CONTRACTS);
    }

    @Test
    void escortOffersComeFromRealAmbushesOnlyOnce()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "escorted", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        final Contract escort = escortFor(data, encounter);
        assertEquals(Contract.Kind.ESCORT_CARAVAN, escort.kind());
        assertEquals(A, escort.settlementId(), "the caravan owner posts the escort");
        assertEquals(shipment.id(), escort.objective().targetShipment());
        assertEquals(encounter.position(), escort.objective().targetPosition());
        assertEquals(SecurityContracts.escortReward(encounter.strength()), escort.objective().baseReward());
        assertTrue(SecurityContracts.post(data, 20L, SETTINGS, CONTRACTS).isEmpty(), "one contract per encounter");
        assertTrue(data.contracts().offers(A).contains(escort), "shown on the settlement's board with its deliveries");
        final KingdomsSavedData quiet = world();
        assertTrue(SecurityContracts.post(quiet, 10L, SETTINGS, CONTRACTS).isEmpty(), "no threat, no security work");
    }

    @Test
    void completingAnEscortPaysAndRewardsExactlyOnce()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "paid", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        final Contract escort = escortFor(data, encounter);
        final long treasury = data.faction(FA).orElseThrow().treasury();
        assertTrue(ContractService.accept(data, PLAYER, escort, 20L, CONTRACTS).isEmpty());
        assertEquals(treasury - escort.reservedReward(), data.faction(FA).orElseThrow().treasury(), "reserved on acceptance");
        final Wallet wallet = new Wallet();
        assertEquals(ContractService.Refusal.NOT_A_DELIVERY,
            ContractService.deliver(data, PLAYER, escort, wallet, 30L, CONTRACTS).refusal().orElseThrow());
        activate(data, encounter, shipment);

        final EncounterService.Resolution won = defeat(data, encounter, encounter.activatedAt() + 20L, PLAYER, OTHER);
        assertEquals(ContractStatus.COMPLETED, escort.status());
        assertTrue(escort.rewardPending(), "the reward waits for the holder");
        assertEquals(SecurityContracts.ESCORT_REPUTATION, ReputationService.standing(data, PLAYER, FA), "contract reputation, not the +2");
        assertEquals(EncounterService.DEFENDER_REPUTATION, ReputationService.standing(data, OTHER, FA), "a helper without a contract gets +2");
        assertEquals(1, won.reputation().size());

        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        assertEquals(escort.agreedReward(), ContractService.claimPendingRewards(restarted, PLAYER, wallet, 5_000L));
        assertEquals(0, ContractService.claimPendingRewards(restarted, PLAYER, wallet, 5_001L), "paid exactly once");
        assertEquals(escort.agreedReward(), wallet.emeralds);
        final BanditEncounter reloaded = restarted.bandits().encounter(encounter.id()).orElseThrow();
        assertFalse(defeat(restarted, reloaded, 6_000L, PLAYER).applied(), "resolving again after a restart changes nothing");
        assertEquals(SecurityContracts.ESCORT_REPUTATION, ReputationService.standing(restarted, PLAYER, FA));
        assertEquals(0L, restarted.tradeLedger().shipment(shipment.id()).orElseThrow().lostAmount());
    }

    @Test
    void escortsFailWhenRobbedAndAreCancelledWhenSomeoneElseWins()
    {
        final KingdomsSavedData robbed = world();
        final TradeShipment lost = shipment(robbed, "lost", 100, 0L, 4_000L);
        final BanditEncounter raid = ambush(robbed, lost, 0L);
        final Contract failed = escortFor(robbed, raid);
        ContractService.accept(robbed, PLAYER, failed, 20L, CONTRACTS);
        final long money = robbed.faction(FA).orElseThrow().treasury() + failed.reservedReward();
        activate(robbed, raid, lost);
        EncounterService.resolve(robbed, raid, new EncounterRules.Decision(EncounterRules.Outcome.PARTIAL_LOSS, 0.5D, 0L),
            BanditEncounter.Cause.ABSTRACT_ROLL, List.of(), raid.activatedAt() + 20L, SETTINGS, CONTRACTS);
        assertEquals(ContractStatus.FAILED, failed.status());
        assertEquals(-CONTRACTS.failPenalty(), ReputationService.standing(robbed, PLAYER, FA));
        assertEquals(money, robbed.faction(FA).orElseThrow().treasury(), "the reservation returns to the treasury");

        final KingdomsSavedData others = world();
        final TradeShipment saved = shipment(others, "saved", 100, 0L, 4_000L);
        final BanditEncounter fight = ambush(others, saved, 0L);
        final Contract cancelled = escortFor(others, fight);
        ContractService.accept(others, PLAYER, cancelled, 20L, CONTRACTS);
        activate(others, fight, saved);
        defeat(others, fight, fight.activatedAt() + 20L, OTHER);
        assertEquals(ContractStatus.CANCELLED, cancelled.status());
        assertEquals(Contract.CloseReason.OBJECTIVE_GONE, cancelled.closeReason());
        assertEquals(0, ReputationService.standing(others, PLAYER, FA), "no penalty when the objective disappears");
        assertEquals(0, cancelled.reservedReward());
    }

    @Test
    void roadblocksPostClearContractsAtTheNearestSettlement()
    {
        final KingdomsSavedData data = world();
        final BanditEncounter roadblock = EncounterService.createRoadblock(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 0L,
            SETTINGS, true).orElseThrow();
        SecurityContracts.post(data, 10L, SETTINGS, CONTRACTS);
        final Contract clear = data.contracts().targeting(roadblock.id()).getFirst();
        assertEquals(Contract.Kind.CLEAR_BANDITS, clear.kind());
        assertEquals(roadblock.position().getX() <= 800 ? A : B, clear.settlementId(), "ties go to the first endpoint");
        assertEquals(roadblock.remainingStrength(), clear.amount());
        ContractService.accept(data, PLAYER, clear, 20L, CONTRACTS);
        defeat(data, roadblock, 100L, PLAYER);
        assertEquals(ContractStatus.COMPLETED, clear.status());
        final Wallet wallet = new Wallet();
        assertEquals(clear.agreedReward(), ContractService.claimPendingRewards(data, PLAYER, wallet, 200L));

        final KingdomsSavedData expiring = world();
        final BanditEncounter gone = EncounterService.createRoadblock(expiring, expiring.roads().get(ROAD).orElseThrow(), anchors(expiring), 0L,
            SETTINGS, true).orElseThrow();
        SecurityContracts.post(expiring, 10L, SETTINGS, CONTRACTS);
        final Contract offer = expiring.contracts().targeting(gone.id()).getFirst();
        EncounterService.resolveDue(expiring, SETTINGS.roadblockLifetimeTicks() + 1L, SETTINGS, CONTRACTS);
        assertEquals(ContractStatus.CANCELLED, offer.status(), "an expired roadblock withdraws its offer");
    }

    @Test
    void anExpiredRoadblockFailsTheAcceptedClearContract()
    {
        final KingdomsSavedData data = world();
        final BanditEncounter roadblock = EncounterService.createRoadblock(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 0L,
            SETTINGS, true).orElseThrow();
        SecurityContracts.post(data, 10L, SETTINGS, CONTRACTS);
        final Contract clear = data.contracts().targeting(roadblock.id()).getFirst();
        final java.util.UUID issuerFaction = clear.factionId();
        assertTrue(ContractService.accept(data, PLAYER, clear, 20L, CONTRACTS).isEmpty());
        final long reserved = data.faction(issuerFaction).orElseThrow().treasury() + clear.reservedReward();
        final List<EncounterService.Resolution> expired = EncounterService.resolveDue(data, roadblock.expiresAt(), SETTINGS, CONTRACTS);
        assertEquals(1, expired.size());
        assertEquals(BanditEncounter.Status.EXPIRED, roadblock.status());
        assertEquals(ContractStatus.FAILED, clear.status(), "the road was not cleared in time");
        assertEquals(-CONTRACTS.failPenalty(), ReputationService.standing(data, PLAYER, issuerFaction));
        assertEquals(reserved, data.faction(issuerFaction).orElseThrow().treasury(), "the reservation goes back");
        assertTrue(EncounterService.resolveDue(data, roadblock.expiresAt() + 100L, SETTINGS, CONTRACTS).isEmpty());
        assertEquals(-CONTRACTS.failPenalty(), ReputationService.standing(data, PLAYER, issuerFaction), "once");
    }

    @Test
    void securityContractsAreDecidedByTheirEncounterNotByTheClock()
    {
        final ContractSettings quick = new ContractSettings(true, 3, 3, 48_000L, 100L, 2_400L, 12_000L, 24_000L, 96, 6, 4, 10,
            168_000L, 256);
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "slow", 100, 0L, 40_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        final Contract escort = escortFor(data, encounter);
        assertTrue(ContractService.accept(data, PLAYER, escort, 20L, quick).isEmpty());
        ContractService.sweep(data, 5_000L, quick);
        assertEquals(ContractStatus.ACCEPTED, escort.status(), "past the generic deadline, but the caravan has not reached the bandits");
        assertEquals(0, ReputationService.standing(data, PLAYER, FA));

        // the encounter disappears without closing its contract (e.g. an unreadable record): cancelled, never a penalty
        final net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag(), null);
        saved.getCompound("bandits").remove("encounters");
        final KingdomsSavedData broken = PersistenceTestAccess.load(saved);
        final Contract orphan = broken.contracts().contracts().stream().filter(value -> value.id().equals(escort.id())).findFirst().orElseThrow();
        ContractService.sweep(broken, 5_100L, quick);
        assertEquals(ContractStatus.CANCELLED, orphan.status());
        assertEquals(Contract.CloseReason.OBJECTIVE_GONE, orphan.closeReason());
        assertEquals(0, ReputationService.standing(broken, PLAYER, FA));
        assertEquals(0, orphan.reservedReward(), "reservation returned");
    }

    @Test
    void staleAmbushesEndAndTheirOffersWithThem()
    {
        final KingdomsSavedData data = world();
        final TradeShipment stuck = shipment(data, "stuck", 100, 0L, 4_000L);
        final BanditEncounter planned = ambush(data, stuck, 0L);
        final Contract escort = escortFor(data, planned);
        assertTrue(EncounterService.cancelStale(data, planned.expiresAt() - 1L, CONTRACTS).isEmpty(), "not before its lifetime");
        // pretend the caravan never reaches the ambush point (a stuck physical caravan): the lifetime still ends it
        stuck.materialize(0L);
        final List<EncounterService.Resolution> ended = EncounterService.cancelStale(data, planned.expiresAt(), CONTRACTS);
        assertEquals(1, ended.size());
        assertEquals(BanditEncounter.Status.EXPIRED, planned.status());
        assertEquals(BanditEncounter.Cause.LIFETIME_OVER, planned.cause());
        assertEquals(ContractStatus.CANCELLED, escort.status());
        assertEquals(0L, stuck.lostAmount(), "an ambush that never happened takes nothing");

        final KingdomsSavedData other = world();
        final TradeShipment gone = shipment(other, "gone", 100, 0L, 4_000L);
        final BanditEncounter orphaned = ambush(other, gone, 0L);
        gone.fail(com.minecolonies.kingdoms.trade.ShipmentFailureReason.TECHNICAL_FAILURE, "test");
        final List<EncounterService.Resolution> cancelled = EncounterService.cancelStale(other, 10L, CONTRACTS);
        assertEquals(1, cancelled.size());
        assertEquals(BanditEncounter.Cause.SHIPMENT_GONE, orphaned.cause());
        assertTrue(EncounterService.cancelStale(other, 20L, CONTRACTS).isEmpty(), "once");
    }
}
