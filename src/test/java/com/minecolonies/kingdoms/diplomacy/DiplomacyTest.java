package com.minecolonies.kingdoms.diplomacy;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.trade.DefaultTradePermissionPolicy;
import com.minecolonies.kingdoms.trade.TradeDemand;
import com.minecolonies.kingdoms.trade.TradeOffer;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiplomacyTest
{
    private static UUID id(final String name) { return UUID.nameUUIDFromBytes(name.getBytes()); }

    @Test
    void tiersAndStancesCoverTheWholeRange()
    {
        assertEquals(ReputationTier.HOSTILE, ReputationTier.of(-100));
        assertEquals(ReputationTier.HOSTILE, ReputationTier.of(-60));
        assertEquals(ReputationTier.UNFRIENDLY, ReputationTier.of(-59));
        assertEquals(ReputationTier.NEUTRAL, ReputationTier.of(0));
        assertEquals(ReputationTier.FRIENDLY, ReputationTier.of(20));
        assertEquals(ReputationTier.HONORED, ReputationTier.of(60));
        assertEquals(ReputationTier.REVERED, ReputationTier.of(100));
        assertFalse(ReputationTier.HOSTILE.contractsAllowed());
        assertEquals(DiplomaticStance.TENSE, DiplomaticStance.of(-20));
        assertEquals(DiplomaticStance.NEUTRAL, DiplomaticStance.of(-19));
        assertEquals(DiplomaticStance.ALLIED, DiplomaticStance.of(60));
        assertFalse(DiplomaticStance.TENSE.allowsTrade());
        assertTrue(DiplomaticStance.NEUTRAL.allowsTrade());
    }

    @Test
    void relationRulesAreConservative()
    {
        final Set<Integer> starts = new HashSet<>();
        for (int index = 0; index < 500; index++)
        {
            final UUID a = id("x" + index);
            final UUID b = id("y" + index);
            assertEquals(DiplomacyRules.baseline(a, b), DiplomacyRules.baseline(b, a), "order independent");
            starts.add(DiplomacyRules.firstContact(a, b));
        }
        assertTrue(starts.stream().allMatch(v -> v >= -19 && v <= 30), "first contact is never tense");
        assertTrue(starts.contains(-19) && starts.stream().anyMatch(v -> v >= 20), "cautious and friendly neighbours exist");
        assertEquals(0, DiplomacyRules.tradeDelta(0));
        assertEquals(1, DiplomacyRules.tradeDelta(1));
        assertEquals(2, DiplomacyRules.tradeDelta(99), "trade gain capped per evaluation");
        assertEquals(0, DiplomacyRules.tradeDelta(-3));
    }

    @Test
    void reputationSpillsOnceToAlliesAndEnemies()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final Faction helped = new Faction(id("helped"), "Helped", FactionType.CITY_STATE);
        final Faction ally = new Faction(id("ally"), "Ally", FactionType.CITY_STATE);
        final Faction enemy = new Faction(id("enemy"), "Enemy", FactionType.CITY_STATE);
        final Faction neutral = new Faction(id("neutral"), "Neutral", FactionType.CITY_STATE);
        List.of(helped, ally, enemy, neutral).forEach(data::putFaction);
        DiplomacyService.set(data, helped, ally, 70, DiplomacyState.Cause.ADMIN_SET, 0L, 0L);
        DiplomacyService.set(data, helped, enemy, -80, DiplomacyState.Cause.ADMIN_SET, 0L, 0L);
        DiplomacyService.set(data, helped, neutral, 0, DiplomacyState.Cause.ADMIN_SET, 0L, 0L);
        final UUID player = id("player");
        final ReputationService.Result result = ReputationService.adjust(data, player, helped.id(), 20,
            ReputationRegistry.Cause.CONTRACT_COMPLETED, null, 5L);
        assertEquals(3, result.changes().size());
        assertEquals(20, ReputationService.standing(data, player, helped.id()));
        assertEquals(5, ReputationService.standing(data, player, ally.id()));
        assertEquals(-5, ReputationService.standing(data, player, enemy.id()));
        assertEquals(0, ReputationService.standing(data, player, neutral.id()));
        assertTrue(result.primary().tierChanged());
        assertEquals(ReputationRegistry.Cause.SPILLOVER, data.reputation().events().getFirst().cause(), "spillover is audited");
        assertEquals(helped.id(), data.reputation().events().getFirst().reference());
        ReputationService.adjust(data, player, helped.id(), -500, ReputationRegistry.Cause.ADMIN_SET, null, 6L);
        assertEquals(-100, ReputationService.standing(data, player, helped.id()), "clamped");
        assertEquals(-100, ReputationService.standing(data, player, ally.id()), "allies share the loss (5 - 125, clamped)");
        assertEquals(-5, ReputationService.standing(data, player, enemy.id()), "enemies do not care about losses");
        assertTrue(ReputationService.adjust(data, player, id("missing"), 10, ReputationRegistry.Cause.ADMIN_SET, null, 7L).isEmpty());
    }

    private static NPCColonyData colony(final KingdomsSavedData data, final String name, final FactionType type)
    {
        final Faction faction = new Faction(id(name + "-faction"), name + " Council", type);
        faction.setCapitalColonyId(id(name));
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(id(name), SettlementFixtures.OVERWORLD,
            new BlockPos(0, 64, 0), name, faction.id(), 0L);
        data.putFaction(faction);
        data.putColony(colony);
        return colony;
    }

    private static void deliver(final KingdomsSavedData data, final String name, final UUID from, final UUID to, final long arrival)
    {
        final TradeShipment shipment = new TradeShipment(id(name), id("route"), from, to, EconomicResource.FOOD, 10, 1);
        shipment.depart(arrival - 100, 100);
        shipment.deliver();
        data.tradeLedger().putShipment(shipment);
    }

    @Test
    void relationsChangeOnlyThroughAuditedEvents()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final NPCColonyData oak = colony(data, "Oak", FactionType.CITY_STATE);
        final NPCColonyData high = colony(data, "High", FactionType.CITY_STATE);
        final NPCColonyData far = colony(data, "Far", FactionType.CITY_STATE);
        final NPCColonyData player = colony(data, "PlayerTown", FactionType.PLAYER);
        data.roads().put(RoadRecord.unrouteable(id("road-oh"), oak.id(), high.id(), SettlementFixtures.OVERWORLD, RoadType.STONE,
            new BlockPos(0, 64, 0), new BlockPos(100, 64, 0), 1));
        data.roads().put(new RoadRecord(id("road-hp"), high.id(), player.id(), SettlementFixtures.OVERWORLD, RoadType.DIRT,
            List.of(new BlockPos(0, 64, 0), new BlockPos(50, 64, 0)), RoadStatus.PLANNED, 1));
        for (int index = 0; index < 3; index++) deliver(data, "s" + index, oak.id(), high.id(), 110);
        oak.replaceNeeds(List.of(new ColonyNeed(NeedType.IRON_SHORTAGE, NeedSeverity.CRITICAL, 0, 10, 0)));
        high.replaceNeeds(List.of(new ColonyNeed(NeedType.IRON_SHORTAGE, NeedSeverity.CRITICAL, 2, 10, 0)));

        assertEquals(1, DiplomacyEvaluator.neighbourPairs(data).size(), "far is nobody's neighbour; player factions do not take part");
        final DiplomacyEvaluator.Report first = DiplomacyEvaluator.evaluate(data, 24_000L);
        final Faction oakFaction = data.faction(oak.factionId()).orElseThrow();
        final Faction highFaction = data.faction(high.factionId()).orElseThrow();
        final int contact = DiplomacyRules.firstContact(oakFaction.id(), highFaction.id());
        assertEquals(List.of(DiplomacyState.Cause.TRADE_DELIVERIES, DiplomacyState.Cause.FIRST_CONTACT),
            data.diplomacy().events().stream().map(DiplomacyState.Event::cause).toList(), "newest first");
        assertEquals(2, first.events().size());
        assertEquals(contact + 2, oakFaction.relations().get(highFaction.id()), "first contact, then +2 for 3 deliveries");
        assertEquals(contact + 2, highFaction.relations().get(oakFaction.id()), "symmetric");
        assertEquals(3L, data.diplomacy().events().getFirst().evidence());
        assertFalse(data.faction(far.factionId()).orElseThrow().relations().containsKey(oakFaction.id()));
        assertTrue(data.faction(player.factionId()).orElseThrow().relations().isEmpty());

        final int before = oakFaction.relations().get(highFaction.id());
        for (int day = 2; day <= 30; day++)
            assertTrue(DiplomacyEvaluator.evaluate(data, day * 24_000L).events().isEmpty(),
                "no trade and no events: shared scarcity and time alone never move relations");
        assertEquals(before, oakFaction.relations().get(highFaction.id()));

        deliver(data, "late", high.id(), oak.id(), 30 * 24_000L + 10);
        DiplomacyEvaluator.evaluate(data, 31 * 24_000L);
        assertEquals(before + 1, oakFaction.relations().get(highFaction.id()), "each delivery counts once, in its window");
        DiplomacyEvaluator.evaluate(data, 32 * 24_000L);
        assertEquals(before + 1, oakFaction.relations().get(highFaction.id()));

        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        assertEquals(data.diplomacy().events(), restarted.diplomacy().events());
        assertEquals(32 * 24_000L, restarted.diplomacy().lastEvaluatedAt());
        assertEquals(before + 1, restarted.faction(oak.factionId()).orElseThrow().relations().get(highFaction.id()));
    }

    @Test
    void tenseNeighboursStopTrading()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final NPCColonyData oak = colony(data, "Oak", FactionType.CITY_STATE);
        final NPCColonyData high = colony(data, "High", FactionType.CITY_STATE);
        final Faction first = data.faction(oak.factionId()).orElseThrow();
        final Faction second = data.faction(high.factionId()).orElseThrow();
        final DefaultTradePermissionPolicy policy = new DefaultTradePermissionPolicy();
        final TradeOffer offer = new TradeOffer(oak.id(), first.id(), SettlementFixtures.OVERWORLD, BlockPos.ZERO,
            EconomicResource.FOOD, 100, 1);
        final TradeDemand demand = new TradeDemand(high.id(), second.id(), SettlementFixtures.OVERWORLD, new BlockPos(100, 64, 0),
            EconomicResource.FOOD, 100, 1);
        DiplomacyService.set(data, first, second, -19, DiplomacyState.Cause.ADMIN_SET, 0L, 1L);
        assertTrue(policy.allows(offer, demand, data::faction), "neutral (even slightly negative) neighbours trade");
        DiplomacyService.set(data, first, second, -20, DiplomacyState.Cause.ADMIN_SET, 0L, 2L);
        assertFalse(policy.allows(offer, demand, data::faction), "tense neighbours do not");
        assertEquals(2, data.diplomacy().events().size(), "operator edits are audited too");
        assertTrue(DiplomacyService.set(data, first, second, -20, DiplomacyState.Cause.ADMIN_SET, 0L, 3L).isEmpty(),
            "no event without a change");
    }
}
