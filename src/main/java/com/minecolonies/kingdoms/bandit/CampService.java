package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The only writer of bandit camps (Phase 8.1).
 *
 * <p>A road whose threat stays at or above the camp threshold for several evaluations gets one camp beside it: not
 * while the road is suppressed or cooling down after a camp, and not beyond the global camp cap or the active-encounter
 * cap. Creation is deterministic (stable IDs from the road and the camp ordinal, fixed candidate sites, strength from the
 * threat), so a restart or a repeated evaluation never creates a second camp. The camp's fight is a {@code CAMP}
 * encounter owned by {@link EncounterService}; when that encounter ends, {@link #onEncounterResolved} ends the camp in the
 * same transaction (defeated: CLEARED; otherwise DISBANDED) and starts the road's camp cooldown.
 */
public final class CampService
{
    private CampService() {}

    /**
     * One evaluation of one eligible road (called by {@link ThreatEvaluator} after the road's threat stepped): camp
     * pressure grows while the threat is high, and a camp appears once the pressure is sustained.
     */
    static Optional<BanditCamp> observe(final KingdomsSavedData data, final RoadRecord road, final RoadThreat threat,
        final List<Vec3> exclusionAnchors, final long gameTime, final BanditSettings settings)
    {
        final CampSettings camps = settings.camps();
        if (!settings.enabled() || !camps.enabled()) return Optional.empty();
        final int pressure = threat.observeCampPressure(threat.threat() >= camps.threshold());
        if (pressure < camps.pressureEvaluations()) return Optional.empty();
        return establish(data, road, exclusionAnchors, gameTime, settings, false);
    }

    /**
     * Establishes a camp beside the road now. {@code force} (operator test) ignores the threat, pressure, suppression,
     * cooldowns, and caps, but never creates a second active camp on a road and never a camp without a valid site.
     */
    public static Optional<BanditCamp> establish(final KingdomsSavedData data, final RoadRecord road, final List<Vec3> exclusionAnchors,
        final long gameTime, final BanditSettings settings, final boolean force)
    {
        final BanditRegistry registry = data.bandits();
        final CampSettings camps = settings.camps();
        if (registry.activeCampOn(road.id()).isPresent()) return Optional.empty();
        final RoadThreat threat = registry.threatFor(road.id());
        if (!force)
        {
            if (!settings.enabled() || !camps.enabled() || registry.activeCamps().size() >= camps.maxCamps()
                || registry.open().size() >= settings.maxActiveEncounters()) return Optional.empty();
            if (threat.suppressedAt(gameTime) || threat.campCoolingDownAt(gameTime)) return Optional.empty();
        }
        // the next unused ordinal (defensive: a record left behind by an old save never blocks the road)
        int ordinal = threat.camps() + 1;
        while (registry.camp(CampPlanner.campId(road.id(), ordinal)).isPresent()
            || registry.encounter(CampPlanner.encounterId(CampPlanner.campId(road.id(), ordinal))).isPresent())
        {
            if (ordinal > threat.camps() + 8) return Optional.empty();
            ordinal++;
        }
        final java.util.UUID id = CampPlanner.campId(road.id(), ordinal);
        final List<BlockPos> others = new ArrayList<>();
        registry.activeCamps().forEach(camp -> others.add(camp.position()));
        final List<BlockPos> sites = CampPlanner.sites(road, exclusionAnchors, settings.settlementExclusionRadius(), others,
            EncounterRules.seed(id));
        if (sites.isEmpty()) return Optional.empty();
        final int strength = Math.max(1, Math.min(ThreatRules.campStrength(threat.threat(), camps), 32));
        final long expiresAt = gameTime + camps.lifetimeTicks();
        final BanditEncounter encounter = EncounterService.openCampEncounter(data, CampPlanner.encounterId(id), road, sites.getFirst(),
            threat.threat(), strength, gameTime, expiresAt);
        threat.campEstablished(ordinal);
        final BanditCamp camp = new BanditCamp(id, road.id(), ordinal, road.dimension(), sites, strength, gameTime, expiresAt,
            encounter.id());
        registry.put(camp);
        data.markChanged();
        com.minecolonies.kingdoms.worldevent.WorldHistory.record(data, com.minecolonies.kingdoms.worldevent.WorldHistory.Entry.of(com.minecolonies.kingdoms.worldevent.WorldHistory.Kind.CAMP_ESTABLISHED, camp.id(), gameTime, road.id(), null,
            "Bandits camped by " + roadLabel(data, road.id()) + " (" + strength + " bandits)"));
        return Optional.of(camp);
    }

    /** History of a camp that just ended (cleared or disbanded); recorded once per camp. */
    private static void recordEnd(final KingdomsSavedData data, final BanditCamp camp, final long gameTime)
    {
        final boolean cleared = camp.status() == BanditCamp.Status.CLEARED;
        com.minecolonies.kingdoms.worldevent.WorldHistory.record(data, com.minecolonies.kingdoms.worldevent.WorldHistory.Entry.of(cleared ? com.minecolonies.kingdoms.worldevent.WorldHistory.Kind.CAMP_CLEARED : com.minecolonies.kingdoms.worldevent.WorldHistory.Kind.CAMP_DISBANDED, camp.id(), gameTime,
            camp.roadId(), null, "Bandit camp by " + roadLabel(data, camp.roadId()) + (cleared ? " was cleared" : " broke up")));
    }

    private static String roadLabel(final KingdomsSavedData data, final java.util.UUID roadId)
    {
        return data.roads().get(roadId).map(road -> "the road "
            + data.settlements().get(road.firstSettlementId()).map(value -> value.name()).orElse("?") + " - "
            + data.settlements().get(road.secondSettlementId()).map(value -> value.name()).orElse("?")).orElse("a road");
    }

    /**
     * Ends the camp of a camp encounter that just ended (called by {@link EncounterService} inside its resolution or
     * cancellation transaction). Idempotent: an already ended camp is left as it is.
     */
    static void onEncounterResolved(final KingdomsSavedData data, final BanditEncounter encounter, final long gameTime,
        final BanditSettings settings)
    {
        if (encounter.kind() != BanditEncounter.Kind.CAMP || encounter.open()) return;
        final BanditCamp camp = data.bandits().campByEncounter(encounter.id()).orElse(null);
        if (camp == null || !camp.active()) return;
        final boolean cleared = encounter.outcome() == EncounterRules.Outcome.BANDITS_DEFEATED;
        camp.end(cleared ? BanditCamp.Status.CLEARED : BanditCamp.Status.DISBANDED, encounter.cause(), gameTime);
        data.bandits().threatFor(camp.roadId()).campEnded(gameTime, settings.camps().respawnCooldownTicks());
        data.markChanged();
        recordEnd(data, camp, gameTime);
    }

    /**
     * Unobserved camps regain lost bandits: one per recruit interval, never above the camp's strength and never while
     * players see the camp. Returns the camps that recruited.
     */
    public static List<BanditCamp> recruit(final KingdomsSavedData data, final long gameTime, final BanditSettings settings)
    {
        final List<BanditCamp> recruited = new ArrayList<>();
        final long interval = settings.camps().recruitIntervalTicks();
        for (final BanditCamp camp : data.bandits().activeCamps())
        {
            final BanditEncounter encounter = data.bandits().encounter(camp.encounterId()).orElse(null);
            if (encounter == null || !encounter.open()) continue;
            if (encounter.representation() != BanditEncounter.Representation.ABSTRACT)
            {
                camp.recruited(gameTime); // seen: the clock starts again once players leave
                data.markChanged();
                continue;
            }
            if (gameTime - camp.lastRecruitAt() < interval) continue;
            if (encounter.remainingStrength() > 0 && encounter.remainingStrength() < encounter.strength())
            {
                EncounterService.reinforce(data, encounter, 1);
                recruited.add(camp);
            }
            camp.recruited(gameTime);
            data.markChanged();
        }
        return recruited;
    }

    /**
     * Keeps camps and their fights consistent (called with every threat evaluation): an active camp whose encounter is
     * missing (an unreadable save entry) or already ended (an interrupted transaction) ends now, and an open camp
     * encounter without an active camp is cancelled. Returns the camps that were ended.
     */
    public static List<BanditCamp> reconcile(final KingdomsSavedData data, final long gameTime, final BanditSettings settings,
        final com.minecolonies.kingdoms.contract.ContractSettings contracts)
    {
        final List<BanditCamp> ended = new ArrayList<>();
        for (final BanditCamp camp : data.bandits().activeCamps())
        {
            final BanditEncounter encounter = data.bandits().encounter(camp.encounterId()).orElse(null);
            if (encounter != null && encounter.open()) continue;
            final boolean cleared = encounter != null && encounter.outcome() == EncounterRules.Outcome.BANDITS_DEFEATED;
            camp.end(cleared ? BanditCamp.Status.CLEARED : BanditCamp.Status.DISBANDED,
                encounter == null || encounter.cause() == null ? BanditEncounter.Cause.ADMIN : encounter.cause(), gameTime);
            data.bandits().threatFor(camp.roadId()).campEnded(gameTime, settings.camps().respawnCooldownTicks());
            data.markChanged();
            recordEnd(data, camp, gameTime);
            ended.add(camp);
        }
        for (final BanditEncounter encounter : List.copyOf(data.bandits().open()))
            if (encounter.kind() == BanditEncounter.Kind.CAMP
                && data.bandits().campByEncounter(encounter.id()).filter(BanditCamp::active).isEmpty())
                EncounterService.cancel(data, encounter, BanditEncounter.Cause.ADMIN, gameTime, settings, contracts);
        return ended;
    }

    /** Operator: end a camp now (its fight is cancelled; the camp breaks up and its road cools down). */
    public static boolean disband(final KingdomsSavedData data, final BanditCamp camp, final long gameTime, final BanditSettings settings,
        final com.minecolonies.kingdoms.contract.ContractSettings contracts)
    {
        if (!camp.active()) return false;
        data.bandits().encounter(camp.encounterId()).filter(BanditEncounter::open)
            .ifPresent(encounter -> EncounterService.cancel(data, encounter, BanditEncounter.Cause.ADMIN, gameTime, settings, contracts));
        if (camp.active()) reconcile(data, gameTime, settings, contracts);
        return !camp.active();
    }

    /** Threat an active camp adds to its road. */
    public static double contribution(final KingdomsSavedData data, final java.util.UUID roadId, final BanditSettings settings)
    {
        return data.bandits().activeCampOn(roadId).isPresent() && settings.camps().enabled() ? settings.camps().threatContribution() : 0.0D;
    }

    // ------------------------------------------------------------------------------------------------ structure bookkeeping

    /**
     * The camp settles on candidate site {@code index}: only before anything was placed, while its fight is abstract, and
     * within {@link CampPlanner#RELOCATION_RADIUS} of its first site (where its contract and reports point).
     */
    static boolean useSite(final KingdomsSavedData data, final BanditCamp camp, final int index)
    {
        if (index == camp.siteIndex()) return true;
        if (!CampPlanner.withinRelocation(camp.sites().getFirst(), camp.sites().get(index))) return false;
        final BanditEncounter encounter = data.bandits().encounter(camp.encounterId()).orElse(null);
        if (encounter == null || !encounter.open() || encounter.representation() != BanditEncounter.Representation.ABSTRACT
            || camp.structure() == BanditCamp.Structure.BUILT) return false;
        camp.useSite(index);
        EncounterService.relocate(data, encounter, camp.position());
        data.markChanged();
        return true;
    }

    static void built(final KingdomsSavedData data, final BanditCamp camp, final List<BanditCamp.PlacedBlock> blocks, final long gameTime)
    {
        camp.built(blocks, gameTime);
        data.markChanged();
    }

    static void unbuildable(final KingdomsSavedData data, final BanditCamp camp, final String reason, final long gameTime)
    {
        camp.unbuildable(reason, gameTime);
        data.markChanged();
    }

    static void removed(final KingdomsSavedData data, final BanditCamp camp, final long gameTime)
    {
        camp.removed(gameTime);
        data.markChanged();
    }
}
