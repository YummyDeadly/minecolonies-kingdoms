package com.minecolonies.kingdoms.worldevent;

import com.minecolonies.kingdoms.bandit.EncounterRules;
import com.minecolonies.kingdoms.bandit.ThreatEvaluator;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.diplomacy.DiplomacyEvaluator;
import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.military.GarrisonRecord;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.TradeRouteStatus;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementGrowthEvaluator;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementGrowthStage;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of world events (Phase 11), and the only place their effects are applied or given back.
 *
 * <p>Every effect goes through the authority that owns it:
 * <ul>
 * <li>stock and production: {@link EconomyManager};</li>
 * <li>garrison soldiers: {@link MilitaryService#eventLosses} / {@link MilitaryService#eventRecruits}, which are
 * idempotent per event ID;</li>
 * <li>relations: {@link DiplomacyService#change}, audited with cause {@code WORLD_EVENT};</li>
 * <li>population: {@link SettlementGrowthEvaluator#growPopulation} and {@link SettlementGrowthEvaluator#emigrate};</li>
 * <li>road threat: a derived contributor that {@link ThreatEvaluator} reads through {@link #threatContribution}, so
 * nothing is stored in the road threat and nothing needs to be given back.</li>
 * </ul>
 *
 * <p>Exactly once: an effect is applied only in {@link #activate}, which runs only for a PLANNED event and records the
 * amounts actually applied in the same step; a reversible effect is given back only in {@link #finish}, only if it
 * was applied and not yet given back, and never more than was applied. Planning happens at most once per evaluation
 * (the evaluation index is persisted), and its roll and choice are seeded, so a restart never re-rolls.
 */
public final class WorldEventService
{
    static final long SALT_ROLL = 101L;
    static final long SALT_CHOICE = 102L;
    static final long SALT_MAGNITUDE = 103L;
    static final long SALT_SUBJECT = 104L;
    /** Threat added to a road target during a bandit surge: 20..35. */
    public static final double SURGE_MIN = 20.0D;
    public static final double SURGE_RANGE = 15.0D;
    public static final double MAX_SURGE_CONTRIBUTION = 35.0D;
    public static final int MAX_MIGRANTS = 6;
    /** Road threat around a settlement from which volunteers muster. */
    public static final double MUSTER_THREAT = 30.0D;
    private static final EconomyManager ECONOMY = new EconomyManager();

    private WorldEventService() {}

    /** An eligible (type, subject) pair with its weight and a short explanation. */
    public record Candidate(WorldEventType type, UUID settlement, UUID other, UUID factionA, UUID factionB, UUID road,
        EconomicResource resource, ResourceLocation dimension, double weight, String reason)
    {
        public UUID subject()
        {
            return switch (type.scope())
            {
                case SETTLEMENT, MIGRATION -> settlement;
                case ROAD -> road;
                case PAIR -> pairKey(factionA, factionB);
            };
        }
    }

    public record Report(Optional<WorldEventRecord> planned, List<WorldEventRecord> activated, List<WorldEventRecord> ended, int candidates) {}

    // ------------------------------------------------------------------------------------------------ IDs and seeds

    public static UUID generatedId(final long worldSeed, final long evaluation)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-world-event:" + worldSeed + ':' + evaluation).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID adminId(final long worldSeed, final int counter)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-world-event-admin:" + worldSeed + ':' + counter).getBytes(StandardCharsets.UTF_8));
    }

    /** An order-independent key for a faction pair (cooldowns and "one event per pair"). */
    public static UUID pairKey(final UUID a, final UUID b)
    {
        final UUID first = a.compareTo(b) <= 0 ? a : b;
        final UUID second = first == a ? b : a;
        return UUID.nameUUIDFromBytes(("kingdoms-pair:" + first + ':' + second).getBytes(StandardCharsets.UTF_8));
    }

    static long evaluationSeed(final long worldSeed, final long evaluation)
    {
        return worldSeed ^ (evaluation * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
    }

    // ------------------------------------------------------------------------------------------------ update

    /** One world-event update: due transitions, then a planning evaluation if it is due, then pruning. */
    public static Report update(final KingdomsSavedData data, final long gameTime, final WorldEventSettings settings)
    {
        final List<WorldEventRecord> activated = new ArrayList<>();
        final List<WorldEventRecord> ended = new ArrayList<>();
        advance(data, gameTime, settings, activated, ended);
        Optional<WorldEventRecord> planned = Optional.empty();
        int candidates = 0;
        final WorldEventRegistry registry = data.worldEvents();
        if (registry.lastEvaluatedAt() < 0L || gameTime - registry.lastEvaluatedAt() >= settings.evaluationIntervalTicks())
        {
            final List<Candidate> eligible = settings.enabled() ? candidates(data, gameTime, settings) : List.of();
            candidates = eligible.size();
            planned = plan(data, eligible, gameTime, settings);
        }
        if (registry.prune(gameTime, settings.cooldownTicks()) > 0) data.markChanged();
        data.history().setLimit(settings.historyLimit());
        return new Report(planned, List.copyOf(activated), List.copyOf(ended), candidates);
    }

    /** Operator: due transitions and a planning evaluation now, whatever the interval (still once per evaluation index). */
    public static Report evaluateNow(final KingdomsSavedData data, final long gameTime, final WorldEventSettings settings)
    {
        final List<WorldEventRecord> activated = new ArrayList<>();
        final List<WorldEventRecord> ended = new ArrayList<>();
        advance(data, gameTime, settings, activated, ended);
        final List<Candidate> eligible = settings.enabled() ? candidates(data, gameTime, settings) : List.of();
        final Optional<WorldEventRecord> planned = plan(data, eligible, gameTime, settings);
        return new Report(planned, List.copyOf(activated), List.copyOf(ended), eligible.size());
    }

    /**
     * One planning evaluation, exactly once per evaluation index: a seeded roll against the event chance, then a seeded
     * choice of the type (weighted by the type's strongest candidate, so that many neighbour pairs do not crowd out the
     * settlement and road events), then a seeded, weighted choice of the subject within that type (in a fixed order).
     */
    static Optional<WorldEventRecord> plan(final KingdomsSavedData data, final List<Candidate> eligible, final long gameTime,
        final WorldEventSettings settings)
    {
        final WorldEventRegistry registry = data.worldEvents();
        final long evaluation = registry.evaluations();
        registry.markEvaluated(gameTime);
        data.markChanged();
        if (!settings.enabled() || eligible.isEmpty()) return Optional.empty();
        final long seed = evaluationSeed(registry.seed(), evaluation);
        if (EncounterRules.unit(seed, SALT_ROLL) >= settings.eventChance()) return Optional.empty();
        final Candidate chosen = choose(eligible, seed).orElse(null);
        if (chosen == null) return Optional.empty();
        return Optional.of(create(data, generatedId(registry.seed(), evaluation), WorldEventRecord.Cause.GENERATED, chosen, gameTime, settings));
    }

    /** Type first (weight: its strongest candidate), then the subject within the type (weight: the candidate's own). */
    public static Optional<Candidate> choose(final List<Candidate> eligible, final long seed)
    {
        final java.util.EnumMap<WorldEventType, List<Candidate>> byType = new java.util.EnumMap<>(WorldEventType.class);
        for (final Candidate candidate : eligible)
            if (candidate.weight() > 0.0D) byType.computeIfAbsent(candidate.type(), type -> new ArrayList<>()).add(candidate);
        if (byType.isEmpty()) return Optional.empty();
        final List<WorldEventType> types = new ArrayList<>(byType.keySet());
        final List<Double> typeWeights = types.stream()
            .map(type -> byType.get(type).stream().mapToDouble(Candidate::weight).max().orElse(0.0D)).toList();
        final WorldEventType type = types.get(pick(typeWeights, EncounterRules.unit(seed, SALT_CHOICE)));
        final List<Candidate> subjects = byType.get(type);
        return Optional.of(subjects.get(pick(subjects.stream().map(Candidate::weight).toList(), EncounterRules.unit(seed, SALT_SUBJECT))));
    }

    private static int pick(final List<Double> weights, final double unit)
    {
        final double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        double remaining = unit * total;
        for (int index = 0; index < weights.size(); index++)
        {
            remaining -= weights.get(index);
            if (remaining < 0.0D) return index;
        }
        return weights.size() - 1;
    }

    private static WorldEventRecord create(final KingdomsSavedData data, final UUID id, final WorldEventRecord.Cause cause, final Candidate chosen,
        final long gameTime, final WorldEventSettings settings)
    {
        final long seed = EncounterRules.seed(id);
        final long startsAt = gameTime + settings.noticeTicks();
        final WorldEventRecord record = new WorldEventRecord(id, chosen.type(), cause, seed, chosen.dimension(), chosen.settlement(),
            chosen.other(), chosen.factionA(), chosen.factionB(), chosen.road(), chosen.resource(), EncounterRules.unit(seed, SALT_MAGNITUDE),
            gameTime, startsAt, startsAt + settings.durationTicks(), chosen.reason());
        final WorldEventRegistry registry = data.worldEvents();
        if (!registry.put(record)) return registry.event(id).orElseThrow(); // the same evaluation again: the same event
        registry.planned(chosen.type(), chosen.subject(), gameTime);
        data.markChanged();
        return record;
    }

    /** Due transitions: PLANNED events whose time came, ACTIVE events whose time is over or whose subject is gone. */
    static void advance(final KingdomsSavedData data, final long gameTime, final WorldEventSettings settings,
        final List<WorldEventRecord> activated, final List<WorldEventRecord> ended)
    {
        final List<WorldEventRecord> open = new ArrayList<>(data.worldEvents().open());
        open.sort(Comparator.comparingLong(WorldEventRecord::plannedAt).thenComparing(WorldEventRecord::id));
        for (final WorldEventRecord event : open)
        {
            if (event.status() == WorldEventRecord.Status.PLANNED && gameTime >= event.startsAt())
            {
                if (activate(data, event, gameTime, settings)) activated.add(event);
                else ended.add(event);
            }
            if (event.status() == WorldEventRecord.Status.ACTIVE)
            {
                if (gameTime >= event.endsAt())
                {
                    finish(data, event, WorldEventRecord.Status.RESOLVED, gameTime, null);
                    ended.add(event);
                }
                else if (event.type().reversible() && subjectGone(data, event))
                {
                    finish(data, event, WorldEventRecord.Status.CANCELLED, gameTime, "its subject no longer exists");
                    ended.add(event);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ eligibility

    /** Every eligible (type, subject) pair that the caps and cooldowns allow now, in a fixed order. */
    public static List<Candidate> candidates(final KingdomsSavedData data, final long gameTime, final WorldEventSettings settings)
    {
        final List<Candidate> all = new ArrayList<>();
        if (data.worldEvents().open().size() >= settings.maxActiveGlobal()) return all;
        final List<SettlementRecord> settlements = new ArrayList<>(data.settlements().records());
        settlements.sort(Comparator.comparing(SettlementRecord::id));
        for (final SettlementRecord settlement : settlements)
        {
            final NPCColonyData colony = data.colony(settlement.id()).orElse(null);
            if (colony == null || colony.kind() != ColonyKind.NPC_ABSTRACT) continue;
            harvestFailure(settlement, colony).ifPresent(all::add);
            tradeFair(data, settlement, colony).ifPresent(all::add);
            final GarrisonRecord garrison = data.military().garrison(settlement.id()).orElse(null);
            if (garrison != null)
            {
                garrisonFever(settlement, garrison).ifPresent(all::add);
                militiaMuster(data, settlement, colony, garrison, gameTime).ifPresent(all::add);
            }
            migration(data, settlement, colony, garrison, gameTime, settings).ifPresent(all::add);
        }
        final List<RoadRecord> roads = new ArrayList<>(data.roads().roads());
        roads.sort(Comparator.comparing(RoadRecord::id));
        for (final RoadRecord road : roads) banditSurge(data, road, gameTime).ifPresent(all::add);
        for (final DiplomacyEvaluator.Pair pair : DiplomacyEvaluator.neighbourPairs(data))
        {
            final Faction a = data.faction(pair.first()).orElse(null);
            final Faction b = data.faction(pair.second()).orElse(null);
            if (a == null || b == null || !DiplomacyEvaluator.participates(a) || !DiplomacyEvaluator.participates(b)) continue;
            if (WarService.openWarBetween(data, a.id(), b.id()).isPresent()) continue; // no incidents or envoys in a war
            final int relation = DiplomacyService.relation(a, b);
            final ResourceLocation dimension = data.colony(a.capitalColonyId()).map(NPCColonyData::dimension).orElse(null);
            if (relation > -80 && data.war().state().truceUntil(a.id(), b.id()) <= gameTime)
                all.add(new Candidate(WorldEventType.BORDER_INCIDENT, null, null, a.id(), b.id(), null, null, dimension,
                    incidentWeight(relation), "neighbours " + a.name() + " and " + b.name() + " (relation " + relation + ")"));
            if (relation < 60)
                all.add(new Candidate(WorldEventType.ENVOY_VISIT, null, null, a.id(), b.id(), null, null, dimension,
                    WorldEventType.ENVOY_VISIT.weight() * (relation < -20 ? 2.0D : 1.0D),
                    "neighbours " + a.name() + " and " + b.name() + " (relation " + relation + ")"));
        }
        return all.stream().filter(candidate -> allowed(data, candidate, gameTime, settings)).toList();
    }

    /**
     * Incidents happen more often where there is already tension (x1 at relation 0 or better, x2 at -30, x3 at -60), so
     * that tense neighbours can drift towards the hostility the war evaluation looks for; envoys (twice as likely below
     * -20) pull the other way. Without this, relations fall only through operator edits and wars never start by themselves.
     */
    public static double incidentWeight(final int relation)
    {
        return WorldEventType.BORDER_INCIDENT.weight() * (1.0D + Math.max(0, -relation) / 30.0D);
    }

    private static Optional<Candidate> harvestFailure(final SettlementRecord settlement, final NPCColonyData colony)
    {
        final var food = colony.economy().resource(EconomicResource.FOOD);
        if (colony.population() < 5 || food.flow().productionPerDay() <= 0.0D || food.stockpile().amount() <= 0L) return Optional.empty();
        return Optional.of(new Candidate(WorldEventType.HARVEST_FAILURE, settlement.id(), null, null, null, null, EconomicResource.FOOD,
            settlement.dimension(), WorldEventType.HARVEST_FAILURE.weight(), String.format(Locale.ROOT, "%s grows food (%.1f/day, stock %d)",
                settlement.name(), food.flow().productionPerDay(), food.stockpile().amount())));
    }

    private static Optional<Candidate> tradeFair(final KingdomsSavedData data, final SettlementRecord settlement, final NPCColonyData colony)
    {
        final long routes = data.tradeLedger().routes().stream().filter(route -> route.status() == TradeRouteStatus.ACTIVE
            && (route.originColonyId().equals(colony.id()) || route.destinationColonyId().equals(colony.id()))).count();
        if (routes == 0L) return Optional.empty();
        EconomicResource main = null;
        double best = 0.0D;
        for (final EconomicResource resource : EconomicResource.values())
        {
            final double production = colony.economy().resource(resource).flow().productionPerDay();
            if (production > best)
            {
                best = production;
                main = resource;
            }
        }
        if (main == null) return Optional.empty();
        return Optional.of(new Candidate(WorldEventType.TRADE_FAIR, settlement.id(), null, null, null, null, main, settlement.dimension(),
            WorldEventType.TRADE_FAIR.weight(), String.format(Locale.ROOT, "%s trades on %d route(s); main product %s (%.1f/day)",
                settlement.name(), routes, main.name().toLowerCase(Locale.ROOT), best)));
    }

    private static Optional<Candidate> garrisonFever(final SettlementRecord settlement, final GarrisonRecord garrison)
    {
        if (garrison.strength() < 4) return Optional.empty();
        return Optional.of(new Candidate(WorldEventType.GARRISON_FEVER, settlement.id(), null, null, null, null, null, settlement.dimension(),
            WorldEventType.GARRISON_FEVER.weight(), settlement.name() + " keeps " + garrison.strength() + " soldiers in its barracks"));
    }

    private static Optional<Candidate> militiaMuster(final KingdomsSavedData data, final SettlementRecord settlement, final NPCColonyData colony,
        final GarrisonRecord garrison, final long gameTime)
    {
        if (garrison.strength() + garrison.detached() >= garrison.capacity()) return Optional.empty();
        final double danger = data.roads().incident(settlement.id()).stream().mapToDouble(road -> data.bandits().threatOf(road.id()))
            .max().orElse(0.0D);
        final boolean atWar = WarService.openWarOf(data, colony.factionId()).isPresent();
        final boolean shaken = garrison.alertAt(gameTime) || garrison.vulnerableAt(gameTime);
        if (danger < MUSTER_THREAT && !atWar && !shaken) return Optional.empty();
        final String why = atWar ? "its faction is at war" : shaken ? "it was attacked recently"
            : String.format(Locale.ROOT, "a road nearby has threat %.0f", danger);
        return Optional.of(new Candidate(WorldEventType.MILITIA_MUSTER, settlement.id(), null, null, null, null, null, settlement.dimension(),
            WorldEventType.MILITIA_MUSTER.weight() + (atWar ? 1.0D : 0.0D), settlement.name() + " is endangered: " + why));
    }

    private static Optional<Candidate> migration(final KingdomsSavedData data, final SettlementRecord settlement, final NPCColonyData colony,
        final GarrisonRecord garrison, final long gameTime, final WorldEventSettings settings)
    {
        if (colony.population() <= settlement.type().minimumPopulation() + 2) return Optional.empty();
        final boolean hungry = colony.needs().stream().anyMatch(need -> need.type() == NeedType.FOOD_SHORTAGE
            && need.severity().ordinal() >= NeedSeverity.HIGH.ordinal());
        final boolean sacked = garrison != null && garrison.vulnerableAt(gameTime);
        if (!hungry && !sacked) return Optional.empty();
        final List<SettlementRecord> neighbours = new ArrayList<>();
        for (final RoadRecord road : data.roads().incident(settlement.id()))
            data.settlements().get(road.other(settlement.id())).ifPresent(neighbours::add);
        neighbours.sort(Comparator.comparing(SettlementRecord::id));
        for (final SettlementRecord neighbour : neighbours)
        {
            final NPCColonyData destination = data.colony(neighbour.id()).orElse(null);
            if (destination == null || destination.kind() != ColonyKind.NPC_ABSTRACT || destination.id().equals(colony.id())) continue;
            if (WarService.atWar(data, colony.factionId(), destination.factionId())) continue;
            if (!SettlementGrowthEvaluator.populationRoom(new SettlementGrowthEvaluator(), destination, stage(data, neighbour.id()), neighbour,
                settings.populationHardCap())) continue;
            return Optional.of(new Candidate(WorldEventType.MIGRATION, settlement.id(), neighbour.id(), null, null, null, null,
                settlement.dimension(), WorldEventType.MIGRATION.weight(), settlement.name() + (hungry ? " is hungry" : " was sacked")
                    + " and " + neighbour.name() + " has room"));
        }
        return Optional.empty();
    }

    private static Optional<Candidate> banditSurge(final KingdomsSavedData data, final RoadRecord road, final long gameTime)
    {
        if (!ThreatEvaluator.eligible(road)) return Optional.empty();
        final var threat = data.bandits().threat(road.id()).orElse(null);
        if (threat != null && threat.suppressedAt(gameTime)) return Optional.empty(); // bandits were just beaten here
        final double level = threat == null ? 0.0D : threat.threat();
        final String first = data.settlements().get(road.firstSettlementId()).map(SettlementRecord::name).orElse("?");
        final String second = data.settlements().get(road.secondSettlementId()).map(SettlementRecord::name).orElse("?");
        return Optional.of(new Candidate(WorldEventType.BANDIT_SURGE, null, null, null, null, road.id(), null, road.dimension(),
            WorldEventType.BANDIT_SURGE.weight() * (1.0D + level / 50.0D),
            String.format(Locale.ROOT, "road %s - %s (threat %.0f)", first, second, level)));
    }

    /** Caps and cooldowns: global and per-settlement open events, one open event per road and pair, and per-subject cooldowns. */
    static boolean allowed(final KingdomsSavedData data, final Candidate candidate, final long gameTime, final WorldEventSettings settings)
    {
        final WorldEventRegistry registry = data.worldEvents();
        final List<WorldEventRecord> open = registry.open();
        if (open.size() >= settings.maxActiveGlobal()) return false;
        final long last = registry.lastPlanned(candidate.type(), candidate.subject());
        if (last != Long.MIN_VALUE && gameTime - last < settings.cooldownTicks()) return false;
        for (final UUID settlement : new UUID[] {candidate.settlement(), candidate.other()})
            if (settlement != null && open.stream().filter(event -> event.involves(settlement)).count() >= settings.maxActivePerSettlement())
                return false;
        if (candidate.road() != null && open.stream().anyMatch(event -> candidate.road().equals(event.roadId()))) return false;
        if (candidate.factionA() != null && open.stream().anyMatch(event -> event.factionA() != null
            && pairKey(event.factionA(), event.factionB()).equals(pairKey(candidate.factionA(), candidate.factionB())))) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ transitions

    /**
     * PLANNED → ACTIVE with the effect applied once, or → EXPIRED if the subject is no longer valid. Returns whether it
     * became active.
     */
    static boolean activate(final KingdomsSavedData data, final WorldEventRecord event, final long gameTime, final WorldEventSettings settings)
    {
        if (event.status() != WorldEventRecord.Status.PLANNED) return false;
        final String invalid = invalidReason(data, event);
        if (invalid != null)
        {
            event.end(WorldEventRecord.Status.EXPIRED, gameTime, invalid);
            data.markChanged();
            WorldHistory.record(data, WorldHistory.Entry.of(WorldHistory.Kind.EVENT_ENDED, event.id(), gameTime, primary(event), secondary(event),
                event.type().displayName() + " did not happen: " + invalid));
            return false;
        }
        event.activate();
        final String effect = applyEffect(data, event, gameTime, settings);
        data.markChanged();
        WorldHistory.record(data, WorldHistory.Entry.of(WorldHistory.Kind.EVENT_STARTED, event.id(), gameTime, primary(event), secondary(event),
            event.type().displayName() + " at " + subjectName(data, event) + ": " + effect));
        return true;
    }

    /** Applies the effect exactly once through the owning authority and records what was actually applied. */
    private static String applyEffect(final KingdomsSavedData data, final WorldEventRecord event, final long gameTime,
        final WorldEventSettings settings)
    {
        final double m = event.magnitude();
        switch (event.type())
        {
            case HARVEST_FAILURE ->
            {
                final NPCColonyData colony = data.colony(event.settlementId()).orElseThrow();
                final EconomicResource resource = event.resource() == null ? EconomicResource.FOOD : event.resource();
                final long stock = colony.economy().resource(resource).stockpile().amount();
                final long lost = (long) Math.floor(stock * (0.15D + 0.20D * m));
                if (lost > 0L) ECONOMY.setStockpile(colony, resource, stock - lost);
                final double delta = changeProduction(colony, resource, -colony.economy().resource(resource).flow().productionPerDay() * (0.25D + 0.15D * m));
                event.applied(delta, lost, 0, 0, 0);
                return String.format(Locale.ROOT, "%d %s spoiled; production %.1f/day while it lasts", lost, name(resource), delta);
            }
            case TRADE_FAIR ->
            {
                final NPCColonyData colony = data.colony(event.settlementId()).orElseThrow();
                final EconomicResource resource = event.resource() == null ? EconomicResource.FOOD : event.resource();
                final double delta = changeProduction(colony, resource, colony.economy().resource(resource).flow().productionPerDay() * (0.30D + 0.20D * m));
                event.applied(delta, 0L, 0, 0, 0);
                return String.format(Locale.ROOT, "%s production +%.1f/day while it lasts", name(resource), delta);
            }
            case BANDIT_SURGE ->
            {
                event.applied(0.0D, 0L, 0, 0, 0); // derived: read by the threat evaluation while active
                return String.format(Locale.ROOT, "road threat target +%.0f while it lasts", surge(event));
            }
            case GARRISON_FEVER ->
            {
                final int strength = data.military().garrison(event.settlementId()).map(GarrisonRecord::strength).orElse(0);
                final int lost = MilitaryService.eventLosses(data, event.settlementId(), event.id(),
                    Math.max(1, (int) Math.round(strength * (0.20D + 0.15D * m))));
                event.applied(0.0D, 0L, -lost, 0, 0);
                return lost + " soldier(s) lost to fever";
            }
            case MILITIA_MUSTER ->
            {
                final int joined = MilitaryService.eventRecruits(data, event.settlementId(), event.id(), 2 + (int) Math.floor(3.0D * m));
                event.applied(0.0D, 0L, joined, 0, 0);
                return joined + " volunteer(s) joined the garrison";
            }
            case BORDER_INCIDENT, ENVOY_VISIT ->
            {
                final Faction a = data.faction(event.factionA()).orElseThrow();
                final Faction b = data.faction(event.factionB()).orElseThrow();
                final int wanted = event.type() == WorldEventType.BORDER_INCIDENT ? -(10 + (int) Math.round(10.0D * m)) : 8 + (int) Math.round(8.0D * m);
                final int change = DiplomacyService.change(data, a, b, wanted, DiplomacyState.Cause.WORLD_EVENT, event.type().ordinal(), gameTime)
                    .map(value -> value.after() - value.before()).orElse(0);
                event.applied(0.0D, 0L, 0, change, 0);
                return String.format(Locale.ROOT, "relation %s %+d (now %d)", a.name() + " / " + b.name(), change, DiplomacyService.relation(a, b));
            }
            case MIGRATION ->
            {
                final int moved = migrate(data, event, m, settings.populationHardCap());
                event.applied(0.0D, 0L, 0, 0, moved);
                return moved + " people moved to " + data.settlements().get(event.otherSettlementId()).map(SettlementRecord::name).orElse("?");
            }
        }
        throw new IllegalStateException("Unhandled world event type " + event.type());
    }

    /** Changes production through the economy authority and returns the change actually made (production never goes below 0). */
    private static double changeProduction(final NPCColonyData colony, final EconomicResource resource, final double wanted)
    {
        final double before = colony.economy().resource(resource).flow().productionPerDay();
        ECONOMY.adjustProduction(colony, resource, Double.isFinite(wanted) ? wanted : 0.0D);
        return colony.economy().resource(resource).flow().productionPerDay() - before;
    }

    /**
     * Moves people once: the destination takes them one by one through the growth population rule, then exactly as many
     * leave the source (never below its minimum). Nobody is created or lost.
     */
    private static int migrate(final KingdomsSavedData data, final WorldEventRecord event, final double m, final int hardCap)
    {
        final NPCColonyData source = data.colony(event.settlementId()).orElseThrow();
        final SettlementRecord sourceSettlement = data.settlements().get(event.settlementId()).orElseThrow();
        final NPCColonyData destination = data.colony(event.otherSettlementId()).orElseThrow();
        final SettlementRecord destinationSettlement = data.settlements().get(event.otherSettlementId()).orElseThrow();
        final int wanted = Math.min(MAX_MIGRANTS, Math.max(1, (int) Math.round(source.population() * (0.05D + 0.05D * m))));
        final int allowed = Math.min(wanted, Math.max(0, source.population() - sourceSettlement.type().minimumPopulation()));
        final SettlementGrowthEvaluator evaluator = new SettlementGrowthEvaluator();
        final SettlementGrowthStage stage = stage(data, destinationSettlement.id());
        int arrived = 0;
        while (arrived < allowed && SettlementGrowthEvaluator.growPopulation(evaluator, destination, stage, destinationSettlement, hardCap)) arrived++;
        final int left = SettlementGrowthEvaluator.emigrate(source, sourceSettlement, arrived);
        if (left != arrived) throw new IllegalStateException("Migration would create people: " + arrived + " arrived, " + left + " left");
        return arrived;
    }

    /**
     * ACTIVE → RESOLVED/CANCELLED (or PLANNED → CANCELLED): a reversible effect is given back once, never more than was
     * applied. Returns false if the event had already ended.
     */
    static boolean finish(final KingdomsSavedData data, final WorldEventRecord event, final WorldEventRecord.Status terminal, final long gameTime,
        final String why)
    {
        if (!event.open()) return false;
        String result = why == null ? "" : why;
        if (event.type().reversible() && event.applied() && !event.reverted())
        {
            // flag first: if giving back failed half-way, it is never tried again (a lost effect, never a doubled one)
            event.markReverted();
            final String restored = revert(data, event);
            result = result.isEmpty() ? restored : result + "; " + restored;
        }
        if (result.isEmpty()) result = event.status() == WorldEventRecord.Status.PLANNED ? "called off" : "over";
        event.end(terminal, gameTime, result);
        data.markChanged();
        WorldHistory.record(data, WorldHistory.Entry.of(WorldHistory.Kind.EVENT_ENDED, event.id(), gameTime, primary(event), secondary(event),
            event.type().displayName() + " at " + subjectName(data, event) + " " + terminal.name().toLowerCase(Locale.ROOT) + ": " + result));
        return true;
    }

    private static String revert(final KingdomsSavedData data, final WorldEventRecord event)
    {
        return switch (event.type())
        {
            case HARVEST_FAILURE, TRADE_FAIR -> data.colony(event.settlementId()).filter(colony -> colony.kind() == ColonyKind.NPC_ABSTRACT)
                .map(colony -> {
                    final EconomicResource resource = event.resource() == null ? EconomicResource.FOOD : event.resource();
                    final double restored = changeProduction(colony, resource, -event.productionDelta());
                    return String.format(Locale.ROOT, "%s production back by %+.1f/day", name(resource), restored);
                }).orElse("nothing to restore (settlement gone)");
            case BANDIT_SURGE -> "the extra bandits dispersed";
            default -> "";
        };
    }

    /** Operator: end an open event now (active: resolved with its effect given back; planned: called off). */
    public static boolean resolveNow(final KingdomsSavedData data, final WorldEventRecord event, final long gameTime)
    {
        if (!event.open()) return false;
        if (event.status() == WorldEventRecord.Status.PLANNED)
            return finish(data, event, WorldEventRecord.Status.CANCELLED, gameTime, "called off by an operator");
        return finish(data, event, WorldEventRecord.Status.RESOLVED, gameTime, "ended by an operator");
    }

    public static boolean cancel(final KingdomsSavedData data, final WorldEventRecord event, final long gameTime)
    {
        return finish(data, event, WorldEventRecord.Status.CANCELLED, gameTime, "cancelled by an operator");
    }

    /** Operator: plan an event of this type for this subject now (the subject must be eligible unless {@code force}). */
    public static Optional<WorldEventRecord> trigger(final KingdomsSavedData data, final WorldEventType type, final UUID subject, final UUID other,
        final long gameTime, final WorldEventSettings settings, final boolean force)
    {
        final List<Candidate> pool = new ArrayList<>(force ? candidatesIgnoringCaps(data, gameTime, settings) : candidates(data, gameTime, settings));
        for (final Candidate candidate : pool)
        {
            if (candidate.type() != type) continue;
            final boolean matches = switch (type.scope())
            {
                case SETTLEMENT -> subject.equals(candidate.settlement());
                case ROAD -> subject.equals(candidate.road());
                case PAIR -> (subject.equals(candidate.factionA()) && (other == null || other.equals(candidate.factionB())))
                    || (subject.equals(candidate.factionB()) && (other == null || other.equals(candidate.factionA())));
                case MIGRATION -> subject.equals(candidate.settlement()) && (other == null || other.equals(candidate.other()));
            };
            if (!matches) continue;
            final WorldEventRegistry registry = data.worldEvents();
            return Optional.of(create(data, adminId(registry.seed(), registry.nextAdminCounter()), WorldEventRecord.Cause.ADMIN, candidate,
                gameTime, settings));
        }
        return Optional.empty();
    }

    private static List<Candidate> candidatesIgnoringCaps(final KingdomsSavedData data, final long gameTime, final WorldEventSettings settings)
    {
        final WorldEventSettings open = new WorldEventSettings(true, settings.evaluationIntervalTicks(), settings.eventChance(), Integer.MAX_VALUE,
            Integer.MAX_VALUE, 0L, settings.noticeTicks(), settings.durationTicks(), settings.historyLimit(), settings.populationHardCap());
        return candidates(data, gameTime, open);
    }

    // ------------------------------------------------------------------------------------------------ queries

    /** Threat added to a road's target by active bandit surges (read by {@link ThreatEvaluator}). */
    public static double threatContribution(final KingdomsSavedData data, final UUID roadId, final long gameTime)
    {
        double total = 0.0D;
        for (final WorldEventRecord event : data.worldEvents().open())
            if (event.type() == WorldEventType.BANDIT_SURGE && roadId.equals(event.roadId()) && event.activeAt(gameTime)) total += surge(event);
        return Math.min(MAX_SURGE_CONTRIBUTION, total);
    }

    public static double surge(final WorldEventRecord event)
    {
        return SURGE_MIN + SURGE_RANGE * event.magnitude();
    }

    /** Open events that concern a settlement: its own, its roads', and its faction's pairs (for citizens' news). */
    public static List<WorldEventRecord> news(final KingdomsSavedData data, final UUID settlementId)
    {
        final UUID faction = data.colony(settlementId).map(NPCColonyData::factionId).orElse(null);
        final List<UUID> roads = data.roads().incident(settlementId).stream().map(RoadRecord::id).toList();
        return data.worldEvents().open().stream().filter(event -> event.involves(settlementId)
            || (event.roadId() != null && roads.contains(event.roadId()))
            || (faction != null && (faction.equals(event.factionA()) || faction.equals(event.factionB()))))
            .sorted(Comparator.comparingLong(WorldEventRecord::plannedAt)).toList();
    }

    /** Why an event can no longer happen, or null. */
    static String invalidReason(final KingdomsSavedData data, final WorldEventRecord event)
    {
        switch (event.type().scope())
        {
            case SETTLEMENT ->
            {
                final NPCColonyData colony = data.colony(event.settlementId()).orElse(null);
                if (colony == null || colony.kind() != ColonyKind.NPC_ABSTRACT) return "the settlement is gone";
                if ((event.type() == WorldEventType.GARRISON_FEVER || event.type() == WorldEventType.MILITIA_MUSTER)
                    && data.military().garrison(event.settlementId()).isEmpty()) return "the settlement has no garrison";
            }
            case ROAD ->
            {
                final RoadRecord road = data.roads().get(event.roadId()).orElse(null);
                if (road == null || !ThreatEvaluator.eligible(road)) return "the road is no longer usable";
            }
            case PAIR ->
            {
                if (data.faction(event.factionA()).isEmpty() || data.faction(event.factionB()).isEmpty()) return "a faction is gone";
                if (WarService.openWarBetween(data, event.factionA(), event.factionB()).isPresent()) return "the factions are at war";
            }
            case MIGRATION ->
            {
                final NPCColonyData source = data.colony(event.settlementId()).orElse(null);
                final NPCColonyData destination = data.colony(event.otherSettlementId()).orElse(null);
                if (source == null || destination == null || source.kind() != ColonyKind.NPC_ABSTRACT || destination.kind() != ColonyKind.NPC_ABSTRACT
                    || data.settlements().get(event.settlementId()).isEmpty() || data.settlements().get(event.otherSettlementId()).isEmpty())
                    return "a settlement is gone";
                if (WarService.atWar(data, source.factionId(), destination.factionId())) return "the settlements are at war";
            }
        }
        return null;
    }

    private static boolean subjectGone(final KingdomsSavedData data, final WorldEventRecord event)
    {
        return switch (event.type().scope())
        {
            case SETTLEMENT, MIGRATION -> data.colony(event.settlementId()).isEmpty();
            case ROAD -> data.roads().get(event.roadId()).isEmpty();
            case PAIR -> data.faction(event.factionA()).isEmpty() || data.faction(event.factionB()).isEmpty();
        };
    }

    static SettlementGrowthStage stage(final KingdomsSavedData data, final UUID settlementId)
    {
        return data.growth().state(settlementId).map(value -> value.stage()).orElse(SettlementGrowthStage.STARTER);
    }

    private static UUID primary(final WorldEventRecord event)
    {
        return switch (event.type().scope())
        {
            case SETTLEMENT, MIGRATION -> event.settlementId();
            case ROAD -> event.roadId();
            case PAIR -> event.factionA();
        };
    }

    private static UUID secondary(final WorldEventRecord event)
    {
        return switch (event.type().scope())
        {
            case MIGRATION -> event.otherSettlementId();
            case PAIR -> event.factionB();
            default -> null;
        };
    }

    public static String subjectName(final KingdomsSavedData data, final WorldEventRecord event)
    {
        return switch (event.type().scope())
        {
            case SETTLEMENT -> data.settlements().get(event.settlementId()).map(SettlementRecord::name).orElse(String.valueOf(event.settlementId()));
            case MIGRATION -> data.settlements().get(event.settlementId()).map(SettlementRecord::name).orElse("?") + " → "
                + data.settlements().get(event.otherSettlementId()).map(SettlementRecord::name).orElse("?");
            case ROAD -> data.roads().get(event.roadId()).map(road -> "the road "
                + data.settlements().get(road.firstSettlementId()).map(SettlementRecord::name).orElse("?") + " - "
                + data.settlements().get(road.secondSettlementId()).map(SettlementRecord::name).orElse("?")).orElse(String.valueOf(event.roadId()));
            case PAIR -> data.faction(event.factionA()).map(Faction::name).orElse("?") + " / " + data.faction(event.factionB()).map(Faction::name).orElse("?");
        };
    }

    private static String name(final EconomicResource resource)
    {
        return resource.name().toLowerCase(Locale.ROOT);
    }
}
