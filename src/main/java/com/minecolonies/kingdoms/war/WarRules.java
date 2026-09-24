package com.minecolonies.kingdoms.war;

import com.minecolonies.kingdoms.bandit.EncounterRules;
import com.minecolonies.kingdoms.diplomacy.DiplomaticStance;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pure, deterministic war rules (Phase 10).
 *
 * <p>Declaration is conservative: a war needs a pair of neighbouring NPC factions whose relation stayed HOSTILE for
 * several consecutive diplomacy evaluations, no open war for either faction, no truce between them, a free global war
 * slot, and an attacker whose soldiers at home are enough to raise an army and at least as many as the defender's.
 * Relation alone never starts a war: the hostility must persist, and every war is an explicit audited record.
 *
 * <p>A siege is decided by one seeded roll against the attacker's share of the fighting power; the defender fights
 * behind walls ({@code fortification}, the settlement's security). Physical losses recorded during the siege are
 * folded in with {@code max(recorded, rolled)} per side, so a fight seen by players and the same fight unseen can never
 * both apply losses.
 */
public final class WarRules
{
    public static final long SALT_BATTLE = 51L;
    public static final long SALT_ATTACKER_LOSSES = 52L;
    public static final long SALT_DEFENDER_LOSSES = 53L;

    private WarRules() {}

    public static UUID warId(final UUID attacker, final UUID defender, final int ordinal)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-war:" + attacker + ':' + defender + ':' + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID armyId(final UUID warId, final int ordinal)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-army:" + warId + ':' + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID battleId(final UUID armyId)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-battle:" + armyId).getBytes(StandardCharsets.UTF_8));
    }

    /** Whether a relation counts as a hostile evaluation (towards a war). */
    public static boolean hostile(final int relation)
    {
        return DiplomaticStance.of(relation) == DiplomaticStance.HOSTILE;
    }

    /** Soldiers an army takes from a garrison: 60% of those at home, at least {@code minimum} or none at all. */
    public static int armySize(final int atHome, final int minimum, final int maximum)
    {
        final int size = Math.min(maximum, (int) Math.floor(atHome * 0.6D));
        return size >= minimum ? size : 0;
    }

    public record Battle(BattleRecord.Outcome outcome, int attackerLosses, int defenderLosses) {}

    /**
     * The abstract decision of a siege. Attacker win chance = attack / (attack + defence) (clamped 10..90%), where
     * defence = defenders x (1 + fortification/100) + 2 (militia). Losses: the loser loses 40-70% of its soldiers, the
     * winner 10-30%. Recorded physical losses raise either side's losses (never lower them).
     */
    public static Battle decide(final long seed, final int attackerStrength, final int defenderStrength, final int fortification,
        final int attackerPhysicalLosses, final int defenderPhysicalLosses)
    {
        final int attackers = Math.max(0, attackerStrength - attackerPhysicalLosses);
        final int defenders = Math.max(0, defenderStrength - defenderPhysicalLosses);
        final BattleRecord.Outcome outcome;
        if (attackers <= 0) outcome = BattleRecord.Outcome.DEFENDER_VICTORY;
        else if (defenders <= 0 && defenderStrength > 0) outcome = BattleRecord.Outcome.ATTACKER_VICTORY;
        else
        {
            final double defence = defenders * (1.0D + Math.max(0, Math.min(100, fortification)) / 100.0D) + 2.0D;
            final double chance = Math.max(0.1D, Math.min(0.9D, attackers / (attackers + defence)));
            outcome = EncounterRules.unit(seed, SALT_BATTLE) < chance ? BattleRecord.Outcome.ATTACKER_VICTORY : BattleRecord.Outcome.DEFENDER_VICTORY;
        }
        final boolean attackerWon = outcome == BattleRecord.Outcome.ATTACKER_VICTORY;
        final double attackerShare = attackerWon ? 0.1D + 0.2D * EncounterRules.unit(seed, SALT_ATTACKER_LOSSES)
            : 0.4D + 0.3D * EncounterRules.unit(seed, SALT_ATTACKER_LOSSES);
        final double defenderShare = attackerWon ? 0.4D + 0.3D * EncounterRules.unit(seed, SALT_DEFENDER_LOSSES)
            : 0.1D + 0.2D * EncounterRules.unit(seed, SALT_DEFENDER_LOSSES);
        final int attackerLosses = Math.min(attackerStrength, Math.max(attackerPhysicalLosses, (int) Math.round(attackerStrength * attackerShare)));
        final int defenderLosses = Math.min(defenderStrength, Math.max(defenderPhysicalLosses, (int) Math.round(defenderStrength * defenderShare)));
        return new Battle(outcome, attackerLosses, defenderLosses);
    }

    /** War score swing of one battle. */
    public static int scoreSwing() { return 25; }

    /** Treasury tribute a sacked settlement's faction pays once (never more than it has). */
    public static int sackTribute(final long treasury, final int defenderStrength)
    {
        return (int) Math.max(0L, Math.min(treasury, 10L + 2L * Math.max(0, defenderStrength)));
    }

    /** Indemnity the loser of a war pays once at peace (never more than it has). */
    public static int warIndemnity(final long treasury, final int battlesWon)
    {
        return (int) Math.max(0L, Math.min(treasury, 20L + 10L * Math.max(0, battlesWon)));
    }
}
