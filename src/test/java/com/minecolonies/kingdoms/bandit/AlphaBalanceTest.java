package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomaticStance;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecuritySettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.war.CampaignService;
import com.minecolonies.kingdoms.war.WarRecord;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.war.WarSettings;
import com.minecolonies.kingdoms.world.road.*;
import com.minecolonies.kingdoms.world.road.geometry.*;
import com.minecolonies.kingdoms.world.settlement.*;
import com.minecolonies.kingdoms.worldevent.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** A bounded, reproducible balance probe, not a substitute for playtesting the economy. */
class AlphaBalanceTest
{
    @Test
    void threeHundredDaysAllowWarsButDoNotTrapTheWorldInPermanentWar()
    {
        int incidents = 0, envoys = 0, transitions = 0, started = 0, ended = 0;
        long fightingFactionSamples = 0, factionSamples = 0;
        final WarSettings war = WarSettings.defaults();
        final WorldEventSettings events = WorldEventSettings.defaults();
        final EconomyManager economy = new EconomyManager();
        for (int seed = 0; seed < 12; seed++)
        {
            KingdomsSavedData data = new KingdomsSavedData();
            data.worldEvents().seedIfUnset(5318008L + seed);
            final List<UUID> towns = new ArrayList<>(), factions = new ArrayList<>();
            for (int index = 0; index < 6; index++)
            {
                final UUID town = id("alpha-town-" + index), factionId = id("alpha-faction-" + index);
                towns.add(town);
                factions.add(factionId);
                final BlockPos pos = new BlockPos(index * 800, 64, 0);
                final Faction faction = new Faction(factionId, "Council " + index, FactionType.CITY_STATE);
                faction.setCapitalColonyId(town);
                faction.setTreasury(200);
                data.putFaction(faction);
                final NPCColonyData colony = NPCColonyData.createStrategicNpc(town, SettlementFixtures.OVERWORLD, pos,
                    "Town " + index, factionId, 0L);
                colony.updatePopulation(60, 30, 12);
                economy.setStockpile(colony, EconomicResource.FOOD, 100_000L);
                economy.setDesiredReserve(colony, EconomicResource.FOOD, 100L);
                economy.setProduction(colony, EconomicResource.FOOD, 80);
                economy.setConsumption(colony, EconomicResource.FOOD, 40);
                data.putColony(colony);
                data.settlements().put(new SettlementRecord(town, colony.name(), SettlementType.TOWN, SettlementFixtures.OVERWORLD,
                    pos, 0, pos.offset(0, 0, 20), factionId, 60, SettlementPhysicalState.PLANNED,
                    new SettlementRegion(SettlementFixtures.OVERWORLD, index, 0), null, 0));
                if (index > 0)
                    data.roads().put(new RoadRecord(id("alpha-road-" + index), towns.get(index - 1), town, SettlementFixtures.OVERWORLD,
                        RoadType.STONE, new RoadGeometry(List.of(new RoadGeometryPoint(pos.offset(-800, 0, 0), RoadGeometryKind.GROUND),
                            new RoadGeometryPoint(pos, RoadGeometryKind.GROUND))), RoadStatus.GENERATED, 1));
            }
            final Map<String, DiplomaticStance> stances = new HashMap<>();
            for (long time = 0; time <= 300L * 24_000; time += 1_200)
            {
                final var report = WorldEventService.update(data, time, events);
                incidents += (int) report.activated().stream().filter(e -> e.type() == WorldEventType.BORDER_INCIDENT).count();
                envoys += (int) report.activated().stream().filter(e -> e.type() == WorldEventType.ENVOY_VISIT).count();
                if (time % 24_000 == 0)
                {
                    MilitaryService.evaluate(data, time, SecuritySettings.defaults(), SETTINGS, CONTRACTS);
                    final var wars = WarService.evaluate(data, time, war);
                    started += wars.declared().size();
                    ended += wars.ended().size();
                }
                CampaignService.update(data, time, war, CONTRACTS);
                final long open = data.war().wars().stream().filter(WarRecord::open).count();
                assertTrue(open <= war.maxWars(), "global war cap");
                fightingFactionSamples += 2 * open;
                factionSamples += factions.size();
                for (int index = 1; index < factions.size(); index++)
                {
                    final int relation = DiplomacyService.relation(data.faction(factions.get(index - 1)).orElseThrow(),
                        data.faction(factions.get(index)).orElseThrow());
                    final DiplomaticStance stance = DiplomaticStance.of(relation);
                    final DiplomaticStance previous = stances.put("pair" + index, stance);
                    if (previous != null && previous != stance) transitions++;
                }
                if (time > 0 && time % (50L * 24_000) == 0) data = PersistenceTestAccess.reload(data);
            }
        }
        final double fraction = fightingFactionSamples / (double) factionSamples;
        System.out.printf(Locale.ROOT,
            "ALPHA_BALANCE worlds=12 factions=6 days=300 incidents=%d envoys=%d stanceTransitions=%d warsStarted=%d warsEnded=%d factionWarFraction=%.5f%n",
            incidents, envoys, transitions, started, ended, fraction);
        assertTrue(incidents > 0 && envoys > 0);
        assertTrue(started > 0, "autonomous war must be reachable without operator hostility");
        assertTrue(ended > 0, "wars must end");
        assertTrue(fraction < 0.35, "most faction time should remain peaceful");
    }
}
