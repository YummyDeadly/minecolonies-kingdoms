package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.decision.StrategicActionType;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.ShipmentPathResolver;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TradeManager
{
    private static final TradeManager INSTANCE = new TradeManager(
        new EconomyManager(),
        new TradeMatcher(),
        new DefaultTradePermissionPolicy());

    private final EconomyManager economyManager;
    private final TradeMatcher matcher;
    private final TradePermissionPolicy permissionPolicy;
    private final TradeProfiler profiler = new TradeProfiler();
    private final ShipmentPathResolver pathResolver = new ShipmentPathResolver();
    private final ShipmentTravelTimeEstimator travelTimeEstimator = new ShipmentTravelTimeEstimator();
    private long nextMatchingGameTime;
    private long nextShipmentGameTime;

    public TradeManager(
        final EconomyManager economyManager,
        final TradeMatcher matcher,
        final TradePermissionPolicy permissionPolicy)
    {
        this.economyManager = economyManager;
        this.matcher = matcher;
        this.permissionPolicy = permissionPolicy;
    }

    public static TradeManager getInstance()
    {
        return INSTANCE;
    }

    public void initialize(final MinecraftServer server)
    {
        final long gameTime = server.overworld().getGameTime();
        nextMatchingGameTime = gameTime + KingdomsConfig.SERVER.tradeMatchIntervalTicks.get();
        nextShipmentGameTime = gameTime;
        profiler.reset();
    }

    public void tick(final MinecraftServer server)
    {
        if (!KingdomsConfig.SERVER.tradeEnabled.get())
        {
            return;
        }
        final long gameTime = server.overworld().getGameTime();
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        if (gameTime >= nextShipmentGameTime)
        {
            processShipments(data, gameTime, settingsFromConfig());
            nextShipmentGameTime = gameTime + KingdomsConfig.SERVER.tradeShipmentIntervalTicks.get();
        }
        if (gameTime >= nextMatchingGameTime)
        {
            runMatching(data, gameTime, settingsFromConfig());
            nextMatchingGameTime = gameTime + KingdomsConfig.SERVER.tradeMatchIntervalTicks.get();
        }
    }

    public TradeMatchResult matchNow(final MinecraftServer server)
    {
        return runMatching(
            KingdomsSavedData.get(server.overworld()),
            server.overworld().getGameTime(),
            settingsFromConfig());
    }

    public TradeProcessResult processNow(final MinecraftServer server)
    {
        return processShipments(
            KingdomsSavedData.get(server.overworld()),
            server.overworld().getGameTime(),
            settingsFromConfig());
    }

    public TradeStats stats(final KingdomsSavedData data)
    {
        return profiler.snapshot(data.tradeLedger());
    }

    public void shutdown()
    {
        nextMatchingGameTime = 0L;
        nextShipmentGameTime = 0L;
        profiler.reset();
    }

    public TradeMatchResult runMatching(
        final KingdomsSavedData data,
        final long gameTime,
        final TradeSettings settings)
    {
        final long started = System.nanoTime();
        final TradeLedger ledger = data.tradeLedger();
        final List<TradeOffer> offers = buildOffers(data, settings);
        final List<TradeDemand> demands = buildDemands(data);
        final List<TradeMatchCandidate> candidates = matcher.match(
            offers,
            demands,
            permissionPolicy,
            data::faction,
            settings.maxDistance(),
            settings.minimumShipmentAmount(),
            settings.maximumShipmentAmount());

        int routesCreated = 0;
        int shipmentsPlanned = 0;
        final Set<TradeRouteKey> matchedRoutes = new HashSet<>();
        for (final TradeMatchCandidate candidate : candidates)
        {
            TradeRoute route = ledger.route(candidate.routeKey()).orElse(null);
            if (route == null)
            {
                if (ledger.routeCountFor(candidate.originColonyId()) >= settings.maxRoutesPerColony()
                    || ledger.routeCountFor(candidate.destinationColonyId()) >= settings.maxRoutesPerColony())
                {
                    continue;
                }
                route = new TradeRoute(
                    UUID.randomUUID(),
                    candidate.originColonyId(),
                    candidate.destinationColonyId(),
                    candidate.resource(),
                    candidate.amount(),
                    gameTime,
                    candidate.priority());
                ledger.putRoute(route);
                routesCreated++;
            }
            route.matched(candidate.amount(), candidate.priority(), gameTime);
            matchedRoutes.add(route.key());
            if (!ledger.hasOpenShipment(route.id()))
            {
                ledger.putShipment(new TradeShipment(
                    UUID.randomUUID(),
                    route.id(),
                    route.originColonyId(),
                    route.destinationColonyId(),
                    route.resource(),
                    Math.min(route.targetAmountPerCycle(), candidate.amount()),
                    gameTime));
                shipmentsPlanned++;
            }
        }

        ledger.routes().stream()
            .filter(route -> !matchedRoutes.contains(route.key()))
            .forEach(route -> route.unmatched(settings.routePauseGraceCycles(), gameTime));
        final long duration = System.nanoTime() - started;
        profiler.recordMatching(duration);
        data.markChanged();
        return new TradeMatchResult(candidates.size(), routesCreated, shipmentsPlanned, duration);
    }

    public TradeProcessResult processShipments(
        final KingdomsSavedData data,
        final long gameTime,
        final TradeSettings settings)
    {
        int departed = 0;
        int delivered = 0;
        int failed = 0;
        long resourcesDelivered = 0L;
        final List<TradeShipment> shipments = data.tradeLedger().shipments().stream()
            .sorted(Comparator.comparingLong(TradeShipment::createdAt).thenComparing(TradeShipment::id))
            .toList();
        for (final TradeShipment shipment : shipments)
        {
            if (shipment.status() == TradeShipmentStatus.PLANNED)
            {
                if (depart(data, shipment, gameTime, settings))
                {
                    departed++;
                }
                else if (shipment.status() == TradeShipmentStatus.FAILED)
                {
                    failed++;
                }
            }
            else if (shipment.status() == TradeShipmentStatus.IN_TRANSIT)
            {
                final ShipmentResolution resolution = resolveInTransit(data, shipment, gameTime);
                if (resolution == ShipmentResolution.DELIVERED)
                {
                    delivered++;
                    resourcesDelivered = saturatingAdd(resourcesDelivered, shipment.deliverableAmount());
                    profiler.recordDelivery(shipment.deliverableAmount());
                }
                else if (resolution == ShipmentResolution.FAILED)
                {
                    failed++;
                }
            }
        }
        if (departed > 0 || delivered > 0 || failed > 0)
        {
            data.markChanged();
        }
        return new TradeProcessResult(departed, delivered, failed, resourcesDelivered);
    }

    public void handleColonyDeletion(final KingdomsSavedData data, final UUID colonyId, final long gameTime)
    {
        data.tradeLedger().routes().stream()
            .filter(route -> route.originColonyId().equals(colonyId) || route.destinationColonyId().equals(colonyId))
            .forEach(route -> route.breakRoute("Colony deleted", gameTime));
        data.tradeLedger().shipments().stream()
            .filter(shipment -> shipment.status() == TradeShipmentStatus.PLANNED
                || shipment.status() == TradeShipmentStatus.IN_TRANSIT)
            .filter(shipment -> shipment.originColonyId().equals(colonyId)
                || shipment.destinationColonyId().equals(colonyId))
            .forEach(shipment -> failAndReturnCargo(
                data,
                shipment,
                shipment.destinationColonyId().equals(colonyId)
                    ? ShipmentFailureReason.DESTINATION_REMOVED
                    : ShipmentFailureReason.ORIGIN_REMOVED,
                "Colony deleted",
                colonyId));
        data.markChanged();
    }

    public boolean completePhysicalShipment(
        final KingdomsSavedData data,
        final UUID shipmentId,
        final long gameTime)
    {
        final TradeShipment shipment = data.tradeLedger().shipment(shipmentId).orElse(null);
        if (shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT
            || shipment.representation() != ShipmentRepresentation.PHYSICAL)
        {
            return false;
        }
        final NPCColonyData destination = data.colony(shipment.destinationColonyId()).orElse(null);
        if (destination == null)
        {
            failAndReturnCargo(data, shipment, ShipmentFailureReason.DESTINATION_REMOVED,
                "Destination colony is unavailable", null);
            data.markChanged();
            return false;
        }
        shipment.updatePhysicalProgress(1.0D, gameTime);
        if (shipment.deliverableAmount() > 0L) economyManager.depositShipment(destination, shipment.resource(), shipment.deliverableAmount());
        shipment.deliver();
        profiler.recordDelivery(shipment.deliverableAmount());
        data.markChanged();
        return true;
    }

    public boolean failDestroyedCaravan(final KingdomsSavedData data, final UUID shipmentId)
    {
        final TradeShipment shipment = data.tradeLedger().shipment(shipmentId).orElse(null);
        if (shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT
            || shipment.representation() != ShipmentRepresentation.PHYSICAL)
        {
            return false;
        }
        shipment.fail(ShipmentFailureReason.CARAVAN_DESTROYED, "Caravan leader or carrier was destroyed; cargo lost");
        data.markChanged();
        return true;
    }

    private boolean depart(
        final KingdomsSavedData data,
        final TradeShipment shipment,
        final long gameTime,
        final TradeSettings settings)
    {
        final TradeRoute route = data.tradeLedger().route(shipment.routeId()).orElse(null);
        final NPCColonyData origin = data.colony(shipment.originColonyId()).orElse(null);
        final NPCColonyData destination = data.colony(shipment.destinationColonyId()).orElse(null);
        if (route == null || route.status() == TradeRouteStatus.BROKEN || origin == null || destination == null)
        {
            shipment.fail(ShipmentFailureReason.TECHNICAL_FAILURE, "Route endpoint is unavailable");
            return false;
        }
        final long allReservations = data.tradeLedger().reservedForExport(origin.id(), shipment.resource());
        final long otherReservations = Math.max(0L, allReservations - shipment.amount());
        final long available = economyManager.availableForExport(
            origin,
            shipment.resource(),
            otherReservations,
            settings.exportSafetyBufferPercent());
        if (available < shipment.amount()
            || !economyManager.withdrawForShipment(origin, shipment.resource(), shipment.amount()))
        {
            shipment.fail(ShipmentFailureReason.EXPORT_CAPACITY_LOST, "Export capacity is no longer available");
            return false;
        }
        shipment.depart(gameTime, travelTimeEstimator.estimate(
            pathResolver.resolve(data, origin.id(), destination.id()), settings));
        route.recordShipment(shipment.amount(), gameTime);
        return true;
    }

    private ShipmentResolution resolveInTransit(
        final KingdomsSavedData data,
        final TradeShipment shipment,
        final long gameTime)
    {
        final TradeRoute route = data.tradeLedger().route(shipment.routeId()).orElse(null);
        final NPCColonyData origin = data.colony(shipment.originColonyId()).orElse(null);
        final NPCColonyData destination = data.colony(shipment.destinationColonyId()).orElse(null);
        if (route == null || route.status() == TradeRouteStatus.BROKEN || origin == null || destination == null)
        {
            final ShipmentFailureReason reason = destination == null
                ? ShipmentFailureReason.DESTINATION_REMOVED
                : origin == null ? ShipmentFailureReason.ORIGIN_REMOVED : ShipmentFailureReason.ROUTE_REMOVED;
            failAndReturnCargo(data, shipment, reason, "Route endpoint is unavailable", null);
            return ShipmentResolution.FAILED;
        }
        if (shipment.representation() == ShipmentRepresentation.PHYSICAL)
        {
            return ShipmentResolution.WAITING;
        }
        if (gameTime < shipment.arrivalAt())
        {
            return ShipmentResolution.WAITING;
        }
        if (shipment.deliverableAmount() > 0L) economyManager.depositShipment(destination, shipment.resource(), shipment.deliverableAmount());
        shipment.deliver();
        return ShipmentResolution.DELIVERED;
    }

    private void failAndReturnCargo(
        final KingdomsSavedData data,
        final TradeShipment shipment,
        final ShipmentFailureReason failureReason,
        final String reason,
        final UUID colonyBeingDeleted)
    {
        if (shipment.status() == TradeShipmentStatus.IN_TRANSIT && shipment.deliverableAmount() > 0L)
        {
            // only cargo that bandits did not take can be returned
            data.colony(shipment.originColonyId())
                .filter(origin -> colonyBeingDeleted == null || !origin.id().equals(colonyBeingDeleted))
                .ifPresent(origin -> economyManager.depositShipment(origin, shipment.resource(), shipment.deliverableAmount()));
        }
        shipment.fail(failureReason, reason);
    }

    private List<TradeOffer> buildOffers(final KingdomsSavedData data, final TradeSettings settings)
    {
        final List<TradeOffer> offers = new ArrayList<>();
        for (final NPCColonyData colony : eligibleColonies(data))
        {
            for (final EconomicResource resource : EconomicResource.values())
            {
                final long committed = data.tradeLedger().reservedForExport(colony.id(), resource);
                final long amount = economyManager.availableForExport(
                    colony, resource, committed, settings.exportSafetyBufferPercent());
                if (amount >= settings.minimumShipmentAmount())
                {
                    offers.add(new TradeOffer(
                        colony.id(), colony.factionId(), colony.dimension(), colony.center(), resource, amount, 0));
                }
            }
        }
        return offers;
    }

    private List<TradeDemand> buildDemands(final KingdomsSavedData data)
    {
        final List<TradeDemand> demands = new ArrayList<>();
        for (final NPCColonyData colony : eligibleColonies(data))
        {
            for (final EconomicResource resource : EconomicResource.values())
            {
                final long rawNeed = economyManager.importNeed(colony, resource);
                final long committed = data.tradeLedger().committedForImport(colony.id(), resource);
                final long amount = committed >= rawNeed ? 0L : rawNeed - committed;
                if (amount > 0L)
                {
                    final boolean explicitImport = colony.lastDecision().action() == StrategicActionType.IMPORT_RESOURCE
                        && colony.lastDecision().resource() == resource;
                    final int priority = (explicitImport ? 100 : 0) + reservePriority(colony, resource);
                    demands.add(new TradeDemand(
                        colony.id(), colony.factionId(), colony.dimension(), colony.center(), resource, amount, priority));
                }
            }
        }
        return demands;
    }

    private static List<NPCColonyData> eligibleColonies(final KingdomsSavedData data)
    {
        return data.colonies().stream()
            .filter(colony -> colony.kind() == ColonyKind.NPC_ABSTRACT)
            .sorted(Comparator.comparing(NPCColonyData::id))
            .toList();
    }

    private static int reservePriority(final NPCColonyData colony, final EconomicResource resource)
    {
        final long target = colony.economy().resource(resource).stockpile().desiredReserve();
        final long shortage = colony.economy().resource(resource).stockpile().reserveShortage();
        if (target <= 0L)
        {
            return shortage > 0L ? 100 : 0;
        }
        return (int) Math.min(100L, Math.round(shortage * 100.0D / target));
    }

    private static TradeSettings settingsFromConfig()
    {
        final long minimum = KingdomsConfig.SERVER.tradeMinimumShipmentAmount.get();
        final long maximum = Math.max(minimum, KingdomsConfig.SERVER.tradeMaximumShipmentAmount.get());
        return new TradeSettings(
            KingdomsConfig.SERVER.tradeMaxRoutesPerColony.get(),
            KingdomsConfig.SERVER.tradeMaxAbstractDistance.get(),
            minimum,
            maximum,
            KingdomsConfig.SERVER.tradeBaseTravelTicks.get(),
            KingdomsConfig.SERVER.tradeTravelTicksPerBlock.get(),
            KingdomsConfig.SERVER.tradeRoutePauseGraceCycles.get(),
            KingdomsConfig.SERVER.tradeExportSafetyBufferPercent.get());
    }

    private static long saturatingAdd(final long left, final long right)
    {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private enum ShipmentResolution
    {
        WAITING,
        DELIVERED,
        FAILED
    }
}
