package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.diplomacy.ReputationRegistry;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.ShipmentFailureReason;
import com.minecolonies.kingdoms.trade.ShipmentRepresentation;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The only writer of bandit encounters, and of the bandit effects on shipments.
 *
 * <p>Resolution is one transaction: check the encounter is still open; validate the shipment is still in transit
 * (else cancel); compute the cargo outcome; change the shipment once ({@link TradeShipment#recordBanditLoss} refuses a
 * second loss); record the result on the encounter once; then apply consequences (road threat, defender reputation,
 * contracts). Removing physical bandits is the caller's job and changes nothing strategic.
 */
public final class EncounterService
{
    /** Reputation for players who defeat an encounter without holding a contract for it (once per encounter). */
    public static final int DEFENDER_REPUTATION = 2;
    public static final long RESOLVED_RETENTION_TICKS = 168_000L;
    public static final int MAX_RESOLVED_HISTORY = 256;

    private EncounterService() {}

    /** {@code reputation}: defender (without a contract for this encounter) -> their reputation change. */
    public record Resolution(boolean applied, BanditEncounter encounter, long cargoLost, List<ContractService.Closure> contracts,
        java.util.Map<UUID, ReputationService.Result> reputation)
    {
        static Resolution notApplied(final BanditEncounter encounter)
        {
            return new Resolution(false, encounter, 0L, List.of(), java.util.Map.of());
        }
    }

    // ------------------------------------------------------------------------------------------------ planning

    /**
     * Judges a shipment once: records its traffic on every road it uses and, if one of those roads is dangerous
     * enough and the seeded roll says so, plans a single ambush at a remote point ahead of it. The decision is
     * persisted (the shipment is marked assessed), so it is never re-rolled.
     */
    public static Optional<BanditEncounter> assess(final KingdomsSavedData data, final TradeShipment shipment,
        final RoadShipmentPath path, final List<Vec3> settlementAnchors, final long gameTime, final BanditSettings settings)
    {
        final BanditRegistry registry = data.bandits();
        if (registry.assessed(shipment.id()) || shipment.status() != TradeShipmentStatus.IN_TRANSIT) return Optional.empty();
        registry.markAssessed(shipment.id(), gameTime);
        data.markChanged();
        path.spans().forEach(span -> registry.threatFor(span.roadId()).addTraffic(1.0D));
        if (!settings.enabled() || registry.openForShipment(shipment.id()).isPresent()) return Optional.empty();
        if (registry.open().size() >= settings.maxActiveEncounters()) return Optional.empty();
        final double current = progress(shipment, gameTime) * path.length();
        final List<RoadShipmentPath.RoadSpan> spans = new ArrayList<>(path.spans());
        spans.sort(Comparator.comparingDouble((RoadShipmentPath.RoadSpan span) -> -registry.threatOf(span.roadId()))
            .thenComparing(RoadShipmentPath.RoadSpan::roadId));
        for (final RoadShipmentPath.RoadSpan span : spans)
        {
            final RoadThreat threat = registry.threatFor(span.roadId());
            if (threat.suppressedAt(gameTime) || threat.coolingDownAt(gameTime)) continue;
            final double chance = ThreatRules.ambushChance(threat.threat(), settings.ambushChanceAtMaxThreat());
            if (chance <= 0.0D) break; // spans are sorted by threat: nothing below can qualify either
            final long seed = EncounterRules.seed(EncounterRules.ambushId(shipment.id(), span.roadId()));
            if (EncounterRules.unit(seed, EncounterRules.SALT_AMBUSH) >= chance) return Optional.empty(); // judged: no ambush
            final Optional<BanditEncounter> planned = planAmbush(data, shipment, path, span, settlementAnchors, gameTime, settings);
            if (planned.isPresent()) return planned;
        }
        return Optional.empty();
    }

    /**
     * Plans an ambush of a shipment on one road of its path, at a seeded remote point ahead of it (no chance roll).
     * Used by {@link #assess} after the roll and by the operator {@code spawn-test} command. At most one open
     * encounter per shipment.
     */
    public static Optional<BanditEncounter> planAmbush(final KingdomsSavedData data, final TradeShipment shipment,
        final RoadShipmentPath path, final RoadShipmentPath.RoadSpan span, final List<Vec3> settlementAnchors, final long gameTime,
        final BanditSettings settings)
    {
        final BanditRegistry registry = data.bandits();
        if (shipment.status() != TradeShipmentStatus.IN_TRANSIT || registry.openForShipment(shipment.id()).isPresent())
            return Optional.empty();
        final UUID id = EncounterRules.ambushId(shipment.id(), span.roadId());
        if (registry.encounter(id).isPresent()) return Optional.empty(); // this shipment was already ambushed on this road
        final OptionalDouble distance = EncounterPlanner.ambushDistance(path, span, progress(shipment, gameTime) * path.length(),
            settlementAnchors, settings.settlementExclusionRadius(), EncounterRules.seed(id));
        if (distance.isEmpty()) return Optional.empty();
        final RoadThreat threat = registry.threatFor(span.roadId());
        final double trigger = distance.getAsDouble() / path.length();
        final BanditEncounter encounter = new BanditEncounter(id, BanditEncounter.Kind.AMBUSH, span.roadId(), shipment.id(),
            path.dimension(), BlockPos.containing(path.positionAt(trigger)), trigger, threat.threat(),
            ThreatRules.strength(threat.threat(), settings.maxBanditsPerEncounter()), gameTime,
            gameTime + Math.max(1L, shipment.travelDurationTicks()) * 2L);
        registry.put(encounter);
        registry.markAssessed(shipment.id(), gameTime);
        threat.encountered(gameTime, settings.encounterCooldownTicks());
        data.markChanged();
        return Optional.of(encounter);
    }

    /** Creates a roadblock (lurking bandits) on a very dangerous road, deterministic by the road's roadblock count. */
    public static Optional<BanditEncounter> createRoadblock(final KingdomsSavedData data, final RoadRecord road,
        final List<Vec3> settlementAnchors, final long gameTime, final BanditSettings settings)
    {
        return createRoadblock(data, road, settlementAnchors, gameTime, settings, false);
    }

    /** {@code force} (operator test) ignores suppression, cooldown, and the active-encounter cap. */
    public static Optional<BanditEncounter> createRoadblock(final KingdomsSavedData data, final RoadRecord road,
        final List<Vec3> settlementAnchors, final long gameTime, final BanditSettings settings, final boolean force)
    {
        final BanditRegistry registry = data.bandits();
        if ((!settings.enabled() && !force) || registry.openRoadblockOn(road.id())
            || (!force && registry.open().size() >= settings.maxActiveEncounters())) return Optional.empty();
        final RoadThreat threat = registry.threatFor(road.id());
        if (!force && (threat.suppressedAt(gameTime) || threat.coolingDownAt(gameTime))) return Optional.empty();
        final int ordinal = threat.roadblocks() + 1;
        final UUID id = EncounterRules.roadblockId(road.id(), ordinal);
        final Optional<BlockPos> point = EncounterPlanner.roadblockPoint(road, settlementAnchors, settings.settlementExclusionRadius(),
            EncounterRules.seed(id));
        if (point.isEmpty()) return Optional.empty();
        threat.nextRoadblockOrdinal();
        final BanditEncounter encounter = new BanditEncounter(id, BanditEncounter.Kind.ROADBLOCK, road.id(), null, road.dimension(),
            point.get(), 0.0D, threat.threat(), ThreatRules.strength(threat.threat(), settings.maxBanditsPerEncounter()), gameTime,
            gameTime + settings.roadblockLifetimeTicks());
        registry.put(encounter);
        threat.encountered(gameTime, settings.encounterCooldownTicks());
        data.markChanged();
        return Optional.of(encounter);
    }

    // ------------------------------------------------------------------------------------------------ activation

    /**
     * PLANNED ambushes become ACTIVE when their shipment reaches the ambush point; the shipment is held there until
     * the encounter resolves. Planned ambushes whose shipment is gone are cancelled.
     */
    public static List<BanditEncounter> activateDue(final KingdomsSavedData data, final long gameTime, final BanditSettings settings,
        final ContractSettings contracts)
    {
        final List<BanditEncounter> activated = new ArrayList<>();
        for (final BanditEncounter encounter : data.bandits().open())
        {
            if (encounter.status() != BanditEncounter.Status.PLANNED) continue;
            final TradeShipment shipment = data.tradeLedger().shipment(encounter.shipmentId()).orElse(null);
            if (shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT)
            {
                cancel(data, encounter, BanditEncounter.Cause.SHIPMENT_GONE, gameTime, contracts);
                continue;
            }
            if (progress(shipment, gameTime) + 1.0E-6D < encounter.triggerProgress()) continue;
            encounter.activate(gameTime, settings.abstractResolveTicks());
            shipment.holdUntil(gameTime, encounter.resolveAt());
            data.markChanged();
            activated.add(encounter);
        }
        return activated;
    }

    /**
     * Ends ambushes that can no longer happen: their shipment is no longer in transit (delivered, failed, removed), or
     * they are still PLANNED after their lifetime (a caravan that never reached the ambush point). This bounds every
     * encounter's life, so no bandit presence outlives its reason.
     */
    public static List<Resolution> cancelStale(final KingdomsSavedData data, final long gameTime, final ContractSettings contracts)
    {
        final List<Resolution> cancelled = new ArrayList<>();
        for (final BanditEncounter encounter : data.bandits().open())
        {
            if (encounter.kind() != BanditEncounter.Kind.AMBUSH) continue;
            final boolean gone = data.tradeLedger().shipment(encounter.shipmentId())
                .map(shipment -> shipment.status() != TradeShipmentStatus.IN_TRANSIT).orElse(true);
            if (gone) cancelled.add(cancel(data, encounter, BanditEncounter.Cause.SHIPMENT_GONE, gameTime, contracts));
            else if (encounter.status() == BanditEncounter.Status.PLANNED && gameTime >= encounter.expiresAt())
                cancelled.add(cancel(data, encounter, BanditEncounter.Cause.LIFETIME_OVER, gameTime, contracts));
        }
        return cancelled;
    }

    /** Keeps abstract shipments of active ambushes held (a caravan that just dematerialized would otherwise move on). */
    public static void enforceHolds(final KingdomsSavedData data, final long gameTime)
    {
        for (final BanditEncounter encounter : data.bandits().open())
        {
            if (encounter.status() != BanditEncounter.Status.ACTIVE || encounter.kind() != BanditEncounter.Kind.AMBUSH) continue;
            data.tradeLedger().shipment(encounter.shipmentId())
                .filter(shipment -> shipment.status() == TradeShipmentStatus.IN_TRANSIT
                    && shipment.representation() == ShipmentRepresentation.ABSTRACT && !shipment.heldAt(gameTime))
                .ifPresent(shipment -> shipment.holdUntil(gameTime, Math.max(gameTime + 1L, encounter.resolveAt())));
        }
    }

    // ------------------------------------------------------------------------------------------------ resolution

    /**
     * The single resolution transaction. Returns {@code applied=false} (changing nothing) if the encounter is already
     * resolved, so a second caller, a repeated command, or a restart can never resolve it twice.
     */
    public static Resolution resolve(final KingdomsSavedData data, final BanditEncounter encounter, final EncounterRules.Decision decision,
        final BanditEncounter.Cause cause, final Collection<UUID> defenders, final long gameTime, final BanditSettings settings,
        final ContractSettings contractSettings)
    {
        if (!encounter.open()) return Resolution.notApplied(encounter);
        defenders.forEach(encounter::defender);
        long lost = 0L;
        if (encounter.kind() == BanditEncounter.Kind.AMBUSH)
        {
            final TradeShipment shipment = data.tradeLedger().shipment(encounter.shipmentId()).orElse(null);
            if (shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT)
            {
                return cancel(data, encounter, BanditEncounter.Cause.SHIPMENT_GONE, gameTime, contractSettings);
            }
            final long before = shipment.deliverableAmount();
            final long wanted = EncounterRules.lostUnits(before, decision.lossFraction());
            if (wanted > 0L && shipment.recordBanditLoss(encounter.id(), wanted)) lost = shipment.lostAmount();
            shipment.releaseHold(gameTime);
            if (decision.outcome() == EncounterRules.Outcome.CARAVAN_DELAYED && decision.delayTicks() > 0L)
                shipment.holdUntil(gameTime, gameTime + decision.delayTicks());
            if (lost > 0L && shipment.deliverableAmount() == 0L)
                shipment.fail(ShipmentFailureReason.BANDIT_RAID, "Bandits took all " + before + " " + shipment.resource());
            encounter.recordCargo(shipment.resource(), before, lost, decision.outcome() == EncounterRules.Outcome.CARAVAN_DELAYED
                ? decision.delayTicks() : 0L);
        }
        final EncounterRules.Outcome outcome = encounter.kind() == BanditEncounter.Kind.AMBUSH && lost == 0L && decision.outcome().banditsWon()
            ? EncounterRules.Outcome.CARAVAN_ESCAPED // the shipment had no cargo left to take
            : decision.outcome();
        final BanditEncounter.Status status = switch (outcome)
        {
            case PARTIAL_LOSS, TOTAL_LOSS -> BanditEncounter.Status.RESOLVED_BANDITS;
            case BANDITS_DEFEATED -> cause == BanditEncounter.Cause.PLAYER_VICTORY
                ? BanditEncounter.Status.RESOLVED_PLAYER : BanditEncounter.Status.RESOLVED_CARAVAN;
            case CARAVAN_ESCAPED, CARAVAN_DELAYED -> BanditEncounter.Status.RESOLVED_CARAVAN;
        };
        encounter.resolve(status, cause, outcome, gameTime);
        data.markChanged();
        return applyConsequences(data, encounter, lost, gameTime, settings, contractSettings);
    }

    /** Ends an encounter without a fight (shipment or road gone, lifetime over, operator). */
    public static Resolution cancel(final KingdomsSavedData data, final BanditEncounter encounter, final BanditEncounter.Cause cause,
        final long gameTime, final ContractSettings contractSettings)
    {
        if (!encounter.open()) return Resolution.notApplied(encounter);
        if (encounter.kind() == BanditEncounter.Kind.AMBUSH && encounter.status() == BanditEncounter.Status.ACTIVE)
            data.tradeLedger().shipment(encounter.shipmentId())
                .filter(shipment -> shipment.status() == TradeShipmentStatus.IN_TRANSIT)
                .ifPresent(shipment -> shipment.releaseHold(gameTime));
        encounter.resolve(cause == BanditEncounter.Cause.LIFETIME_OVER ? BanditEncounter.Status.EXPIRED : BanditEncounter.Status.CANCELLED,
            cause, null, gameTime);
        // a roadblock that outlived its lifetime was not cleared: an accepted clear-the-road contract has failed
        final boolean missed = cause == BanditEncounter.Cause.LIFETIME_OVER && encounter.kind() == BanditEncounter.Kind.ROADBLOCK;
        final List<ContractService.Closure> closures = ContractService.onEncounterResolved(data, encounter.id(), false, false, missed,
            List.of(), gameTime, contractSettings);
        encounter.markConsequencesApplied();
        data.markChanged();
        return new Resolution(true, encounter, 0L, closures, java.util.Map.of());
    }

    /** Resolves unobserved ambushes that are due and expires roadblocks whose lifetime is over. */
    public static List<Resolution> resolveDue(final KingdomsSavedData data, final long gameTime, final BanditSettings settings,
        final ContractSettings contractSettings)
    {
        final List<Resolution> resolutions = new ArrayList<>();
        for (final BanditEncounter encounter : data.bandits().open())
        {
            if (encounter.status() != BanditEncounter.Status.ACTIVE || encounter.representation() != BanditEncounter.Representation.ABSTRACT)
                continue;
            if (encounter.kind() == BanditEncounter.Kind.AMBUSH && gameTime >= encounter.resolveAt())
                resolutions.add(resolve(data, encounter, EncounterRules.decideAbstract(encounter.seed(), encounter.remainingStrength(),
                    security(data, encounter)), BanditEncounter.Cause.ABSTRACT_ROLL, List.of(), gameTime, settings, contractSettings));
            else if (encounter.kind() == BanditEncounter.Kind.ROADBLOCK && gameTime >= encounter.expiresAt())
                resolutions.add(cancel(data, encounter, BanditEncounter.Cause.LIFETIME_OVER, gameTime, contractSettings));
        }
        return resolutions;
    }

    // ------------------------------------------------------------------------------------------------ consequences

    private static Resolution applyConsequences(final KingdomsSavedData data, final BanditEncounter encounter, final long lost,
        final long gameTime, final BanditSettings settings, final ContractSettings contractSettings)
    {
        final RoadThreat threat = data.bandits().threatFor(encounter.roadId());
        final boolean banditsWon = encounter.status() == BanditEncounter.Status.RESOLVED_BANDITS;
        final boolean defeated = encounter.outcome() == EncounterRules.Outcome.BANDITS_DEFEATED;
        if (banditsWon) threat.raided(gameTime, settings.encounterCooldownTicks());
        else if (defeated) threat.cleared(gameTime, settings.suppressionTicks(), settings.encounterCooldownTicks());
        final boolean playersWon = encounter.status() == BanditEncounter.Status.RESOLVED_PLAYER;
        final List<ContractService.Closure> closures = ContractService.onEncounterResolved(data, encounter.id(), playersWon, banditsWon,
            false, encounter.defenders(), gameTime, contractSettings);
        final java.util.Map<UUID, ReputationService.Result> reputation = new java.util.LinkedHashMap<>();
        if (playersWon)
        {
            final UUID beneficiary = beneficiaryFaction(data, encounter);
            final List<UUID> contractHolders = closures.stream().map(closure -> closure.contract().holder()).toList();
            if (beneficiary != null)
                for (final UUID defender : encounter.defenders())
                    if (!contractHolders.contains(defender))
                        reputation.put(defender, ReputationService.adjust(data, defender, beneficiary, DEFENDER_REPUTATION,
                            ReputationRegistry.Cause.ENCOUNTER_DEFENDED, encounter.id(), gameTime));
        }
        encounter.markConsequencesApplied();
        data.markChanged();
        return new Resolution(true, encounter, lost, closures, java.util.Map.copyOf(reputation));
    }

    /** Whose reputation a defence improves: the caravan owner, or for a roadblock the nearest road endpoint. */
    public static UUID beneficiaryFaction(final KingdomsSavedData data, final BanditEncounter encounter)
    {
        if (encounter.kind() == BanditEncounter.Kind.AMBUSH)
        {
            return data.tradeLedger().shipment(encounter.shipmentId())
                .flatMap(shipment -> data.colony(shipment.originColonyId())).map(NPCColonyData::factionId).orElse(null);
        }
        final RoadRecord road = data.roads().get(encounter.roadId()).orElse(null);
        if (road == null) return null;
        return nearestEndpoint(data, road, encounter.position()).flatMap(settlement -> data.colony(settlement.id()))
            .map(NPCColonyData::factionId).orElse(null);
    }

    public static Optional<SettlementRecord> nearestEndpoint(final KingdomsSavedData data, final RoadRecord road, final BlockPos position)
    {
        return java.util.stream.Stream.of(road.firstSettlementId(), road.secondSettlementId())
            .map(id -> data.settlements().get(id).orElse(null)).filter(java.util.Objects::nonNull)
            .min(Comparator.comparingDouble(settlement -> settlement.anchor().distSqr(position)));
    }

    /** Security of the road: the stronger of its two settlements. */
    public static int security(final KingdomsSavedData data, final BanditEncounter encounter)
    {
        return data.roads().get(encounter.roadId()).map(road -> Math.max(
            data.settlements().get(road.firstSettlementId()).map(value -> ThreatRules.security(value.type())).orElse(0),
            data.settlements().get(road.secondSettlementId()).map(value -> ThreatRules.security(value.type())).orElse(0))).orElse(0);
    }

    /** Where a shipment is: physical caravans report their own progress; abstract ones follow time. */
    public static double progress(final TradeShipment shipment, final long gameTime)
    {
        return shipment.representation() == ShipmentRepresentation.PHYSICAL ? shipment.storedProgress() : shipment.progressAt(gameTime);
    }
}
