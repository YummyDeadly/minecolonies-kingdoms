package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.trade.ShipmentFailureReason;
import com.minecolonies.kingdoms.trade.TradeManager;
import com.minecolonies.kingdoms.trade.TradeSettings;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class EncounterResolutionTest
{
    private static final TradeSettings TRADE = new TradeSettings(4, 4096.0D, 1L, 1_000L, 100L, 1.0D, 3, 10.0D);

    private static long stock(final KingdomsSavedData data, final java.util.UUID colony)
    {
        return data.colony(colony).orElseThrow().economy().resource(EconomicResource.FOOD).stockpile().amount();
    }

    private static EncounterRules.Decision decision(final EncounterRules.Outcome outcome, final double fraction, final long delay)
    {
        return new EncounterRules.Decision(outcome, fraction, delay);
    }

    @Test
    void anAmbushActivatesWhenTheCaravanArrivesAndHoldsIt()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "held", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        final long before = (long) Math.floor(encounter.triggerProgress() * 4_000L) - 50L;
        assertTrue(EncounterService.activateDue(data, before, SETTINGS, CONTRACTS).isEmpty(), "not before the ambush point");
        activate(data, encounter, shipment);
        assertEquals(BanditEncounter.Status.ACTIVE, encounter.status());
        assertEquals(BanditEncounter.Representation.ABSTRACT, encounter.representation());
        final long now = encounter.activatedAt();
        assertTrue(shipment.heldAt(now + 10L), "the caravan waits at the ambush");
        final double held = shipment.progressAt(now + 100L);
        assertEquals(held, shipment.progressAt(now + 1_000L), 1.0E-9, "no progress while held");
        assertTrue(shipment.arrivalAt() >= encounter.resolveAt(), "arrival moved back");
    }

    @Test
    void banditVictoryTakesPartOfTheCargoExactlyOnce()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "robbed", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        activate(data, encounter, shipment);
        final long now = encounter.activatedAt() + 10L;
        final EncounterService.Resolution first = EncounterService.resolve(data, encounter, decision(EncounterRules.Outcome.PARTIAL_LOSS,
            0.4D, 0L), BanditEncounter.Cause.ABSTRACT_ROLL, List.of(), now, SETTINGS, CONTRACTS);
        assertTrue(first.applied());
        assertEquals(40L, first.cargoLost());
        assertEquals(BanditEncounter.Status.RESOLVED_BANDITS, encounter.status());
        assertEquals(40L, encounter.cargoLost());
        assertEquals(100L, encounter.cargoBefore());
        assertEquals(60L, shipment.deliverableAmount());
        assertEquals(TradeShipmentStatus.IN_TRANSIT, shipment.status(), "the rest travels on");
        assertFalse(shipment.heldAt(now + 1L), "released");
        assertEquals(1, data.bandits().threat(ROAD).orElseThrow().raids());

        final EncounterService.Resolution again = EncounterService.resolve(data, encounter, decision(EncounterRules.Outcome.TOTAL_LOSS,
            1.0D, 0L), BanditEncounter.Cause.CARAVAN_OVERRUN, List.of(), now + 5L, SETTINGS, CONTRACTS);
        assertFalse(again.applied(), "an encounter never resolves twice");
        assertFalse(shipment.recordBanditLoss(BanditFixtures.id("another"), 50L), "and a shipment never loses cargo twice");
        assertEquals(40L, shipment.lostAmount());
        assertEquals(1, data.bandits().threat(ROAD).orElseThrow().raids());

        final long destinationBefore = stock(data, B);
        final long originBefore = stock(data, A);
        TradeManager.getInstance().processShipments(data, shipment.arrivalAt() + 1L, TRADE);
        assertEquals(TradeShipmentStatus.DELIVERED, shipment.status());
        assertEquals(destinationBefore + 60L, stock(data, B), "only what bandits left reaches the destination");
        assertEquals(originBefore, stock(data, A), "the lost cargo is not refunded either");
    }

    @Test
    void caravanVictoriesDelaysAndTotalLosses()
    {
        final KingdomsSavedData won = world();
        final TradeShipment safe = shipment(won, "safe", 100, 0L, 4_000L);
        final BanditEncounter defended = ambush(won, safe, 0L);
        activate(won, defended, safe);
        final long now = defended.activatedAt() + 10L;
        EncounterService.resolve(won, defended, decision(EncounterRules.Outcome.BANDITS_DEFEATED, 0.0D, 0L),
            BanditEncounter.Cause.CARAVAN_GUARDS, List.of(), now, SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.RESOLVED_CARAVAN, defended.status());
        assertEquals(0L, safe.lostAmount());
        assertTrue(won.bandits().threat(ROAD).orElseThrow().suppressedAt(now + 1L), "a defeated band suppresses the road");

        final KingdomsSavedData late = world();
        final TradeShipment slow = shipment(late, "slow", 100, 0L, 4_000L);
        final BanditEncounter delaying = ambush(late, slow, 0L);
        activate(late, delaying, slow);
        final long at = delaying.activatedAt() + 10L;
        final long arrival = slow.arrivalAt();
        EncounterService.resolve(late, delaying, decision(EncounterRules.Outcome.CARAVAN_DELAYED, 0.0D, 2_000L),
            BanditEncounter.Cause.ABSTRACT_ROLL, List.of(), at, SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.RESOLVED_CARAVAN, delaying.status());
        assertEquals(2_000L, delaying.delayTicks());
        assertTrue(slow.heldAt(at + 1_000L) && !slow.heldAt(at + 2_001L));
        assertTrue(slow.arrivalAt() > arrival - (delaying.resolveAt() - at) + 1_900L, "the delay pushes arrival back");

        final KingdomsSavedData lost = world();
        final TradeShipment doomed = shipment(lost, "doomed", 100, 0L, 4_000L);
        final BanditEncounter raid = ambush(lost, doomed, 0L);
        activate(lost, raid, doomed);
        EncounterService.resolve(lost, raid, decision(EncounterRules.Outcome.TOTAL_LOSS, 1.0D, 0L), BanditEncounter.Cause.ABSTRACT_ROLL,
            List.of(), raid.activatedAt() + 10L, SETTINGS, CONTRACTS);
        assertEquals(TradeShipmentStatus.FAILED, doomed.status());
        assertEquals(ShipmentFailureReason.BANDIT_RAID, doomed.failureReason());
        final long destination = stock(lost, B);
        TradeManager.getInstance().processShipments(lost, 100_000L, TRADE);
        assertEquals(destination, stock(lost, B), "nothing arrives");
    }

    @Test
    void theSameEncounterResolvesIdenticallyAfterARestartAndNeverAgain()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "restart", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        activate(data, encounter, shipment);
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final long due = encounter.resolveAt();
        EncounterService.resolveDue(data, due, SETTINGS, CONTRACTS);
        EncounterService.resolveDue(restarted, due + 500L, SETTINGS, CONTRACTS);
        final BanditEncounter reloaded = restarted.bandits().encounter(encounter.id()).orElseThrow();
        assertEquals(encounter.outcome(), reloaded.outcome(), "restart timing never re-rolls the outcome");
        assertEquals(encounter.cargoLost(), reloaded.cargoLost());
        assertEquals(shipment.lostAmount(), restarted.tradeLedger().shipment(shipment.id()).orElseThrow().lostAmount());

        final KingdomsSavedData resolvedThenRestarted = PersistenceTestAccess.reload(data);
        final BanditEncounter settled = resolvedThenRestarted.bandits().encounter(encounter.id()).orElseThrow();
        assertTrue(settled.status().terminal());
        assertTrue(EncounterService.resolveDue(resolvedThenRestarted, due + 10_000L, SETTINGS, CONTRACTS).isEmpty(),
            "a resolved encounter is never resolved again after loading");
        assertEquals(shipment.lostAmount(), resolvedThenRestarted.tradeLedger().shipment(shipment.id()).orElseThrow().lostAmount());
        assertEquals(BanditEncounter.Representation.ABSTRACT, restarted.bandits().encounter(encounter.id()).orElseThrow().representation());
    }

    @Test
    void physicalAndAbstractPathsCannotBothResolve()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "fight", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        activate(data, encounter, shipment);
        encounter.representation(BanditEncounter.Representation.PHYSICAL);
        assertTrue(EncounterService.resolveDue(data, encounter.resolveAt() + 1L, SETTINGS, CONTRACTS).isEmpty(),
            "while bandits are physical, only the fight decides");
        for (int index = 0; index < encounter.strength(); index++) encounter.banditLost();
        encounter.defender(PLAYER);
        final EncounterService.Resolution won = EncounterService.resolve(data, encounter, decision(EncounterRules.Outcome.BANDITS_DEFEATED,
            0.0D, 0L), BanditEncounter.Cause.PLAYER_VICTORY, List.of(), encounter.resolveAt() + 2L, SETTINGS, CONTRACTS);
        assertTrue(won.applied());
        assertEquals(BanditEncounter.Status.RESOLVED_PLAYER, encounter.status());
        assertEquals(BanditEncounter.Representation.ABSTRACT, encounter.representation());
        assertFalse(EncounterService.resolve(data, encounter, decision(EncounterRules.Outcome.PARTIAL_LOSS, 0.5D, 0L),
            BanditEncounter.Cause.CARAVAN_OVERRUN, List.of(), encounter.resolveAt() + 3L, SETTINGS, CONTRACTS).applied(),
            "a caravan death after the victory changes nothing");
        assertTrue(EncounterService.resolveDue(data, encounter.resolveAt() + 100L, SETTINGS, CONTRACTS).isEmpty());
        assertEquals(0L, shipment.lostAmount());
        assertEquals(EncounterService.DEFENDER_REPUTATION, com.minecolonies.kingdoms.diplomacy.ReputationService.standing(data, PLAYER, FA),
            "the defender gains a little reputation with the caravan owner, once");
    }

    @Test
    void leavingDefersTheAbstractDecisionAndGoneShipmentsCancel()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "left", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        activate(data, encounter, shipment);
        final long late = encounter.resolveAt() + 1_000L;
        encounter.representation(BanditEncounter.Representation.PHYSICAL);
        encounter.representation(BanditEncounter.Representation.ABSTRACT);
        encounter.deferResolution(late, SETTINGS.abstractResolveTicks() / 2);
        assertTrue(EncounterService.resolveDue(data, late + 1L, SETTINGS, CONTRACTS).isEmpty(),
            "unloading a fight does not decide it on the spot");
        assertEquals(1, EncounterService.resolveDue(data, late + SETTINGS.abstractResolveTicks() / 2, SETTINGS, CONTRACTS).size());

        final KingdomsSavedData gone = world();
        final TradeShipment vanished = shipment(gone, "vanished", 100, 0L, 4_000L);
        final BanditEncounter orphan = ambush(gone, vanished, 0L);
        vanished.fail(ShipmentFailureReason.ROUTE_REMOVED, "test");
        EncounterService.activateDue(gone, 100L, SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.CANCELLED, orphan.status());
        assertEquals(BanditEncounter.Cause.SHIPMENT_GONE, orphan.cause());
    }

    @Test
    void roadblocksExpireOrAreClearedWithoutTouchingShipments()
    {
        final KingdomsSavedData data = world();
        final var road = data.roads().get(ROAD).orElseThrow();
        final BanditEncounter expired = EncounterService.createRoadblock(data, road, anchors(data), 0L, SETTINGS, true).orElseThrow();
        EncounterService.resolveDue(data, SETTINGS.roadblockLifetimeTicks(), SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.EXPIRED, expired.status());
        final BanditEncounter cleared = EncounterService.createRoadblock(data, road, anchors(data), 30_000L, SETTINGS, true).orElseThrow();
        assertNotEquals(expired.id(), cleared.id(), "each roadblock has its own identity");
        cleared.defender(PLAYER);
        EncounterService.resolve(data, cleared, decision(EncounterRules.Outcome.BANDITS_DEFEATED, 0.0D, 0L),
            BanditEncounter.Cause.PLAYER_VICTORY, List.of(), 30_100L, SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.RESOLVED_PLAYER, cleared.status());
        assertTrue(data.bandits().threat(ROAD).orElseThrow().suppressedAt(30_200L));
        final java.util.UUID nearest = EncounterService.beneficiaryFaction(data, cleared);
        assertTrue(nearest.equals(cleared.position().getX() <= 800 ? FA : FB), "the settlement nearest to the roadblock benefits");
        assertEquals(EncounterService.DEFENDER_REPUTATION, com.minecolonies.kingdoms.diplomacy.ReputationService.standing(data, PLAYER, nearest));
    }
}
