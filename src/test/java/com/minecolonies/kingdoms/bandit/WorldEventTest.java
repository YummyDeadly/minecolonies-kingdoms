package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.colony.ColonyRemoval;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecuritySettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.war.WarRecord;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.war.WarSettings;
import com.minecolonies.kingdoms.worldevent.WorldEventRecord;
import com.minecolonies.kingdoms.worldevent.WorldEventService;
import com.minecolonies.kingdoms.worldevent.WorldEventSettings;
import com.minecolonies.kingdoms.worldevent.WorldEventType;
import com.minecolonies.kingdoms.worldevent.WorldHistory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 11: world events act once, only through their owning authorities, and are bounded, seeded, and restart-safe. */
class WorldEventTest
{
    private static final WorldEventSettings EVENTS = WorldEventSettings.defaults();
    private static final EconomyManager ECONOMY = new EconomyManager();
    private static final double EPSILON = 1.0E-9D;

    private static KingdomsSavedData farming()
    {
        final KingdomsSavedData data = world();
        data.worldEvents().seedIfUnset(20260924L);
        for (final UUID id : List.of(A, B))
        {
            final NPCColonyData colony = colony(data, id);
            ECONOMY.setStockpile(colony, EconomicResource.FOOD, 1_000L);
            ECONOMY.setDesiredReserve(colony, EconomicResource.FOOD, 100L);
            ECONOMY.setProduction(colony, EconomicResource.FOOD, 80.0D);
            ECONOMY.setConsumption(colony, EconomicResource.FOOD, 40.0D);
        }
        return data;
    }

    private static NPCColonyData colony(final KingdomsSavedData data, final UUID id) { return data.colony(id).orElseThrow(); }

    private static double production(final KingdomsSavedData data, final UUID id, final EconomicResource resource)
    {
        return colony(data, id).economy().resource(resource).flow().productionPerDay();
    }

    private static long stock(final KingdomsSavedData data, final UUID id)
    {
        return colony(data, id).economy().resource(EconomicResource.FOOD).stockpile().amount();
    }

    private static WorldEventRecord trigger(final KingdomsSavedData data, final WorldEventType type, final UUID subject, final UUID other,
        final long gameTime)
    {
        return WorldEventService.trigger(data, type, subject, other, gameTime, EVENTS, true).orElseThrow();
    }

    private static void start(final KingdomsSavedData data, final WorldEventRecord event)
    {
        WorldEventService.update(data, event.startsAt(), EVENTS);
        assertEquals(WorldEventRecord.Status.ACTIVE, event.status());
    }

    // ------------------------------------------------------------------------------------------------ eligibility and caps

    @Test
    void onlyEligibleSubjectsBecomeCandidatesWithinCapsAndCooldowns()
    {
        final KingdomsSavedData bare = world();
        final List<WorldEventService.Candidate> none = WorldEventService.candidates(bare, 0L, EVENTS);
        assertTrue(none.stream().noneMatch(value -> value.type() == WorldEventType.HARVEST_FAILURE), "no harvest failure without farming");
        assertTrue(none.stream().anyMatch(value -> value.type() == WorldEventType.BANDIT_SURGE && ROAD.equals(value.road())));
        assertTrue(none.stream().anyMatch(value -> value.type() == WorldEventType.BORDER_INCIDENT), "the road makes A and B neighbours");

        final KingdomsSavedData data = farming();
        final List<WorldEventService.Candidate> candidates = WorldEventService.candidates(data, 0L, EVENTS);
        assertTrue(candidates.stream().anyMatch(value -> value.type() == WorldEventType.HARVEST_FAILURE && A.equals(value.settlement())));
        assertTrue(candidates.stream().allMatch(value -> !value.reason().isBlank()), "every candidate explains why it is eligible");

        // the road becomes unusable: no surge on it
        data.roads().get(ROAD).orElseThrow().status(com.minecolonies.kingdoms.world.road.RoadStatus.UNROUTABLE);
        assertTrue(WorldEventService.candidates(data, 0L, EVENTS).stream().noneMatch(value -> value.type() == WorldEventType.BANDIT_SURGE));

        // per-settlement cap: an open event on A excludes every other event involving A
        final WorldEventRecord first = WorldEventService.trigger(data, WorldEventType.HARVEST_FAILURE, A, null, 0L, EVENTS, false).orElseThrow();
        assertTrue(WorldEventService.candidates(data, 10L, EVENTS).stream().noneMatch(value -> A.equals(value.settlement()) || A.equals(value.other())));
        assertTrue(WorldEventService.trigger(data, WorldEventType.TRADE_FAIR, A, null, 10L, EVENTS, false).isEmpty(), "caps apply without force");

        // global cap
        final WorldEventSettings one = new WorldEventSettings(true, 12_000L, 1.0D, 1, 1, 72_000L, 2_400L, 36_000L, 256, 120);
        assertTrue(WorldEventService.candidates(data, 10L, one).isEmpty(), "the only open slot is taken");

        // cooldown after the event ends: the same type on the same settlement waits
        WorldEventService.update(data, first.endsAt(), EVENTS);
        assertFalse(first.open());
        assertTrue(WorldEventService.candidates(data, first.endsAt() + 20L, EVENTS).stream()
            .noneMatch(value -> value.type() == WorldEventType.HARVEST_FAILURE && A.equals(value.settlement())), "cooling down");
        assertTrue(WorldEventService.candidates(data, first.plannedAt() + EVENTS.cooldownTicks(), EVENTS).stream()
            .anyMatch(value -> value.type() == WorldEventType.HARVEST_FAILURE && A.equals(value.settlement())), "eligible again later");
    }

    @Test
    void planningIsSeededOncePerEvaluationAndNeverRerolledAfterARestart()
    {
        final WorldEventSettings always = new WorldEventSettings(true, 12_000L, 1.0D, 4, 1, 72_000L, 2_400L, 36_000L, 256, 120);
        final KingdomsSavedData first = farming();
        final KingdomsSavedData second = farming();
        final WorldEventRecord a = WorldEventService.evaluateNow(first, 1_000L, always).planned().orElseThrow();
        final WorldEventRecord b = WorldEventService.evaluateNow(second, 1_000L, always).planned().orElseThrow();
        assertEquals(a.id(), b.id(), "the same world seed and evaluation give the same event");
        assertEquals(a.type(), b.type());
        assertEquals(a.magnitude(), b.magnitude(), EPSILON);
        assertEquals(1L, first.worldEvents().evaluations());

        final KingdomsSavedData restarted = PersistenceTestAccess.reload(first);
        assertEquals(1L, restarted.worldEvents().evaluations(), "the evaluation count survives the restart");
        final WorldEventService.Report again = WorldEventService.update(restarted, 1_100L, always);
        assertTrue(again.planned().isEmpty(), "the interval has not passed: nothing is planned again");
        assertEquals(1, restarted.worldEvents().events().size());
        final WorldEventRecord next = WorldEventService.evaluateNow(restarted, 1_200L, always).planned().orElse(null);
        if (next != null) assertNotEquals(a.id(), next.id(), "the next evaluation has its own index and ID");

        final WorldEventSettings never = new WorldEventSettings(true, 12_000L, 0.0D, 4, 1, 72_000L, 2_400L, 36_000L, 256, 120);
        assertTrue(WorldEventService.evaluateNow(farming(), 1_000L, never).planned().isEmpty(), "chance 0: nothing ever happens");
    }

    @Test
    void manyNeighbourPairsDoNotCrowdOutSettlementAndRoadEvents()
    {
        final java.util.ArrayList<WorldEventService.Candidate> pool = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++)
        {
            pool.add(new WorldEventService.Candidate(WorldEventType.BORDER_INCIDENT, null, null, id("fa" + index), id("fb" + index), null, null,
                null, 2.0D, "pair " + index));
            pool.add(new WorldEventService.Candidate(WorldEventType.ENVOY_VISIT, null, null, id("fa" + index), id("fb" + index), null, null,
                null, 2.0D, "pair " + index));
        }
        pool.add(new WorldEventService.Candidate(WorldEventType.HARVEST_FAILURE, A, null, null, null, null, EconomicResource.FOOD, null, 3.0D, "farm"));
        int harvests = 0;
        for (long seed = 0; seed < 2_000L; seed++)
        {
            final WorldEventService.Candidate chosen = WorldEventService.choose(pool, seed * 0x9E3779B97F4A7C15L).orElseThrow();
            assertEquals(chosen, WorldEventService.choose(pool, seed * 0x9E3779B97F4A7C15L).orElseThrow(), "seeded");
            if (chosen.type() == WorldEventType.HARVEST_FAILURE) harvests++;
        }
        // types are weighted by their strongest candidate (3 : 2 : 2), not by how many subjects they have
        assertTrue(harvests > 700 && harvests < 1_000, "harvest failures: " + harvests + " of 2000");
        assertTrue(WorldEventService.choose(List.of(), 1L).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ effects through authorities

    @Test
    void aHarvestFailureGoesThroughTheEconomyOnceAndGivesBackOnlyWhatItTook()
    {
        final KingdomsSavedData data = farming();
        final WorldEventRecord event = trigger(data, WorldEventType.HARVEST_FAILURE, A, null, 0L);
        assertEquals(WorldEventRecord.Status.PLANNED, event.status());
        WorldEventService.update(data, event.startsAt() - 1L, EVENTS);
        assertEquals(1_000L, stock(data, A), "nothing happens during the notice");
        start(data, event);
        assertTrue(event.applied());
        assertTrue(event.stockLost() >= 150L && event.stockLost() <= 350L, "15-35% of the stock");
        assertEquals(1_000L - event.stockLost(), stock(data, A));
        assertTrue(event.productionDelta() < 0.0D);
        assertEquals(80.0D + event.productionDelta(), production(data, A, EconomicResource.FOOD), EPSILON);

        // repeated updates and a restart change nothing more
        WorldEventService.update(data, event.startsAt() + 100L, EVENTS);
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final WorldEventRecord reloaded = restarted.worldEvents().event(event.id()).orElseThrow();
        WorldEventService.update(restarted, event.startsAt() + 200L, EVENTS);
        assertEquals(1_000L - event.stockLost(), stock(restarted, A));
        assertEquals(80.0D + event.productionDelta(), production(restarted, A, EconomicResource.FOOD), EPSILON);

        // meanwhile production changes for other reasons (growth): the end gives back exactly the event's own share
        ECONOMY.adjustProduction(colony(restarted, A), EconomicResource.FOOD, 10.0D);
        WorldEventService.update(restarted, reloaded.endsAt(), EVENTS);
        assertEquals(WorldEventRecord.Status.RESOLVED, reloaded.status());
        assertTrue(reloaded.reverted());
        assertEquals(90.0D, production(restarted, A, EconomicResource.FOOD), EPSILON);
        assertEquals(1_000L - event.stockLost(), stock(restarted, A), "the spoiled food stays lost");
        WorldEventService.update(restarted, reloaded.endsAt() + 1_000L, EVENTS);
        assertFalse(WorldEventService.resolveNow(restarted, reloaded, reloaded.endsAt() + 2_000L), "an ended event never ends again");
        assertEquals(90.0D, production(restarted, A, EconomicResource.FOOD), EPSILON);
        assertTrue(restarted.contracts().contracts().isEmpty(), "events never create contracts themselves");
    }

    @Test
    void twoProductionEventsOnOneSettlementEachGiveBackOnlyTheirOwnShare()
    {
        final KingdomsSavedData data = farming();
        final WorldEventRecord failure = trigger(data, WorldEventType.HARVEST_FAILURE, A, null, 0L);
        data.tradeLedger().routes(); // A is on the fixture's trade route, so a fair is eligible when forced past the caps
        final WorldEventRecord fair = trigger(data, WorldEventType.TRADE_FAIR, A, null, 0L);
        WorldEventService.update(data, failure.startsAt(), EVENTS);
        assertTrue(failure.applied() && fair.applied());
        assertEquals(80.0D + failure.productionDelta() + fair.productionDelta(), production(data, A, fair.resource()), EPSILON);
        assertTrue(WorldEventService.cancel(data, fair, failure.startsAt() + 10L));
        assertFalse(WorldEventService.cancel(data, fair, failure.startsAt() + 11L), "once");
        WorldEventService.update(data, failure.endsAt(), EVENTS);
        assertEquals(80.0D, production(data, A, EconomicResource.FOOD), EPSILON, "back to the start, never above it");
    }

    @Test
    void aBanditSurgeActsOnlyThroughTheThreatTarget()
    {
        final KingdomsSavedData data = farming();
        final WorldEventRecord surge = trigger(data, WorldEventType.BANDIT_SURGE, ROAD, null, 0L);
        assertEquals(0.0D, WorldEventService.threatContribution(data, ROAD, 10L), "not during the notice");
        start(data, surge);
        final double bonus = WorldEventService.threatContribution(data, ROAD, surge.startsAt());
        assertTrue(bonus >= WorldEventService.SURGE_MIN && bonus <= WorldEventService.SURGE_MIN + WorldEventService.SURGE_RANGE);
        final double before = data.bandits().threatOf(ROAD);
        ThreatEvaluator.evaluate(data, road -> 1_000.0D, anchors(data), surge.startsAt() + 20L, SETTINGS, CONTRACTS);
        final RoadThreat threat = data.bandits().threat(ROAD).orElseThrow();
        assertEquals(bonus, threat.lastContributors().event(), EPSILON, "the threat authority reads the surge as a contributor");
        assertTrue(threat.threat() >= before, "the threat moves towards the higher target by its normal step");
        WorldEventService.update(data, surge.endsAt(), EVENTS);
        assertEquals(0.0D, WorldEventService.threatContribution(data, ROAD, surge.endsAt()), "nothing is left behind in the road");
        ThreatEvaluator.evaluate(data, road -> 1_000.0D, anchors(data), surge.endsAt() + 20L, SETTINGS, CONTRACTS);
        assertEquals(0.0D, data.bandits().threat(ROAD).orElseThrow().lastContributors().event(), EPSILON);
    }

    @Test
    void garrisonEventsGoThroughTheMilitaryServiceOnce()
    {
        final KingdomsSavedData data = farming();
        MilitaryService.evaluate(data, 0L, SecuritySettings.defaults(), SETTINGS, CONTRACTS);
        MilitaryService.setStrength(data, A, 10);
        final WorldEventRecord fever = trigger(data, WorldEventType.GARRISON_FEVER, A, null, 0L);
        start(data, fever);
        final int lost = -fever.soldiers();
        assertTrue(lost >= 2 && lost <= 4, "20-35% of 10");
        assertEquals(10 - lost, data.military().garrison(A).orElseThrow().strength());
        assertTrue(data.military().garrison(A).orElseThrow().applied(fever.id()), "the garrison knows the event, once");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        WorldEventService.update(restarted, fever.startsAt() + 100L, EVENTS);
        assertEquals(10 - lost, restarted.military().garrison(A).orElseThrow().strength());

        // volunteers muster where a road is dangerous, never above the capacity (a village of 30 holds 5)
        MilitaryService.setStrength(restarted, A, 1);
        restarted.bandits().threatFor(ROAD).setThreat(60.0D);
        WorldEventService.update(restarted, fever.endsAt(), EVENTS);
        final WorldEventRecord muster = WorldEventService.trigger(restarted, WorldEventType.MILITIA_MUSTER, A, null, fever.endsAt() + 20L,
            EVENTS, false).orElseThrow();
        final int before = restarted.military().garrison(A).orElseThrow().strength();
        start(restarted, muster);
        final var garrison = restarted.military().garrison(A).orElseThrow();
        assertEquals(before + muster.soldiers(), garrison.strength());
        assertTrue(garrison.strength() + garrison.detached() <= garrison.capacity());
        assertTrue(muster.soldiers() >= 0 && muster.soldiers() <= 4);
    }

    @Test
    void diplomaticEventsGoThroughTheDiplomacyServiceAndNeverDeclareWars()
    {
        final KingdomsSavedData data = farming();
        final Faction fa = data.faction(FA).orElseThrow();
        final Faction fb = data.faction(FB).orElseThrow();
        DiplomacyService.set(data, fa, fb, -45, DiplomacyState.Cause.ADMIN_SET, 0L, 0L);
        final WorldEventRecord incident = trigger(data, WorldEventType.BORDER_INCIDENT, FA, FB, 0L);
        start(data, incident);
        assertTrue(incident.relationChange() <= -10 && incident.relationChange() >= -20);
        assertEquals(-45 + incident.relationChange(), DiplomacyService.relation(fa, fb));
        final DiplomacyState.Event logged = data.diplomacy().events().getFirst(); // newest first
        assertEquals(DiplomacyState.Cause.WORLD_EVENT, logged.cause(), "an audited relation change");
        assertTrue(data.war().wars().isEmpty(), "a world event never creates a war record itself");

        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        WorldEventService.update(restarted, incident.startsAt() + 500L, EVENTS);
        assertEquals(-45 + incident.relationChange(), DiplomacyService.relation(restarted.faction(FA).orElseThrow(), restarted.faction(FB).orElseThrow()),
            "applied once, also after a restart");

        // envoys only between factions that are not at war; an incident planned before a war does not happen during it
        WorldEventService.update(restarted, incident.endsAt(), EVENTS); // one open event per pair: the incident ends first
        final WorldEventRecord envoy = trigger(restarted, WorldEventType.ENVOY_VISIT, FA, FB, incident.endsAt());
        MilitaryService.evaluate(restarted, incident.endsAt(), SecuritySettings.defaults(), SETTINGS, CONTRACTS);
        final WarRecord war = WarService.declare(restarted, FA, FB, WarRecord.Cause.ADMIN, 0L, incident.endsAt(), WarSettings.defaults(), true)
            .war().orElseThrow();
        WorldEventService.update(restarted, envoy.startsAt(), EVENTS);
        assertEquals(WorldEventRecord.Status.EXPIRED, envoy.status(), "the factions went to war before the envoys arrived");
        assertFalse(envoy.applied());
        assertTrue(restarted.history().contains(WorldHistory.entryId(WorldHistory.Kind.WAR_DECLARED, war.id())), "wars are history");
    }

    @Test
    void migrationMovesPeopleWithoutCreatingOrLosingAnyone()
    {
        final KingdomsSavedData data = farming();
        final NPCColonyData source = colony(data, A);
        final NPCColonyData destination = colony(data, B);
        source.replaceNeeds(List.of(new ColonyNeed(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200, 0L)));
        destination.updateCapacities(60, destination.storageCapacity());
        final int total = source.population() + destination.population();
        final WorldEventRecord migration = WorldEventService.trigger(data, WorldEventType.MIGRATION, A, B, 0L, EVENTS, false).orElseThrow();
        start(data, migration);
        assertTrue(migration.peopleMoved() >= 1 && migration.peopleMoved() <= WorldEventService.MAX_MIGRANTS);
        assertEquals(total, source.population() + destination.population(), "nobody is created or lost");
        assertEquals(30 - migration.peopleMoved(), source.population());
        assertTrue(source.population() >= 12, "never below the village minimum");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        WorldEventService.update(restarted, migration.endsAt(), EVENTS);
        assertEquals(total, colony(restarted, A).population() + colony(restarted, B).population(), "and never again after a restart");
        assertEquals(30 - migration.peopleMoved(), colony(restarted, A).population());
    }

    @Test
    void eventsWhoseSubjectIsGoneExpireOrAreCancelledWithoutEffect()
    {
        final KingdomsSavedData data = farming();
        final WorldEventRecord fair = trigger(data, WorldEventType.TRADE_FAIR, B, null, 0L);
        final WorldEventRecord planned = trigger(data, WorldEventType.HARVEST_FAILURE, A, null, 500L);
        start(data, fair);
        assertEquals(WorldEventRecord.Status.PLANNED, planned.status(), "still in its notice");
        ColonyRemoval.remove(data, A, fair.startsAt() + 10L, "test");
        ColonyRemoval.remove(data, B, fair.startsAt() + 10L, "test");
        WorldEventService.update(data, planned.startsAt() + 20L, EVENTS);
        assertEquals(WorldEventRecord.Status.EXPIRED, planned.status());
        assertFalse(planned.applied(), "nothing happens to a settlement that is gone");
        assertEquals(WorldEventRecord.Status.CANCELLED, fair.status());
        assertTrue(data.history().contains(WorldHistory.entryId(WorldHistory.Kind.COLONY_ABANDONED, A)), "abandonment is history");
        assertFalse(ColonyRemoval.remove(data, A, 200L, "again").isPresent(), "a colony is removed once");
    }

    // ------------------------------------------------------------------------------------------------ history

    @Test
    void historyIsBoundedDeduplicatedAndSurvivesRestarts()
    {
        final KingdomsSavedData data = world();
        data.history().setLimit(3);
        for (int index = 0; index < 5; index++)
            assertTrue(WorldHistory.record(data, WorldHistory.Entry.of(WorldHistory.Kind.BATTLE, id("battle-" + index), index, null, null,
                "battle " + index)));
        assertEquals(3, data.history().size(), "only the newest entries are kept");
        assertEquals("battle 4", data.history().latest(1).getFirst().detail());
        assertFalse(WorldHistory.record(data, WorldHistory.Entry.of(WorldHistory.Kind.BATTLE, id("battle-4"), 99L, null, null, "again")),
            "one fact is recorded once");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        assertEquals(3, restarted.history().size());
        assertEquals(3, restarted.history().limit());
        assertEquals(data.history().entries(), restarted.history().entries());
        data.history().setLimit(0);
        assertEquals(0, data.history().size(), "a limit of 0 turns history off");
        assertFalse(WorldHistory.record(data, WorldHistory.Entry.of(WorldHistory.Kind.BATTLE, id("battle-9"), 9L, null, null, "off")));
    }

    @Test
    void eventLifecycleIsRecordedAndRoundTripsWithoutDrift()
    {
        final KingdomsSavedData data = farming();
        final WorldEventRecord event = trigger(data, WorldEventType.HARVEST_FAILURE, A, null, 0L);
        start(data, event);
        assertTrue(data.history().contains(WorldHistory.entryId(WorldHistory.Kind.EVENT_STARTED, event.id())));
        final var saved = data.save(new net.minecraft.nbt.CompoundTag(), null);
        final KingdomsSavedData loaded = PersistenceTestAccess.load(saved);
        assertEquals(saved, loaded.save(new net.minecraft.nbt.CompoundTag(), null), "schema 16 round trip");
        WorldEventService.update(loaded, event.endsAt(), EVENTS);
        assertTrue(loaded.history().contains(WorldHistory.entryId(WorldHistory.Kind.EVENT_ENDED, event.id())));
        assertEquals(2, loaded.history().entries().stream().filter(entry -> entry.source().equals(event.id())).count());
    }
}
