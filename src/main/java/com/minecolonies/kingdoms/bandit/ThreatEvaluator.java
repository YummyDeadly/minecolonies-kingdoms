package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * One periodic threat evaluation: every eligible road (physical, routable) steps its threat towards the transparent
 * target; very dangerous roads get a roadblock; roads that stay dangerous get a bandit camp (Phase 8.1); encounters of
 * vanished roads are cancelled; bookkeeping is pruned. Cost: linear in roads plus open encounters; no block access.
 */
public final class ThreatEvaluator
{
    private ThreatEvaluator() {}

    /** {@code cancellations}: encounters ended because their road can no longer be used (for notifications). */
    public record Report(int roads, int roadblocks, int camps, int cancelled, List<EncounterService.Resolution> cancellations) {}

    public static boolean eligible(final RoadRecord road)
    {
        return road.hasPhysicalGeometry() && road.status() != RoadStatus.UNROUTABLE && road.status() != RoadStatus.FAILED;
    }

    public static Report evaluate(final KingdomsSavedData data, final ToDoubleFunction<RoadRecord> remoteLength,
        final List<Vec3> settlementAnchors, final long gameTime, final BanditSettings settings, final ContractSettings contractSettings)
    {
        final BanditRegistry registry = data.bandits();
        CampService.reconcile(data, gameTime, settings, contractSettings);
        final Set<java.util.UUID> roads = new HashSet<>();
        int evaluated = 0;
        int roadblocks = 0;
        int camps = 0;
        for (final RoadRecord road : data.roads().roads())
        {
            if (!eligible(road)) continue;
            roads.add(road.id());
            final RoadThreat threat = registry.threatFor(road.id());
            // security of both endpoints: their garrisons' security level (Phase 9), the archetype before the first evaluation
            final int security = com.minecolonies.kingdoms.military.MilitaryService.level(data, road.firstSettlementId())
                + com.minecolonies.kingdoms.military.MilitaryService.level(data, road.secondSettlementId());
            threat.evaluate(ThreatRules.contributors(settings.baseThreat(), threat.recentTraffic(), remoteLength.applyAsDouble(road),
                threat.raidMomentum(), CampService.contribution(data, road.id(), settings),
                com.minecolonies.kingdoms.worldevent.WorldEventService.threatContribution(data, road.id(), gameTime), security,
                threat.suppressedAt(gameTime)),
                settings.threatStep(), gameTime);
            evaluated++;
            if (threat.threat() >= settings.roadblockThreshold()
                && EncounterService.createRoadblock(data, road, settlementAnchors, gameTime, settings).isPresent()) roadblocks++;
            if (CampService.observe(data, road, threat, settlementAnchors, gameTime, settings).isPresent()) camps++;
        }
        final List<EncounterService.Resolution> cancellations = new ArrayList<>();
        for (final BanditEncounter encounter : new ArrayList<>(registry.open()))
        {
            if (roads.contains(encounter.roadId())) continue;
            final EncounterService.Resolution resolution = EncounterService.cancel(data, encounter, BanditEncounter.Cause.ROAD_GONE, gameTime,
                settings, contractSettings);
            if (resolution.applied()) cancellations.add(resolution);
        }
        final Set<java.util.UUID> inTransit = new HashSet<>();
        for (final TradeShipment shipment : data.tradeLedger().shipments())
            if (shipment.status() == TradeShipmentStatus.IN_TRANSIT) inTransit.add(shipment.id());
        registry.prune(inTransit, roads, gameTime, EncounterService.RESOLVED_RETENTION_TICKS, EncounterService.MAX_RESOLVED_HISTORY);
        registry.setLastEvaluatedAt(gameTime);
        data.markChanged();
        return new Report(evaluated, roadblocks, camps, cancellations.size(), List.copyOf(cancellations));
    }
}
