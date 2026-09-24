package com.minecolonies.kingdoms.military;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.bandit.BanditCamp;
import com.minecolonies.kingdoms.bandit.BanditEncounter;
import com.minecolonies.kingdoms.bandit.BanditSettings;
import com.minecolonies.kingdoms.bandit.EncounterRules;
import com.minecolonies.kingdoms.bandit.EncounterService;
import com.minecolonies.kingdoms.bandit.ThreatRules;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The only writer of garrisons (Phase 9). One coarse evaluation per interval: every NPC settlement's garrison recruits
 * towards its capacity, its security is recomputed from strategic state, and garrisons may send one patrol against a
 * nearby bandit camp. Strategic casualties are applied only here and only for an identified resolution (a bandit
 * encounter or a patrol), each at most once per garrison.
 */
public final class MilitaryService
{
    /** Defence momentum fades one point per day without a new defence. */
    public static final long DEFENCE_DECAY_TICKS = 24_000L;
    /** A garrison that lost soldiers stays on alert this long (no patrols meanwhile). */
    public static final long ALERT_TICKS = 6_000L;

    private MilitaryService() {}

    /** {@code resolutions}: bandit encounters settled by patrols (for notifications). */
    public record Report(int garrisons, int recruited, int sorties, int campsCleared, List<EncounterService.Resolution> resolutions) {}

    // ------------------------------------------------------------------------------------------------ evaluation

    public static Report evaluate(final KingdomsSavedData data, final long gameTime, final SecuritySettings settings,
        final BanditSettings bandits, final ContractSettings contracts)
    {
        return evaluate(data, gameTime, settings, bandits, contracts, encounter -> false);
    }

    /**
     * {@code observed}: runtime knowledge of the physical world (players near the fight, bandits held or suppressed after
     * a stall); a patrol never targets such a fight. Each garrison is evaluated in isolation: one that fails is logged and
     * skipped, the others (and patrols already made) stand.
     */
    public static Report evaluate(final KingdomsSavedData data, final long gameTime, final SecuritySettings settings,
        final BanditSettings bandits, final ContractSettings contracts, final Predicate<BanditEncounter> observed)
    {
        final MilitaryRegistry registry = data.military();
        final Set<UUID> settlements = new HashSet<>();
        int recruited = 0;
        final long last = registry.lastEvaluatedAt();
        final double days = last < 0L ? 0.0D : Math.max(0L, gameTime - last) / (double) SecurityRules.DAY_TICKS;
        final Index index = Index.of(data);
        for (final SettlementRecord settlement : sortedSettlements(data))
        {
            final NPCColonyData colony = data.colony(settlement.id()).filter(value -> value.kind() == ColonyKind.NPC_ABSTRACT).orElse(null);
            if (colony == null) continue;
            settlements.add(settlement.id());
            try
            {
                recruited += evaluateGarrison(registry, index, settlement, colony, days, gameTime, settings);
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Security evaluation of settlement {} failed", settlement.id(), exception);
            }
        }
        registry.retain(settlements);
        int sorties = 0;
        int cleared = 0;
        final List<EncounterService.Resolution> resolutions = new ArrayList<>();
        if (settings.enabled() && bandits.enabled())
        {
            for (final SettlementRecord settlement : sortedSettlements(data))
            {
                final GarrisonRecord garrison = registry.garrison(settlement.id()).orElse(null);
                if (garrison == null || !sortieReady(garrison, gameTime, settings)) continue;
                try
                {
                    final Optional<BanditEncounter> target = sortieTarget(data, settlement, settings, observed);
                    if (target.isEmpty()) continue;
                    final SortieResult result = sortie(data, settlement, garrison, target.get(), gameTime, bandits, contracts);
                    sorties++;
                    if (result.resolution() != null && result.resolution().applied())
                    {
                        cleared++;
                        resolutions.add(result.resolution());
                    }
                }
                catch (RuntimeException exception)
                {
                    KingdomsMod.LOGGER.error("Patrol of settlement {} failed", settlement.id(), exception);
                }
            }
        }
        registry.setLastEvaluatedAt(gameTime);
        data.markChanged();
        return new Report(settlements.size(), recruited, sorties, cleared, List.copyOf(resolutions));
    }

    /** Completed CIVIC buildings and the worst road threat per settlement, built in one pass per evaluation. */
    private record Index(Map<UUID, Integer> civic, Map<UUID, Double> worstThreat)
    {
        static Index of(final KingdomsSavedData data)
        {
            final Map<UUID, Integer> civic = new HashMap<>();
            for (final SettlementBuildingRecord building : data.growth().buildings())
                if (building.type() == SettlementBuildingType.CIVIC && building.status() == SettlementBuildingStatus.COMPLETED)
                    civic.merge(building.settlementId(), 1, Integer::sum);
            final Map<UUID, Double> worst = new HashMap<>();
            for (final RoadRecord road : data.roads().roads())
            {
                final double threat = data.bandits().threatOf(road.id());
                worst.merge(road.firstSettlementId(), threat, Math::max);
                worst.merge(road.secondSettlementId(), threat, Math::max);
            }
            return new Index(civic, worst);
        }
    }

    private static int evaluateGarrison(final MilitaryRegistry registry, final Index index, final SettlementRecord settlement,
        final NPCColonyData colony, final double days, final long gameTime, final SecuritySettings settings)
    {
        final int civic = index.civic().getOrDefault(settlement.id(), 0);
        final int capacity = SecurityRules.capacity(settlement.type(), colony.population(), civic);
        final GarrisonRecord garrison = registry.garrison(settlement.id()).orElseGet(() -> {
            final GarrisonRecord created = new GarrisonRecord(settlement.id(), Math.min(capacity, Math.max(0, colony.soldiers())), capacity);
            registry.put(created);
            return created;
        });
        final int recruited = settings.enabled() ? garrison.recruit(days * SecurityRules.recruitPerDay(civic, foodCritical(colony),
            settings.recruitmentMultiplier())) : 0;
        garrison.decayDefences(gameTime, DEFENCE_DECAY_TICKS);
        final SecurityRules.Breakdown breakdown = SecurityRules.security(settlement.type(), garrison.strength(), capacity, civic,
            garrison.recentDefences(), index.worstThreat().getOrDefault(settlement.id(), 0.0D), garrison.vulnerableAt(gameTime));
        garrison.evaluated(capacity, breakdown.total(), gameTime);
        return recruited;
    }

    public static SecurityRules.Breakdown breakdown(final KingdomsSavedData data, final SettlementRecord settlement, final GarrisonRecord garrison,
        final int civic, final long gameTime)
    {
        double worst = 0.0D;
        for (final RoadRecord road : data.roads().incident(settlement.id())) worst = Math.max(worst, data.bandits().threatOf(road.id()));
        return SecurityRules.security(settlement.type(), garrison.strength(), garrison.capacity(), civic, garrison.recentDefences(), worst,
            garrison.vulnerableAt(gameTime));
    }

    public static int civicBuildings(final KingdomsSavedData data, final UUID settlementId)
    {
        return (int) data.growth().forSettlement(settlementId).stream()
            .filter(building -> building.type() == SettlementBuildingType.CIVIC && building.status() == SettlementBuildingStatus.COMPLETED)
            .count();
    }

    private static boolean foodCritical(final NPCColonyData colony)
    {
        return colony.needs().stream().anyMatch(need -> need.type() == NeedType.FOOD_SHORTAGE && need.severity() == NeedSeverity.CRITICAL);
    }

    private static List<SettlementRecord> sortedSettlements(final KingdomsSavedData data)
    {
        return data.settlements().records().stream().sorted(Comparator.comparing(SettlementRecord::id)).toList();
    }

    // ------------------------------------------------------------------------------------------------ road security

    /**
     * The security level (0..5) a settlement lends to its roads: from its garrison's last evaluation, or from its
     * archetype before the first evaluation (the Phase 8 values), or 0 for anything that is not a settlement.
     */
    public static int level(final KingdomsSavedData data, final UUID settlementId)
    {
        final Optional<GarrisonRecord> garrison = data.military().garrison(settlementId);
        if (garrison.isPresent() && garrison.get().lastEvaluatedAt() >= 0L) return SecurityRules.level(garrison.get().security());
        return data.settlements().get(settlementId).map(value -> ThreatRules.security(value.type())).orElse(0);
    }

    // ------------------------------------------------------------------------------------------------ patrols

    public static boolean sortieReady(final GarrisonRecord garrison, final long gameTime, final SecuritySettings settings)
    {
        return garrison.strength() >= settings.sortieMinimumStrength() && !garrison.alertAt(gameTime)
            && (garrison.lastSortieAt() == Long.MIN_VALUE || gameTime - garrison.lastSortieAt() >= settings.sortieCooldownTicks());
    }

    public static Optional<BanditEncounter> sortieTarget(final KingdomsSavedData data, final SettlementRecord settlement,
        final SecuritySettings settings)
    {
        return sortieTarget(data, settlement, settings, encounter -> false);
    }

    /**
     * The nearest bandit camp within patrol range whose fight nobody else has a claim on: abstract, not {@code observed}
     * (players near it, bandits held or suppressed), never fought by players, not reserved by an accepted contract, and
     * not in a grace period after players stepped away.
     */
    public static Optional<BanditEncounter> sortieTarget(final KingdomsSavedData data, final SettlementRecord settlement,
        final SecuritySettings settings, final Predicate<BanditEncounter> observed)
    {
        final double limit = (double) settings.patrolRadius() * settings.patrolRadius();
        BanditEncounter best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (final BanditCamp camp : data.bandits().activeCamps())
        {
            if (!camp.dimension().equals(settlement.dimension())) continue;
            final double distance = camp.position().distSqr(settlement.anchor());
            if (distance > limit || distance >= bestDistance) continue;
            final BanditEncounter encounter = data.bandits().encounter(camp.encounterId()).orElse(null);
            if (encounter == null || encounter.status() != BanditEncounter.Status.ACTIVE
                || encounter.representation() != BanditEncounter.Representation.ABSTRACT || encounter.remainingStrength() <= 0
                || claimedByPlayers(data, encounter) || observed.test(encounter)) continue;
            best = encounter;
            bestDistance = distance;
        }
        return Optional.ofNullable(best);
    }

    /** Players fought this fight, hold a contract for it, or were just fighting it: a patrol leaves it to them. */
    static boolean claimedByPlayers(final KingdomsSavedData data, final BanditEncounter encounter)
    {
        return !encounter.defenders().isEmpty() || encounter.expiryDeferred()
            || data.contracts().targeting(encounter.id()).stream().anyMatch(contract -> contract.status() == ContractStatus.ACCEPTED);
    }

    public record SortieResult(SecurityRules.Sortie outcome, EncounterService.Resolution resolution, int lost) {}

    /**
     * One patrol against a camp, as a single transaction: the attempt is counted first (so a restart never re-rolls it),
     * the seeded outcome is taken, a victory resolves the camp's encounter through {@link EncounterService} (its losses
     * travel with the encounter and are applied once in its consequences), and a defeat costs soldiers once.
     */
    public static SortieResult sortie(final KingdomsSavedData data, final SettlementRecord settlement, final GarrisonRecord garrison,
        final BanditEncounter encounter, final long gameTime, final BanditSettings bandits, final ContractSettings contracts)
    {
        garrison.sortie(gameTime);
        data.markChanged();
        final long seed = SecurityRules.sortieSeed(encounter.id(), settlement.id(), garrison.sorties());
        final SecurityRules.Sortie outcome = SecurityRules.sortie(seed, garrison.strength(), encounter.remainingStrength(), garrison.security());
        if (outcome.success())
        {
            final int recorded = EncounterService.recordGarrisonDefence(data, encounter, settlement.id(), outcome.losses());
            final EncounterService.Resolution resolution = EncounterService.resolve(data, encounter,
                new EncounterRules.Decision(EncounterRules.Outcome.BANDITS_DEFEATED, 0.0D, 0L), BanditEncounter.Cause.GARRISON_PATROL,
                List.of(), gameTime, bandits, contracts);
            return new SortieResult(outcome, resolution, resolution.applied() ? recorded : 0);
        }
        final int lost = garrison.lose(sortieEventId(encounter.id(), settlement.id(), garrison.sorties()), outcome.losses());
        garrison.alert(gameTime + ALERT_TICKS);
        data.markChanged();
        return new SortieResult(outcome, null, lost);
    }

    static UUID sortieEventId(final UUID encounterId, final UUID settlementId, final int attempt)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-sortie:" + encounterId + ':' + settlementId + ':' + attempt).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------ encounter consequences

    /**
     * Called by {@link EncounterService} when a bandit encounter ends (inside its resolution or cancellation transaction):
     * soldiers that died fighting it (recorded on the encounter) are lost once, and a garrison that won gains defence
     * momentum. The encounter ID is the event ID, so this can never apply twice to one garrison.
     */
    public static void onEncounterEnded(final KingdomsSavedData data, final BanditEncounter encounter, final long gameTime)
    {
        if (encounter.open()) return;
        final boolean won = encounter.status() == BanditEncounter.Status.RESOLVED_GARRISON;
        for (final Map.Entry<UUID, Integer> entry : encounter.garrisonLosses().entrySet())
        {
            final GarrisonRecord garrison = data.military().garrison(entry.getKey()).orElse(null);
            if (garrison == null) continue;
            if (garrison.lose(encounter.id(), entry.getValue()) > 0) garrison.alert(gameTime + ALERT_TICKS);
            data.markChanged();
        }
        if (won)
            for (final UUID settlementId : encounter.garrisonDefenders())
                data.military().garrison(settlementId).ifPresent(garrison -> {
                    if (garrison.markApplied(defenceEventId(encounter.id()))) garrison.defended(gameTime);
                    data.markChanged();
                });
    }

    static UUID defenceEventId(final UUID encounterId)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-defence:" + encounterId).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------ armies and battles (Phase 10)

    /** Soldiers leave a garrison for an army in the same transaction that creates the army; returns how many left. */
    public static int detach(final KingdomsSavedData data, final UUID settlementId, final int wanted)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null) return 0;
        final int left = garrison.detach(wanted);
        data.markChanged();
        return left;
    }

    /** An army's survivors come home, exactly once per army; returns the soldiers that came back. */
    public static int reattach(final KingdomsSavedData data, final UUID settlementId, final int left, final int survivors, final UUID armyId)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null) return 0; // the settlement is gone: its soldiers have nowhere to return
        final int back = garrison.reattach(armyEventId(armyId), left, survivors);
        data.markChanged();
        return back;
    }

    /** Keeps "soldiers away" consistent with the armies actually in the field (self-healing after an unreadable record). */
    public static int reconcileDetached(final KingdomsSavedData data, final UUID settlementId, final int inTheField)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null || garrison.detached() <= inTheField) return 0;
        final int back = garrison.reconcileDetached(inTheField);
        data.markChanged();
        return back;
    }

    /** A settlement's defenders lost {@code losses} soldiers in a battle; applied once per battle. */
    public static int battleLosses(final KingdomsSavedData data, final UUID settlementId, final UUID battleId, final int losses, final long gameTime)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null) return 0;
        final int lost = garrison.lose(battleId, losses);
        if (lost > 0) garrison.alert(gameTime + ALERT_TICKS);
        data.markChanged();
        return lost;
    }

    /**
     * A guard fell to an enemy soldier outside a siege (a skirmish with a marching army, Phase 10): the garrison loses
     * that soldier now, once per guard ({@code guardId} is the event ID); returns the soldiers lost.
     */
    public static int skirmishLoss(final KingdomsSavedData data, final UUID settlementId, final UUID guardId, final long gameTime)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null) return 0;
        final int lost = garrison.lose(UUID.nameUUIDFromBytes(("kingdoms-skirmish:" + guardId).getBytes(StandardCharsets.UTF_8)), 1);
        if (lost > 0) garrison.alert(gameTime + ALERT_TICKS);
        data.markChanged();
        return lost;
    }

    /** A settlement was sacked: its security is reduced until {@code until} (a transparent term of the breakdown). */
    public static void sacked(final KingdomsSavedData data, final UUID settlementId, final long until)
    {
        data.military().garrison(settlementId).ifPresent(garrison -> {
            garrison.vulnerable(until);
            data.markChanged();
        });
    }

    /** A settlement held off a siege: defence momentum, once per battle. */
    public static void defendedBattle(final KingdomsSavedData data, final UUID settlementId, final UUID battleId, final long gameTime)
    {
        data.military().garrison(settlementId).ifPresent(garrison -> {
            if (garrison.markApplied(defenceEventId(battleId))) garrison.defended(gameTime);
            data.markChanged();
        });
    }

    static UUID armyEventId(final UUID armyId)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-army-return:" + armyId).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------ world events (Phase 11)

    /** Volunteers from a world event join once (the event ID), never above the capacity; returns who joined. */
    public static int eventRecruits(final KingdomsSavedData data, final UUID settlementId, final UUID eventId, final int soldiers)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null) return 0;
        final int joined = garrison.join(eventId, soldiers);
        data.markChanged();
        return joined;
    }

    /** Soldiers desert because of a world event, once (the event ID); returns who left. */
    public static int eventLosses(final KingdomsSavedData data, final UUID settlementId, final UUID eventId, final int soldiers)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null) return 0;
        final int lost = garrison.lose(eventId, soldiers);
        data.markChanged();
        return lost;
    }

    /** After an evaluation failed as a whole: the next attempt waits for the normal interval instead of every cycle. */
    public static void evaluationFailed(final KingdomsSavedData data, final long gameTime)
    {
        data.military().setLastEvaluatedAt(gameTime);
        data.markChanged();
    }

    // ------------------------------------------------------------------------------------------------ operator

    /** Operator override of a garrison's strength (diagnostics and tests); returns the previous strength. */
    public static Optional<Integer> setStrength(final KingdomsSavedData data, final UUID settlementId, final int strength)
    {
        final GarrisonRecord garrison = data.military().garrison(settlementId).orElse(null);
        if (garrison == null) return Optional.empty();
        final int before = garrison.strength();
        garrison.adminStrength(strength);
        data.markChanged();
        return Optional.of(before);
    }
}
