package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class EncounterPlanningTest
{
    @Test
    void busyRemoteTradeRoadsBecomeDangerous()
    {
        final KingdomsSavedData data = world();
        final RoadRecord road = data.roads().get(ROAD).orElseThrow();
        final List<Vec3> anchors = anchors(data);
        final double remote = EncounterPlanner.remoteLength(road, anchors, SETTINGS.settlementExclusionRadius());
        assertTrue(remote > 1000.0D && remote < road.length(), "the ends near the towns are not remote");
        for (int evaluation = 0; evaluation < 12; evaluation++)
        {
            final TradeShipment shipment = shipment(data, "caravan-" + evaluation, 100, evaluation * 1_200L, 4_000L);
            EncounterService.assess(data, shipment, path(data), anchors, evaluation * 1_200L, BanditSettings.defaults());
            ThreatEvaluator.evaluate(data, value -> remote, anchors, evaluation * 1_200L, SETTINGS, CONTRACTS);
        }
        final RoadThreat threat = data.bandits().threat(ROAD).orElseThrow();
        assertTrue(threat.threat() > ThreatRules.AMBUSH_FLOOR, "caravans on a remote road attract bandits: " + threat.threat());
        assertTrue(threat.lastContributors().traffic() > 0.0D && threat.lastContributors().remoteness() > 0.0D);
        assertEquals(24.0D, threat.lastContributors().security(), "village (1) + town (2) security");
        assertEquals(data.bandits().lastEvaluatedAt(), 11 * 1_200L);
    }

    @Test
    void quietRoadsStaySafeAndUnroutableRoadsAreNotTracked()
    {
        final KingdomsSavedData data = world();
        final List<Vec3> anchors = anchors(data);
        for (int evaluation = 0; evaluation < 40; evaluation++)
            ThreatEvaluator.evaluate(data, value -> 0.0D, anchors, evaluation * 1_200L, SETTINGS, CONTRACTS);
        assertTrue(data.bandits().threatOf(ROAD) < ThreatRules.AMBUSH_FLOOR, "no traffic, no remoteness: no ambushes");
        final RoadRecord unroutable = RoadRecord.unrouteable(id("gap"), A, B, SettlementFixtures.OVERWORLD, RoadType.DIRT,
            new BlockPos(0, 64, 50), new BlockPos(1600, 64, 50), 1);
        data.roads().put(unroutable);
        assertFalse(ThreatEvaluator.eligible(unroutable));
        ThreatEvaluator.evaluate(data, value -> 1000.0D, anchors, 50_000L, SETTINGS, CONTRACTS);
        assertTrue(data.bandits().threat(unroutable.id()).isEmpty(), "no threat without a physical, routable road");
    }

    @Test
    void ambushPointsAvoidTownsAndBridges()
    {
        final KingdomsSavedData data = world();
        final RoadShipmentPath path = path(data);
        final Set<Double> chosen = new HashSet<>();
        for (long seed = 0; seed < 500; seed++)
        {
            final OptionalDouble distance = EncounterPlanner.ambushDistance(path, path.spans().getFirst(), 0.0D, anchors(data),
                SETTINGS.settlementExclusionRadius(), seed * 104_729L);
            assertTrue(distance.isPresent());
            final Vec3 point = path.positionAt(distance.getAsDouble() / path.length());
            assertTrue(point.x >= 192 && point.x <= 1408, "outside both towns' exclusion zones: " + point.x);
            assertFalse(point.x > 870 && point.x < 990, "never on the bridge: " + point.x);
            chosen.add(Math.rint(point.x));
        }
        assertEquals(Set.of(320.5D, 480.5D, 640.5D, 800.5D, 1120.5D, 1280.5D).stream().map(Math::rint).collect(java.util.stream.Collectors.toSet()),
            chosen, "exactly the eligible candidates are used");
        assertTrue(EncounterPlanner.ambushDistance(path, path.spans().getFirst(), 1300.0D, anchors(data), 192, 1L).isEmpty(),
            "never behind or right in front of the caravan");
        for (long seed = 0; seed < 100; seed++)
        {
            final BlockPos block = EncounterPlanner.roadblockPoint(data.roads().get(ROAD).orElseThrow(), anchors(data), 192, seed).orElseThrow();
            assertTrue(block.getX() >= 192 && block.getX() <= 1408 && (block.getX() < 850 || block.getX() > 1000));
        }
        assertTrue(EncounterPlanner.ambushDistance(path, path.spans().getFirst(), 0.0D, anchors(data), 900, 1L).isEmpty(),
            "a road that is all town has no ambush point");
    }

    @Test
    void assessmentIsDeterministicOncePerShipmentAndSurvivesRestarts()
    {
        final KingdomsSavedData first = world();
        final KingdomsSavedData second = world();
        data(first).setThreat(100.0D);
        data(second).setThreat(100.0D);
        int planned = 0;
        for (int index = 0; index < 60; index++)
        {
            final TradeShipment a = shipment(first, "s" + index, 100, 0L, 4_000L);
            final TradeShipment b = shipment(second, "s" + index, 100, 0L, 4_000L);
            final var one = EncounterService.assess(first, a, path(first), anchors(first), 10L, noCooldown());
            final var two = EncounterService.assess(second, b, path(second), anchors(second), 10L, noCooldown());
            assertEquals(one.map(BanditEncounter::id), two.map(BanditEncounter::id), "same world, same decision");
            assertEquals(one.map(BanditEncounter::position), two.map(BanditEncounter::position));
            if (one.isPresent()) planned++;
            assertTrue(EncounterService.assess(first, a, path(first), anchors(first), 20L, noCooldown()).isEmpty(), "judged once");
        }
        assertTrue(planned > 20 && planned < 52, "about 60% of caravans are ambushed at threat 100: " + planned);
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(first);
        for (int index = 0; index < 60; index++)
        {
            final TradeShipment reloaded = restarted.tradeLedger().shipment(id("s" + index)).orElseThrow();
            assertTrue(restarted.bandits().assessed(reloaded.id()));
            assertTrue(EncounterService.assess(restarted, reloaded, path(restarted), anchors(restarted), 30L, noCooldown()).isEmpty(),
                "a restart never re-rolls a shipment");
        }
        assertEquals(planned, restarted.bandits().open().size());
    }

    @Test
    void cooldownsSuppressionAndCapsBoundEncounters()
    {
        final KingdomsSavedData data = world();
        data(data).setThreat(100.0D);
        final TradeShipment first = shipment(data, "first", 100, 0L, 4_000L);
        final BanditEncounter encounter = ambush(data, first, 0L);
        assertEquals(BanditEncounter.Status.PLANNED, encounter.status());
        assertTrue(data.bandits().threat(ROAD).orElseThrow().coolingDownAt(100L), "one encounter per road per cooldown");
        for (int index = 0; index < 30; index++)
            assertTrue(EncounterService.assess(data, shipment(data, "later" + index, 100, 0L, 4_000L), path(data), anchors(data), 100L,
                SETTINGS).isEmpty());
        assertTrue(EncounterService.planAmbush(data, first, path(data), path(data).spans().getFirst(), anchors(data), 200L, SETTINGS).isEmpty(),
            "at most one open encounter per shipment");
        final KingdomsSavedData capped = world();
        data(capped).setThreat(100.0D);
        final BanditSettings none = new BanditSettings(true, 1_200, 48, 80, 5, 24, 10, 192, 5.0D, 5.0D, 0L, 1.0D, 2_400L, 70.0D, 24_000L,
            24_000L, 0);
        assertTrue(EncounterService.assess(capped, shipment(capped, "x", 100, 0L, 4_000L), path(capped), anchors(capped), 0L, none).isEmpty(),
            "the active-encounter cap applies");
    }

    @Test
    void roadblocksAppearOnlyOnVeryDangerousRoadsAndOnlyOnce()
    {
        final KingdomsSavedData data = world();
        final RoadRecord road = data.roads().get(ROAD).orElseThrow();
        data(data).setThreat(100.0D);
        ThreatEvaluator.evaluate(data, value -> 1500.0D, anchors(data), 0L, SETTINGS, CONTRACTS);
        assertTrue(data.bandits().openRoadblockOn(ROAD), "threat above the threshold sets up a roadblock");
        assertTrue(EncounterService.createRoadblock(data, road, anchors(data), 10L, SETTINGS).isEmpty(), "never two on one road");
        final BanditEncounter roadblock = data.bandits().open().getFirst();
        assertEquals(BanditEncounter.Status.ACTIVE, roadblock.status());
        assertEquals(EncounterRules.roadblockId(ROAD, 1), roadblock.id(), "deterministic identity");
        road.status(com.minecolonies.kingdoms.world.road.RoadStatus.UNROUTABLE);
        final ThreatEvaluator.Report report = ThreatEvaluator.evaluate(data, value -> 1500.0D, anchors(data), 20L, SETTINGS, CONTRACTS);
        assertEquals(1, report.cancelled(), "encounters of a road that is no longer usable are cancelled");
        assertEquals(BanditEncounter.Cause.ROAD_GONE, roadblock.cause());
    }

    private static RoadThreat data(final KingdomsSavedData data)
    {
        return data.bandits().threatFor(ROAD);
    }
}
