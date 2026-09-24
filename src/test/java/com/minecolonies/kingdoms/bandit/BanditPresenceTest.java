package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.citizen.SafeSpawnFinder;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.trade.TradeShipment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Physical representation rules: caps, duplicates, orphans, spawn safety, hysteresis, and stuck recovery. */
class BanditPresenceTest
{
    private static final UUID FIRST = id("presence-first");
    private static final UUID SECOND = id("presence-second");
    private static final UUID THIRD = id("presence-third");
    private static final BlockPos ROAD_POINT = new BlockPos(640, 64, 0);
    private static final java.util.function.Predicate<BlockPos> OUTDOORS = position -> true;

    /** Flat world: floor at y=63, air above, water where {@code water} says, loaded only for x below {@code loadedBelow}. */
    private static final class World implements SafeSpawnFinder.BlockView
    {
        final int loadedBelow;
        final Set<Long> water;
        final AtomicInteger unloadedReads = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();

        World(final int loadedBelow, final Set<Long> water)
        {
            this.loadedBelow = loadedBelow;
            this.water = water;
        }

        @Override public boolean loaded(final int x, final int z) { return x < loadedBelow; }
        @Override public boolean floor(final int x, final int y, final int z) { return check(x) && y == 63 && !water.contains(BlockPos.asLong(x, 0, z)); }
        @Override public boolean open(final int x, final int y, final int z) { return check(x) && y > 63 && !water.contains(BlockPos.asLong(x, 0, z)); }

        private boolean check(final int x)
        {
            reads.incrementAndGet();
            if (x >= loadedBelow) unloadedReads.incrementAndGet();
            return true;
        }
    }

    private static List<UUID> entities(final String prefix, final int count)
    {
        final List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) ids.add(id(prefix + index));
        return ids;
    }

    @Test
    void rematerializingNeverDuplicatesAndOldBanditsBecomeOrphans()
    {
        final BanditRoster roster = new BanditRoster();
        roster.begin(FIRST, PLAYER, 0L);
        final List<UUID> firstWave = entities("wave-one-", 3);
        firstWave.forEach(entity -> assertTrue(roster.add(FIRST, entity)));
        assertFalse(roster.add(FIRST, firstWave.getFirst()), "an entity is listed once");
        assertThrows(IllegalStateException.class, () -> roster.begin(FIRST, OTHER, 5L), "one materialization per encounter");
        assertEquals(3, roster.bandits());

        assertEquals(firstWave, roster.end(FIRST), "leaving returns exactly the bandits to discard");
        assertEquals(0, roster.bandits());
        assertTrue(roster.end(FIRST).isEmpty(), "ending twice discards nothing more");
        firstWave.forEach(entity -> assertFalse(roster.isCurrent(entity, FIRST), "bandits of an earlier visit are orphans"));

        roster.begin(FIRST, PLAYER, 100L);
        final List<UUID> secondWave = entities("wave-two-", 3);
        secondWave.forEach(entity -> roster.add(FIRST, entity));
        assertEquals(3, roster.bandits(), "coming back shows the same group once, not twice");
        secondWave.forEach(entity -> assertTrue(roster.isCurrent(entity, FIRST)));
        assertFalse(roster.isCurrent(secondWave.getFirst(), SECOND), "a bandit claiming another encounter is an orphan");
        assertFalse(roster.isCurrent(id("stranger"), FIRST), "an unknown bandit (from a chunk or an old session) is an orphan");

        assertEquals(java.util.Optional.of(FIRST), roster.drop(secondWave.getFirst()));
        assertFalse(roster.isCurrent(secondWave.getFirst(), FIRST));
        assertEquals(2, roster.presence(FIRST).orElseThrow().size());
        assertEquals(3, roster.presence(FIRST).orElseThrow().spawnedEver(), "top-ups within one visit get fresh probe seeds");

        roster.clear(); // server restart: nothing physical survives
        secondWave.forEach(entity -> assertFalse(roster.isCurrent(entity, FIRST)));
        assertEquals(0, roster.encounters());
    }

    @Test
    void capsHoldAcrossEncountersPlayersAndTheServer()
    {
        final BanditSettings settings = SETTINGS; // 5 per encounter, 10 per player, 24 on the server
        final BanditRoster roster = new BanditRoster();
        assertEquals(5, roster.allowance(PLAYER, 7, settings), "per encounter");
        roster.begin(FIRST, PLAYER, 0L);
        entities("a-", 5).forEach(entity -> roster.add(FIRST, entity));
        roster.begin(SECOND, PLAYER, 0L);
        assertEquals(5, roster.allowance(PLAYER, 5, settings));
        entities("b-", 5).forEach(entity -> roster.add(SECOND, entity));
        assertEquals(0, roster.allowance(PLAYER, 5, settings), "one player sees at most ten bandits");
        assertEquals(5, roster.allowance(OTHER, 5, settings), "another player has a budget of their own");

        // many players: the server-wide cap binds
        int index = 0;
        while (roster.bandits() < settings.maxPhysicalBanditsGlobal())
        {
            final UUID encounter = id("crowd-" + index);
            final UUID player = id("player-" + index++);
            final int count = roster.allowance(player, 5, settings);
            assertTrue(count > 0);
            roster.begin(encounter, player, 0L);
            entities("crowd-" + index + "-", count).forEach(entity -> roster.add(encounter, entity));
        }
        assertEquals(settings.maxPhysicalBanditsGlobal(), roster.bandits());
        assertEquals(0, roster.allowance(id("late-player"), 5, settings), "no bandit beyond the global cap");
        assertEquals(0, roster.allowance(null, 5, settings), "operator materializations obey the global cap too");
    }

    @Test
    void spawnsStayOnDryLoadedGroundAwayFromTownsAndPlayers()
    {
        final List<Vec3> anchors = List.of(new Vec3(0.5D, 64.0D, 0.5D));
        final World open = new World(Integer.MAX_VALUE, Set.of());
        final List<BanditSpawnPlanner.Placement> placements = BanditSpawnPlanner.place(42L, ROAD_POINT, 5, 0, open, OUTDOORS, anchors,
            SETTINGS.settlementExclusionRadius(), List.of());
        assertEquals(5, placements.size());
        final Set<BlockPos> distinct = new HashSet<>();
        for (final BanditSpawnPlanner.Placement placement : placements)
        {
            final BlockPos position = placement.position();
            assertTrue(distinct.add(position), "never two bandits in one block");
            assertEquals(64, position.getY(), "standing on the ground");
            final double distance = Math.sqrt(position.distSqr(ROAD_POINT));
            assertTrue(distance >= 2.0D && distance <= 13.0D, "around the ambush point: " + distance);
        }
        assertEquals(placements, BanditSpawnPlanner.place(42L, ROAD_POINT, 5, 0, open, OUTDOORS, anchors, SETTINGS.settlementExclusionRadius(),
            List.of()), "deterministic for the same visit");

        // water all around the road: nobody spawns and the encounter stays abstract
        final Set<Long> lake = new HashSet<>();
        for (int x = 600; x <= 680; x++) for (int z = -40; z <= 40; z++) lake.add(BlockPos.asLong(x, 0, z));
        assertTrue(BanditSpawnPlanner.place(42L, ROAD_POINT, 5, 0, new World(Integer.MAX_VALUE, lake), OUTDOORS, anchors,
            SETTINGS.settlementExclusionRadius(), List.of()).isEmpty(), "never in water");

        // inside a settlement's exclusion zone
        assertTrue(BanditSpawnPlanner.place(42L, new BlockPos(40, 64, 0), 5, 0, open, OUTDOORS, anchors, SETTINGS.settlementExclusionRadius(),
            List.of()).isEmpty(), "never in a town");

        // a player standing right where they would appear
        final List<Vec3> crowd = new ArrayList<>();
        for (int x = 620; x <= 660; x += 4) for (int z = -20; z <= 20; z += 4) crowd.add(new Vec3(x + 0.5D, 64.0D, z + 0.5D));
        assertTrue(BanditSpawnPlanner.place(42L, ROAD_POINT, 5, 0, open, OUTDOORS, anchors, SETTINGS.settlementExclusionRadius(), crowd).isEmpty(),
            "never on top of a player");
    }

    @Test
    void spawningNeverTouchesUnloadedChunksAndIsBounded()
    {
        final World unloaded = new World(0, Set.of());
        assertTrue(BanditSpawnPlanner.place(7L, ROAD_POINT, 5, 0, unloaded, OUTDOORS, List.of(), SETTINGS.settlementExclusionRadius(), List.of())
            .isEmpty(), "nothing appears where the world is not loaded");
        assertEquals(0, unloaded.unloadedReads.get(), "no block of an unloaded chunk is ever read (so none is ever loaded)");

        final World half = new World(ROAD_POINT.getX(), Set.of());
        final List<BanditSpawnPlanner.Placement> placements = BanditSpawnPlanner.place(7L, ROAD_POINT, 5, 0, half, OUTDOORS, List.of(),
            SETTINGS.settlementExclusionRadius(), List.of());
        assertEquals(0, half.unloadedReads.get());
        placements.forEach(placement -> assertTrue(placement.position().getX() < ROAD_POINT.getX(), "only the loaded side"));

        // worst case (no valid block anywhere): bounded by probes x the finder's own cap
        final Set<Long> everywhere = new HashSet<>();
        for (int x = 600; x <= 680; x++) for (int z = -40; z <= 40; z++) everywhere.add(BlockPos.asLong(x, 0, z));
        final World flooded = new World(Integer.MAX_VALUE, everywhere);
        BanditSpawnPlanner.place(7L, ROAD_POINT, SETTINGS.maxBanditsPerEncounter(), 0, flooded, OUTDOORS, List.of(), SETTINGS.settlementExclusionRadius(),
            List.of());
        assertTrue(flooded.reads.get() <= SETTINGS.maxBanditsPerEncounter() * BanditSpawnPlanner.ATTEMPTS_PER_BANDIT
            * SafeSpawnFinder.MAX_CANDIDATES * 3, "bounded work: " + flooded.reads.get());
    }

    @Test
    void aFailedSpawnKeepsTheEncounterAbstractAndRetriesLater()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "unspawnable", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        activate(data, encounter, shipment);
        final BanditRoster roster = new BanditRoster();
        final long now = encounter.activatedAt() + 20L;
        assertTrue(roster.mayMaterialize(encounter.id(), now));
        // what the manager does when no placement is found
        roster.begin(encounter.id(), PLAYER, now);
        assertTrue(roster.end(encounter.id()).isEmpty());
        roster.retry(encounter.id(), now + BanditManager.FAILURE_RETRY_TICKS);
        assertEquals(BanditEncounter.Representation.ABSTRACT, encounter.representation());
        assertFalse(roster.physical(encounter.id()));
        assertFalse(roster.mayMaterialize(encounter.id(), now + 20L), "no spawn attempt every cycle");
        assertTrue(roster.mayMaterialize(encounter.id(), now + BanditManager.FAILURE_RETRY_TICKS));
        // unobserved, the abstract rules still settle it
        final List<EncounterService.Resolution> due = EncounterService.resolveDue(data, encounter.resolveAt(), SETTINGS, CONTRACTS);
        assertEquals(1, due.size());
        assertFalse(encounter.open());
    }

    @Test
    void hysteresisAndStuckRecoveryLetTheAbstractRulesFinishAFight()
    {
        assertTrue(BanditRoster.inMaterializationRange(SETTINGS.materializationRadius(), false, SETTINGS));
        assertFalse(BanditRoster.inMaterializationRange(SETTINGS.materializationRadius() + 1, false, SETTINGS));
        assertFalse(BanditRoster.shouldLeave(SETTINGS.materializationRadius() + 1, false, SETTINGS), "no flicker between the radii");
        assertTrue(BanditRoster.shouldLeave(SETTINGS.dematerializationRadius() + 1, false, SETTINGS));
        assertFalse(BanditRoster.shouldLeave(SETTINGS.dematerializationRadius() + 1, true, SETTINGS), "an operator hold keeps them");
        assertTrue(BanditRoster.inMaterializationRange(Double.POSITIVE_INFINITY, true, SETTINGS));

        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "stuck", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        activate(data, encounter, shipment);
        final BanditRoster roster = new BanditRoster();
        final long start = encounter.activatedAt();
        final BanditRoster.Presence presence = roster.begin(encounter.id(), PLAYER, start);
        encounter.representation(BanditEncounter.Representation.PHYSICAL);
        roster.add(encounter.id(), id("stuck-bandit"));
        assertFalse(BanditRoster.stalled(presence, encounter, start + BanditManager.STALL_TICKS));
        presence.progress(start + 1_000L);
        assertFalse(BanditRoster.stalled(presence, encounter, start + BanditManager.STALL_TICKS + 1L), "a hit resets the clock");
        final long stuck = start + 1_000L + BanditManager.STALL_TICKS + 20L;
        assertTrue(BanditRoster.stalled(presence, encounter, stuck));
        assertTrue(EncounterService.resolveDue(data, stuck, SETTINGS, CONTRACTS).isEmpty(), "a physical fight is never rolled");

        // what the manager does on a stall: dematerialize, defer, and suppress past the deferred resolution
        roster.end(encounter.id());
        encounter.representation(BanditEncounter.Representation.ABSTRACT);
        encounter.deferResolution(stuck, SETTINGS.abstractResolveTicks() / 2);
        roster.suppress(encounter.id(), stuck + BanditRoster.stallSuppression(SETTINGS));
        long time = stuck;
        while (encounter.open())
        {
            time += BanditManager.CYCLE_TICKS;
            assertFalse(roster.mayMaterialize(encounter.id(), time), "the player standing there cannot pull it back before it settles");
            EncounterService.resolveDue(data, time, SETTINGS, CONTRACTS);
        }
        assertEquals(BanditEncounter.Cause.ABSTRACT_ROLL, encounter.cause());

        // a roadblock that outlived its lifetime leaves once nobody fights it
        final BanditEncounter roadblock = EncounterService.createRoadblock(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 0L,
            SETTINGS, true).orElseThrow();
        final BanditRoster.Presence camp = roster.begin(roadblock.id(), PLAYER, 0L);
        final long expiry = roadblock.expiresAt();
        camp.progress(expiry - 10L);
        assertFalse(BanditRoster.stalled(camp, roadblock, expiry + 100L), "an ongoing fight is not cut off");
        assertTrue(BanditRoster.stalled(camp, roadblock, expiry + BanditManager.EXPIRY_GRACE_TICKS));
        roster.end(roadblock.id());
        roadblock.representation(BanditEncounter.Representation.ABSTRACT);
        EncounterService.resolveDue(data, expiry + BanditManager.EXPIRY_GRACE_TICKS + 20L, SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.EXPIRED, roadblock.status());
    }

    @Test
    void timersStayBoundedByTheOpenEncounters()
    {
        final BanditRoster roster = new BanditRoster();
        for (int index = 0; index < 500; index++)
        {
            roster.retry(id("gone-" + index), 1_000L);
            roster.suppress(id("gone-" + index), 2_000L);
        }
        roster.hold(FIRST, 5_000L);
        roster.suppress(SECOND, 500L);
        roster.sweep(600L, Set.of(FIRST, SECOND, THIRD));
        assertEquals(1, roster.timers(), "resolved encounters and expired timers are forgotten");
        assertTrue(roster.held(FIRST, 600L));
        assertTrue(roster.mayMaterialize(SECOND, 600L));
    }

    @Test
    void playerInterventionResolvesOnceAndSurvivesRestart()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "rescued", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, shipment, 0L);
        activate(data, encounter, shipment);
        encounter.representation(BanditEncounter.Representation.PHYSICAL);
        // the player kills every bandit; each death lowers the persisted strength once
        for (int kill = 0; kill < encounter.strength(); kill++)
        {
            encounter.banditLost();
            encounter.defender(PLAYER);
        }
        assertEquals(0, encounter.remainingStrength());
        assertEquals(List.of(PLAYER), encounter.defenders(), "one defender entry however many kills");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final BanditEncounter reloaded = restarted.bandits().encounter(encounter.id()).orElseThrow();
        assertEquals(0, reloaded.remainingStrength(), "kills persist: reloading does not bring the bandits back");
        assertEquals(BanditEncounter.Representation.ABSTRACT, reloaded.representation());
        // with no bandits left, even the abstract rules can only say they were beaten
        assertEquals(EncounterRules.Outcome.BANDITS_DEFEATED, EncounterRules.decideAbstract(reloaded.seed(), 0, 1).outcome());

        final EncounterService.Resolution won = EncounterService.resolve(restarted, reloaded,
            new EncounterRules.Decision(EncounterRules.Outcome.BANDITS_DEFEATED, 0.0D, 0L), BanditEncounter.Cause.PLAYER_VICTORY, List.of(),
            reloaded.activatedAt() + 100L, SETTINGS, CONTRACTS);
        assertTrue(won.applied());
        assertEquals(BanditEncounter.Status.RESOLVED_PLAYER, reloaded.status());
        final int standing = com.minecolonies.kingdoms.diplomacy.ReputationService.standing(restarted, PLAYER, FA);
        assertEquals(EncounterService.DEFENDER_REPUTATION, standing);
        for (int again = 0; again < 3; again++)
        {
            assertFalse(EncounterService.resolve(restarted, reloaded, new EncounterRules.Decision(EncounterRules.Outcome.BANDITS_DEFEATED,
                0.0D, 0L), BanditEncounter.Cause.PLAYER_VICTORY, List.of(PLAYER), reloaded.activatedAt() + 200L, SETTINGS, CONTRACTS).applied());
            assertTrue(EncounterService.resolveDue(restarted, reloaded.activatedAt() + 10_000L, SETTINGS, CONTRACTS).isEmpty());
        }
        final KingdomsSavedData again = PersistenceTestAccess.reload(restarted);
        assertEquals(standing, com.minecolonies.kingdoms.diplomacy.ReputationService.standing(again, PLAYER, FA), "reputation changed once");
        assertEquals(0L, again.tradeLedger().shipment(shipment.id()).orElseThrow().lostAmount(), "the rescued caravan keeps its cargo");
        assertFalse(again.bandits().encounter(encounter.id()).orElseThrow().open());
    }

    @Test
    void indoorSpotsAndPeacefulWorldsGetNoBandits()
    {
        final World open = new World(Integer.MAX_VALUE, Set.of());
        assertTrue(BanditSpawnPlanner.place(42L, ROAD_POINT, 5, 0, open, position -> false, List.of(), SETTINGS.settlementExclusionRadius(),
            List.of()).isEmpty(), "never inside a roofed building or a cave");
        final List<BanditSpawnPlanner.Placement> westOnly = BanditSpawnPlanner.place(42L, ROAD_POINT, 5, 0, open,
            position -> position.getX() < ROAD_POINT.getX(), List.of(), SETTINGS.settlementExclusionRadius(), List.of());
        westOnly.forEach(placement -> assertTrue(placement.position().getX() < ROAD_POINT.getX()));
        assertFalse(BanditRoster.mayAppear(net.minecraft.world.Difficulty.PEACEFUL), "peaceful keeps every encounter abstract");
        assertTrue(BanditRoster.mayAppear(net.minecraft.world.Difficulty.EASY));
        assertTrue(BanditRoster.mayAppear(net.minecraft.world.Difficulty.HARD));
    }

    @Test
    void aPlayerColonyNextToTheRoadIsKeptClearLikeATown()
    {
        final KingdomsSavedData data = world();
        final List<Vec3> before = anchors(data);
        data.putColony(new com.minecolonies.kingdoms.colony.NPCColonyData(id("player-colony"), 7,
            com.minecolonies.kingdoms.world.settlement.SettlementFixtures.OVERWORLD, new BlockPos(640, 64, 40), "Player town", FA));
        final List<Vec3> anchors = anchors(data);
        assertEquals(before.size() + 1, anchors.size(), "tracked player colonies are exclusion anchors too");
        final var path = path(data);
        for (long seed = 0; seed < 200; seed++)
        {
            final double distance = EncounterPlanner.ambushDistance(path, path.spans().getFirst(), 0.0D, anchors,
                SETTINGS.settlementExclusionRadius(), seed).orElseThrow();
            final Vec3 point = path.positionAt(distance / path.length());
            assertTrue(Math.hypot(point.x - 640.5D, point.z - 40.5D) >= SETTINGS.settlementExclusionRadius(),
                "no ambush next to the player's colony: " + point);
        }
        assertTrue(BanditSpawnPlanner.place(42L, ROAD_POINT, 5, 0, new World(Integer.MAX_VALUE, Set.of()), OUTDOORS, anchors,
            SETTINGS.settlementExclusionRadius(), List.of()).isEmpty(), "and no bandit spawns inside it");
    }
}
