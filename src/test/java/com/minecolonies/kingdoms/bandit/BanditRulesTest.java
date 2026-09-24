package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.world.settlement.SettlementType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BanditRulesTest
{
    @Test
    void threatTargetIsTransparentAndMovesWithoutOscillation()
    {
        final ThreatRules.Contributors busy = ThreatRules.contributors(5.0D, 5.0D, 1000.0D, 0.0D, 3, false);
        assertEquals(30.0D, busy.traffic());
        assertEquals(25.0D, busy.remoteness());
        assertEquals(24.0D, busy.security());
        assertEquals(36.0D, busy.target(), 1.0E-9);
        assertEquals(0.0D, ThreatRules.contributors(5.0D, 5.0D, 1000.0D, 0.0D, 3, true).target(), "suppression");
        assertEquals(100.0D, ThreatRules.contributors(100.0D, 50.0D, 5000.0D, 80.0D, 0, false).target(), "clamped");
        double threat = 0.0D;
        double previous = -1.0D;
        for (int evaluation = 0; evaluation < 30; evaluation++)
        {
            threat = ThreatRules.step(threat, 36.0D, 5.0D);
            assertTrue(threat >= previous && threat <= 36.0D, "monotonic towards the target, never beyond it");
            previous = threat;
        }
        assertEquals(36.0D, threat);
        assertEquals(31.0D, ThreatRules.step(36.0D, 0.0D, 5.0D), "falls by at most one step");
    }

    @Test
    void ambushChanceStrengthAndSecurityAreBounded()
    {
        assertEquals(0.0D, ThreatRules.ambushChance(14.9D, 0.6D));
        assertEquals(0.6D, ThreatRules.ambushChance(100.0D, 0.6D), 1.0E-9);
        assertTrue(ThreatRules.ambushChance(60.0D, 0.6D) > ThreatRules.ambushChance(40.0D, 0.6D));
        assertEquals(2, ThreatRules.strength(0.0D, 5));
        assertEquals(5, ThreatRules.strength(100.0D, 5), "capped by configuration");
        assertEquals(6, ThreatRules.strength(100.0D, 12));
        assertEquals(4, ThreatRules.security(SettlementType.CASTLE));
        assertEquals(1, ThreatRules.security(SettlementType.VILLAGE));
    }

    @Test
    void abstractDecisionsAreDeterministicAndPreferPartialLosses()
    {
        final Map<EncounterRules.Outcome, Integer> weak = new EnumMap<>(EncounterRules.Outcome.class);
        final Map<EncounterRules.Outcome, Integer> strong = new EnumMap<>(EncounterRules.Outcome.class);
        for (long seed = 0; seed < 4000; seed++)
        {
            final EncounterRules.Decision decision = EncounterRules.decideAbstract(seed * 7919L, 6, 1);
            assertEquals(decision, EncounterRules.decideAbstract(seed * 7919L, 6, 1), "same seed, same decision");
            strong.merge(decision.outcome(), 1, Integer::sum);
            if (decision.outcome() == EncounterRules.Outcome.PARTIAL_LOSS)
                assertTrue(decision.lossFraction() >= 0.2D && decision.lossFraction() <= 0.6D);
            if (decision.outcome() == EncounterRules.Outcome.CARAVAN_DELAYED)
                assertTrue(decision.delayTicks() >= 1_200L && decision.delayTicks() <= 3_600L);
            weak.merge(EncounterRules.decideAbstract(seed * 7919L, 1, 4).outcome(), 1, Integer::sum);
        }
        for (final EncounterRules.Outcome outcome : EncounterRules.Outcome.values())
            assertTrue(strong.getOrDefault(outcome, 0) > 0, "every outcome occurs for strong groups: " + outcome);
        assertEquals(0, weak.getOrDefault(EncounterRules.Outcome.TOTAL_LOSS, 0), "only groups of six or more can take everything");
        assertTrue(strong.get(EncounterRules.Outcome.PARTIAL_LOSS) > 10 * strong.get(EncounterRules.Outcome.TOTAL_LOSS), "partial losses dominate");
        final int strongWins = strong.get(EncounterRules.Outcome.PARTIAL_LOSS) + strong.get(EncounterRules.Outcome.TOTAL_LOSS);
        assertTrue(strongWins > weak.getOrDefault(EncounterRules.Outcome.PARTIAL_LOSS, 0), "stronger bandits win more often");
        assertEquals(EncounterRules.Outcome.BANDITS_DEFEATED, EncounterRules.decideAbstract(42L, 0, 0).outcome(), "no bandits left");
        assertEquals(1, EncounterRules.lostUnits(10, 0.01D), "any loss takes at least one unit");
        assertEquals(40, EncounterRules.lostUnits(100, 0.4D));
        assertEquals(100, EncounterRules.lostUnits(100, 1.0D));
        assertEquals(0, EncounterRules.lostUnits(100, 0.0D));
        final double overrun = EncounterRules.overrunFraction(123L);
        assertTrue(overrun >= 0.3D && overrun <= 0.6D);
    }

    @Test
    void physicalCapsHoldForEncountersPlayersAndTheServer()
    {
        assertEquals(5, BanditCaps.allowance(6, 5, 24, 10), "per encounter");
        assertEquals(3, BanditCaps.allowance(6, 5, 3, 10), "global cap left");
        assertEquals(2, BanditCaps.allowance(6, 5, 24, 2), "per player left");
        assertEquals(0, BanditCaps.allowance(6, 5, -4, 10), "never negative");
        assertEquals(1, BanditCaps.allowance(1, 5, 24, 10), "never more than the remaining strength");
        // two players each near their own encounter: each has its own budget, the server shares one
        int global = 24;
        final int first = BanditCaps.allowance(5, 5, global, 10);
        global -= first;
        final int second = BanditCaps.allowance(5, 5, global, 10);
        global -= second;
        assertEquals(14, global);
        assertEquals(0, BanditCaps.allowance(5, 5, 24, 0), "a player at the cap gets no more bandits");
    }
}
