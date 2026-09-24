package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadRoutePlanner;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometry;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPoint;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Two settlements 1600 blocks apart on one straight road (a point every 16 blocks). The road has a bridge section at
 * x = 880..976. With the default 192-block exclusion radius the eligible ambush candidates are x = 320, 480, 640, 800,
 * 1120 and 1280 (160 and 1440 are too close to a town, 960 is on the bridge).
 */
final class BanditFixtures
{
    static final UUID A = id("bandit-town-a");
    static final UUID B = id("bandit-town-b");
    static final UUID FA = id("bandit-faction-a");
    static final UUID FB = id("bandit-faction-b");
    static final UUID ROAD = id("bandit-road");
    static final UUID ROUTE = id("bandit-route");
    static final UUID PLAYER = id("bandit-player");
    static final UUID OTHER = id("bandit-other");
    static final BanditSettings SETTINGS = BanditSettings.defaults();
    static final ContractSettings CONTRACTS = ContractSettings.defaults();

    private BanditFixtures() {}

    static UUID id(final String name) { return UUID.nameUUIDFromBytes(name.getBytes()); }

    static KingdomsSavedData world()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        settlement(data, A, FA, "Avenby", SettlementType.VILLAGE, new BlockPos(0, 64, 0));
        settlement(data, B, FB, "Brookhold", SettlementType.TOWN, new BlockPos(1600, 64, 0));
        final List<RoadGeometryPoint> points = new ArrayList<>();
        for (int x = 0; x <= 1600; x += 16)
            points.add(new RoadGeometryPoint(new BlockPos(x, 64, 0), x >= 880 && x <= 976 ? RoadGeometryKind.BRIDGE : RoadGeometryKind.GROUND));
        data.roads().put(new RoadRecord(ROAD, A, B, SettlementFixtures.OVERWORLD, RoadType.STONE, new RoadGeometry(points),
            RoadStatus.GENERATED, 1));
        data.tradeLedger().putRoute(new com.minecolonies.kingdoms.trade.TradeRoute(ROUTE, A, B, EconomicResource.FOOD, 100L, 0L, 0));
        return data;
    }

    private static void settlement(final KingdomsSavedData data, final UUID id, final UUID factionId, final String name,
        final SettlementType type, final BlockPos anchor)
    {
        data.settlements().put(new SettlementRecord(id, name, type, SettlementFixtures.OVERWORLD, anchor, 0, anchor.offset(0, 0, 20),
            factionId, 30, SettlementPhysicalState.PLANNED, new SettlementRegion(SettlementFixtures.OVERWORLD, 0, 0), null, 0L));
        final Faction faction = new Faction(factionId, name + " Council", FactionType.CITY_STATE);
        faction.setCapitalColonyId(id);
        faction.setTreasury(200);
        data.putFaction(faction);
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(id, SettlementFixtures.OVERWORLD, anchor, name, factionId, 0L);
        colony.updatePopulation(30, 15, 2);
        data.putColony(colony);
    }

    /** An in-transit food shipment from A to B that departed at {@code departAt} and takes {@code travel} ticks. */
    static TradeShipment shipment(final KingdomsSavedData data, final String name, final long amount, final long departAt, final long travel)
    {
        final TradeShipment shipment = new TradeShipment(id(name), ROUTE, A, B, EconomicResource.FOOD, amount, Math.max(0L, departAt - 1));
        shipment.depart(departAt, travel);
        data.tradeLedger().putShipment(shipment);
        return shipment;
    }

    static RoadShipmentPath path(final KingdomsSavedData data)
    {
        return new RoadShipmentPath(new RoadRoutePlanner().shortest(data.roads(), A, B).orElseThrow(), data.roads());
    }

    static List<Vec3> anchors(final KingdomsSavedData data)
    {
        return EncounterPlanner.exclusionAnchors(data);
    }

    static BanditSettings noCooldown()
    {
        final BanditSettings d = SETTINGS;
        return new BanditSettings(true, d.evaluationIntervalTicks(), d.materializationRadius(), d.dematerializationRadius(),
            d.maxBanditsPerEncounter(), d.maxPhysicalBanditsGlobal(), d.maxPhysicalBanditsPerPlayer(), d.settlementExclusionRadius(),
            d.baseThreat(), d.threatStep(), 0L, d.ambushChanceAtMaxThreat(), d.abstractResolveTicks(), d.roadblockThreshold(),
            d.roadblockLifetimeTicks(), d.suppressionTicks(), 1_000);
    }

    /** Plans an ambush on the fixture road regardless of chance, for a shipment at its start. */
    static BanditEncounter ambush(final KingdomsSavedData data, final TradeShipment shipment, final long gameTime)
    {
        final RoadShipmentPath path = path(data);
        return EncounterService.planAmbush(data, shipment, path, path.spans().getFirst(), anchors(data), gameTime, SETTINGS).orElseThrow();
    }

    /** Moves an abstract shipment past its ambush point and activates the encounter. */
    static void activate(final KingdomsSavedData data, final BanditEncounter encounter, final TradeShipment shipment)
    {
        final long at = shipment.departureAt() + (long) Math.ceil(encounter.triggerProgress() * shipment.travelDurationTicks()) + 1;
        EncounterService.activateDue(data, at, SETTINGS, CONTRACTS);
    }
}
