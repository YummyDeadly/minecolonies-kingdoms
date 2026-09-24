package com.minecolonies.kingdoms.war;

import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyEvaluator;
import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.diplomacy.DiplomaticStance;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.military.GarrisonRecord;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of wars (Phase 10). Relation and war are separate: a war exists only as an explicit record created by
 * {@link #declare} with an audited cause. The autonomous path ({@link #evaluate}) is conservative (see {@link WarRules}):
 * a hostile relation must persist for several evaluations, the attacker must be able to field an army along the road
 * graph, and a global cap and truces apply. Ending a war applies its peace once: the relation returns to the lowest
 * neutral value (trade can resume), a truce keeps the pair from fighting again for a while, and the loser pays an
 * indemnity from its free treasury. No settlement changes owner (conquest is a documented later extension).
 */
public final class WarService
{
    public static final int WAR_RELATION = -60;

    private WarService() {}

    public record Evaluation(List<WarRecord> declared, List<WarRecord> activated, List<WarRecord> ended) {}

    public enum Refusal
    {
        UNKNOWN_FACTION("Unknown faction"),
        SAME_FACTION("A faction cannot fight itself"),
        NOT_A_PARTICIPANT("Only NPC kingdoms, city states, and tribes go to war (players' factions never do)"),
        ALREADY_AT_WAR("One of the factions already fights a war"),
        TRUCE("The pair is in a truce after its last war"),
        DISABLED("Wars are disabled");

        private final String message;
        Refusal(final String message) { this.message = message; }
        public String message() { return message; }
    }

    public record Declaration(Optional<WarRecord> war, Refusal refusal) {}

    /** The open war involving a faction, if any (at most one by construction). */
    public static Optional<WarRecord> openWarOf(final KingdomsSavedData data, final UUID faction)
    {
        return data.war().wars().stream().filter(war -> war.open() && war.involves(faction)).findFirst();
    }

    public static Optional<WarRecord> openWarBetween(final KingdomsSavedData data, final UUID a, final UUID b)
    {
        return data.war().wars().stream().filter(war -> war.open() && war.involves(a) && war.involves(b)).findFirst();
    }

    /** Whether two factions are at war right now (ACTIVE: armies march and fight). */
    public static boolean atWar(final KingdomsSavedData data, final UUID a, final UUID b)
    {
        return a != null && b != null && !a.equals(b) && openWarBetween(data, a, b).map(war -> war.status().fighting()).orElse(false);
    }

    /**
     * Declares a war (operator, relation collapse, or world event). Refused if a faction is missing or not an NPC
     * participant, either faction already fights a war, or the pair is in truce (unless {@code force}). The relation drops
     * to hostile, audited with the war's ordinal as evidence.
     */
    public static Declaration declare(final KingdomsSavedData data, final UUID attacker, final UUID defender, final WarRecord.Cause cause,
        final long evidence, final long gameTime, final WarSettings settings, final boolean force)
    {
        final Faction a = data.faction(attacker).orElse(null);
        final Faction d = data.faction(defender).orElse(null);
        if (a == null || d == null) return refused(Refusal.UNKNOWN_FACTION);
        if (attacker.equals(defender)) return refused(Refusal.SAME_FACTION);
        if (!DiplomacyEvaluator.participates(a) || !DiplomacyEvaluator.participates(d)) return refused(Refusal.NOT_A_PARTICIPANT);
        if (openWarOf(data, attacker).isPresent() || openWarOf(data, defender).isPresent()) return refused(Refusal.ALREADY_AT_WAR);
        final WarState state = data.war().state();
        if (!force && state.truceUntil(attacker, defender) > gameTime) return refused(Refusal.TRUCE);
        final int ordinal = state.nextWarOrdinal(attacker, defender);
        final WarRecord war = new WarRecord(WarRules.warId(attacker, defender, ordinal), ordinal, attacker, defender, cause, evidence, gameTime,
            gameTime + settings.mobilizationTicks());
        data.war().putWar(war);
        state.resetHostility(attacker, defender);
        DiplomacyService.set(data, a, d, Math.min(DiplomacyService.relation(a, d), WAR_RELATION), DiplomacyState.Cause.WAR_DECLARED, ordinal,
            gameTime);
        data.markChanged();
        com.minecolonies.kingdoms.worldevent.WorldHistory.record(data, com.minecolonies.kingdoms.worldevent.WorldHistory.Entry.of(com.minecolonies.kingdoms.worldevent.WorldHistory.Kind.WAR_DECLARED, war.id(), gameTime, attacker, defender,
            a.name() + " declared war on " + d.name() + " (" + cause.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")"));
        return new Declaration(Optional.of(war), null);
    }

    private static Declaration refused(final Refusal refusal)
    {
        return new Declaration(Optional.empty(), refusal);
    }

    /**
     * One war evaluation: hostility streaks of neighbour pairs, conservative autonomous declarations, mobilization, and
     * ends (score, time limit, ceasefire over). Deterministic: pairs in a fixed order, no randomness.
     */
    public static Evaluation evaluate(final KingdomsSavedData data, final long gameTime, final WarSettings settings)
    {
        final List<WarRecord> declared = new ArrayList<>();
        final List<WarRecord> activated = new ArrayList<>();
        final List<WarRecord> ended = new ArrayList<>();
        final WarState state = data.war().state();
        final java.util.Set<DiplomacyEvaluator.Pair> pairs = DiplomacyEvaluator.neighbourPairs(data);
        state.retainHostility(pairs.stream().map(pair -> WarState.key(pair.first(), pair.second())).collect(java.util.stream.Collectors.toSet()));
        for (final DiplomacyEvaluator.Pair pair : pairs)
        {
            final Faction a = data.faction(pair.first()).orElse(null);
            final Faction b = data.faction(pair.second()).orElse(null);
            if (a == null || b == null) continue;
            final boolean fighting = openWarBetween(data, a.id(), b.id()).isPresent();
            final int hostility = state.observeHostility(pair.first(), pair.second(), !fighting && WarRules.hostile(DiplomacyService.relation(a, b)));
            if (!settings.enabled() || !settings.autonomous() || hostility < settings.hostileEvaluations()) continue;
            if (data.war().wars().stream().filter(WarRecord::open).count() >= settings.maxWars()) continue;
            final int strengthA = homeStrength(data, a);
            final int strengthB = homeStrength(data, b);
            final Faction attacker = strengthA >= strengthB ? a : b;
            final Faction defender = attacker == a ? b : a;
            if (CampaignService.plan(data, attacker, defender, settings).isEmpty()) continue; // it could not field an army
            declare(data, attacker.id(), defender.id(), WarRecord.Cause.RELATION_COLLAPSE, hostility, gameTime, settings, false).war()
                .ifPresent(declared::add);
        }
        for (final WarRecord war : List.copyOf(data.war().wars()))
        {
            if (war.open() && (data.faction(war.attacker()).isEmpty() || data.faction(war.defender()).isEmpty()))
            {
                end(data, war, WarRecord.Result.WHITE_PEACE, gameTime, settings).ifPresent(ended::add); // a side no longer exists
                continue;
            }
            if (war.status() == WarRecord.Status.DECLARED && gameTime >= war.activeAt())
            {
                war.activate();
                activated.add(war);
                data.markChanged();
            }
            if (war.status() == WarRecord.Status.ACTIVE)
            {
                if (war.score() >= settings.victoryScore()) end(data, war, WarRecord.Result.ATTACKER_VICTORY, gameTime, settings).ifPresent(ended::add);
                else if (war.score() <= -settings.victoryScore()) end(data, war, WarRecord.Result.DEFENDER_VICTORY, gameTime, settings).ifPresent(ended::add);
                else if (gameTime - war.activeAt() >= settings.maxDurationTicks()) end(data, war, byScore(war), gameTime, settings).ifPresent(ended::add);
            }
            else if (war.status() == WarRecord.Status.CEASEFIRE && gameTime - war.ceasefireAt() >= settings.ceasefireTicks())
                end(data, war, WarRecord.Result.WHITE_PEACE, gameTime, settings).ifPresent(ended::add);
        }
        state.prune(gameTime);
        data.war().prune();
        data.war().setLastEvaluatedAt(gameTime);
        data.markChanged();
        return new Evaluation(List.copyOf(declared), List.copyOf(activated), List.copyOf(ended));
    }

    static WarRecord.Result byScore(final WarRecord war)
    {
        return war.score() > 0 ? WarRecord.Result.ATTACKER_VICTORY : war.score() < 0 ? WarRecord.Result.DEFENDER_VICTORY : WarRecord.Result.WHITE_PEACE;
    }

    /** Soldiers at home across a faction's garrisons. */
    public static int homeStrength(final KingdomsSavedData data, final Faction faction)
    {
        int total = 0;
        for (final UUID settlement : faction.settlementIds())
            total += data.military().garrison(settlement).map(GarrisonRecord::strength).orElse(0);
        return total;
    }

    /** Operator: skip the mobilization of a declared war. */
    public static boolean mobilizeNow(final KingdomsSavedData data, final WarRecord war)
    {
        if (war.status() != WarRecord.Status.DECLARED) return false;
        war.activate();
        data.markChanged();
        return true;
    }

    /** Operator: armies go home and the war ends in white peace after the ceasefire period. */
    public static Optional<WarRecord> ceasefire(final KingdomsSavedData data, final WarRecord war, final long gameTime)
    {
        if (war.status() != WarRecord.Status.ACTIVE && war.status() != WarRecord.Status.DECLARED) return Optional.empty();
        war.ceasefire(gameTime);
        data.markChanged();
        return Optional.of(war);
    }

    /**
     * Ends a war once and applies its peace once: the relation is raised to the lowest neutral value (audited, so trade
     * can resume), the pair gets a truce, and on a victory the loser pays the indemnity from its free treasury.
     */
    public static Optional<WarRecord> end(final KingdomsSavedData data, final WarRecord war, final WarRecord.Result result, final long gameTime,
        final WarSettings settings)
    {
        if (!war.open()) return Optional.empty();
        final UUID payer = payer(war, result);
        data.faction(payer == null ? war.attacker() : payer).ifPresent(faction -> ContractService.accrueTreasury(data, faction, gameTime));
        final int indemnity = payer == null ? 0 : WarRules.warIndemnity(data.faction(payer).map(Faction::treasury).orElse(0L),
            result == WarRecord.Result.ATTACKER_VICTORY ? war.battlesWon() : war.battlesLost());
        war.end(result, indemnity, gameTime);
        applyPeace(data, war, gameTime, settings);
        data.markChanged();
        final String attackerName = data.faction(war.attacker()).map(Faction::name).orElse("?");
        final String defenderName = data.faction(war.defender()).map(Faction::name).orElse("?");
        com.minecolonies.kingdoms.worldevent.WorldHistory.record(data, com.minecolonies.kingdoms.worldevent.WorldHistory.Entry.of(com.minecolonies.kingdoms.worldevent.WorldHistory.Kind.WAR_ENDED, war.id(), gameTime, war.attacker(), war.defender(),
            "War " + attackerName + " vs " + defenderName + " ended: " + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                + (indemnity > 0 ? " (indemnity " + indemnity + ")" : "")));
        return Optional.of(war);
    }

    private static UUID payer(final WarRecord war, final WarRecord.Result result)
    {
        return result == WarRecord.Result.ATTACKER_VICTORY ? war.defender() : result == WarRecord.Result.DEFENDER_VICTORY ? war.attacker() : null;
    }

    /** Idempotent peace consequences (persisted flags): indemnity once, relation and truce once. */
    static void applyPeace(final KingdomsSavedData data, final WarRecord war, final long gameTime, final WarSettings settings)
    {
        if (war.open()) return;
        if (!war.tributePaid())
        {
            final UUID payer = payer(war, war.result());
            if (payer != null && war.tribute() > 0)
                war.tribute(ContractService.transferTreasury(data, payer, war.enemyOf(payer), war.tribute(), gameTime));
            war.markTributePaid();
        }
        if (!war.peaceApplied())
        {
            final Faction a = data.faction(war.attacker()).orElse(null);
            final Faction d = data.faction(war.defender()).orElse(null);
            if (a != null && d != null)
                DiplomacyService.set(data, a, d, Math.max(DiplomacyService.relation(a, d), DiplomaticStance.NEUTRAL.minimum()),
                    DiplomacyState.Cause.PEACE, war.ordinal(), gameTime);
            data.war().state().truce(war.attacker(), war.defender(), gameTime + settings.truceTicks());
            war.markPeaceApplied();
        }
        data.markChanged();
    }
}
