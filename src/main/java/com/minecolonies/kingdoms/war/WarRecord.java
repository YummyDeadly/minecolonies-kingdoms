package com.minecolonies.kingdoms.war;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One war between two factions (Phase 10): an explicit, persisted record. A war is never inferred from a relation value;
 * it starts only through {@link WarService#declare} with an audited cause.
 *
 * <pre>
 * DECLARED --mobilization over--> ACTIVE --score / exhaustion / time--> ENDED
 *     |                              +--operator or exhaustion--> CEASEFIRE --ceasefire over--> ENDED
 *     +--operator peace-----------------------------------------------------------------------> ENDED
 * </pre>
 *
 * Terminal statuses never change. The peace consequences (relation reset, tribute) are applied once, guarded by
 * persisted flags. State changes are package-private and made only by {@link WarService}.
 */
public final class WarRecord
{
    public enum Status
    {
        DECLARED, ACTIVE, CEASEFIRE, ENDED;

        public boolean terminal() { return this == ENDED; }
        public boolean fighting() { return this == ACTIVE; }
    }

    /** Why the war was declared (audited). */
    public enum Cause { ADMIN, RELATION_COLLAPSE, WORLD_EVENT }

    public enum Result { ATTACKER_VICTORY, DEFENDER_VICTORY, WHITE_PEACE }

    public static final int SCORE_LIMIT = 100;
    public static final int MAX_BATTLES = 64;

    private final UUID id;
    private final int ordinal;
    private final UUID attacker;
    private final UUID defender;
    private final Cause cause;
    private final long evidence;
    private final long declaredAt;
    private final long activeAt;
    private Status status = Status.DECLARED;
    private int score;
    private int battlesWon;
    private int battlesLost;
    private final List<UUID> battles = new ArrayList<>();
    private long ceasefireAt = -1L;
    private long endedAt = -1L;
    private Result result;
    private int tribute;
    private boolean tributePaid;
    private boolean peaceApplied;
    private long lastArmyAt = Long.MIN_VALUE;
    private int armiesRaised;

    WarRecord(final UUID id, final int ordinal, final UUID attacker, final UUID defender, final Cause cause, final long evidence,
        final long declaredAt, final long activeAt)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.ordinal = ordinal;
        this.attacker = Objects.requireNonNull(attacker, "attacker");
        this.defender = Objects.requireNonNull(defender, "defender");
        if (attacker.equals(defender)) throw new IllegalArgumentException("A faction cannot fight itself");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.evidence = evidence;
        this.declaredAt = declaredAt;
        this.activeAt = Math.max(declaredAt, activeAt);
    }

    public UUID id() { return id; }
    public int ordinal() { return ordinal; }
    public UUID attacker() { return attacker; }
    public UUID defender() { return defender; }
    public Cause cause() { return cause; }
    public long evidence() { return evidence; }
    public long declaredAt() { return declaredAt; }
    public long activeAt() { return activeAt; }
    public Status status() { return status; }
    public boolean open() { return !status.terminal(); }
    /** War score: positive favours the attacker (-100..100). */
    public int score() { return score; }
    public int battlesWon() { return battlesWon; }
    public int battlesLost() { return battlesLost; }
    public List<UUID> battles() { return List.copyOf(battles); }
    public long ceasefireAt() { return ceasefireAt; }
    public long endedAt() { return endedAt; }
    public Result result() { return result; }
    public int tribute() { return tribute; }
    public boolean tributePaid() { return tributePaid; }
    public boolean peaceApplied() { return peaceApplied; }
    public long lastArmyAt() { return lastArmyAt; }
    public boolean involves(final UUID faction) { return attacker.equals(faction) || defender.equals(faction); }
    /** The other side, or null for a faction that is not part of this war. */
    public UUID enemyOf(final UUID faction) { return attacker.equals(faction) ? defender : defender.equals(faction) ? attacker : null; }
    public int armiesRaised() { return armiesRaised; }

    // ------------------------------------------------------------------------------------------------ transitions

    void activate()
    {
        if (status != Status.DECLARED) throw new IllegalStateException("War " + id + " is " + status);
        status = Status.ACTIVE;
    }

    /** Records one battle's result once (the battle ID guards against a second application). */
    boolean battle(final UUID battleId, final boolean attackerWon, final int swing)
    {
        if (battles.contains(battleId)) return false;
        battles.add(battleId);
        while (battles.size() > MAX_BATTLES) battles.removeFirst();
        if (attackerWon) battlesWon++;
        else battlesLost++;
        score = Math.max(-SCORE_LIMIT, Math.min(SCORE_LIMIT, score + (attackerWon ? swing : -swing)));
        return true;
    }

    /** Counts a new army (its ordinal, which makes its ID stable and unique within the war). */
    int nextArmyOrdinal() { return ++armiesRaised; }

    void armyRaised(final long gameTime) { lastArmyAt = gameTime; }

    void ceasefire(final long gameTime)
    {
        if (status.terminal() || status == Status.CEASEFIRE) throw new IllegalStateException("War " + id + " is " + status);
        status = Status.CEASEFIRE;
        ceasefireAt = gameTime;
    }

    void end(final Result value, final int agreedTribute, final long gameTime)
    {
        if (status.terminal()) throw new IllegalStateException("War " + id + " is already " + status);
        status = Status.ENDED;
        result = Objects.requireNonNull(value, "result");
        tribute = Math.max(0, agreedTribute);
        endedAt = gameTime;
    }

    /** The indemnity actually paid (the free treasury may have shrunk since the peace was agreed). */
    void tribute(final int paid) { tribute = Math.max(0, paid); }

    void markTributePaid() { tributePaid = true; }

    void markPeaceApplied() { peaceApplied = true; }

    // ------------------------------------------------------------------------------------------------ persistence

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putInt("ordinal", ordinal);
        tag.putUUID("attacker", attacker);
        tag.putUUID("defender", defender);
        tag.putString("cause", cause.name());
        tag.putLong("evidence", evidence);
        tag.putLong("declaredAt", declaredAt);
        tag.putLong("activeAt", activeAt);
        tag.putString("status", status.name());
        tag.putInt("score", score);
        tag.putInt("battlesWon", battlesWon);
        tag.putInt("battlesLost", battlesLost);
        final ListTag list = new ListTag();
        battles.forEach(value -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", value);
            list.add(entry);
        });
        tag.put("battles", list);
        tag.putLong("ceasefireAt", ceasefireAt);
        tag.putLong("endedAt", endedAt);
        if (result != null) tag.putString("result", result.name());
        tag.putInt("tribute", tribute);
        tag.putBoolean("tributePaid", tributePaid);
        tag.putBoolean("peaceApplied", peaceApplied);
        tag.putLong("lastArmyAt", lastArmyAt);
        tag.putInt("armiesRaised", armiesRaised);
        return tag;
    }

    static WarRecord load(final CompoundTag tag)
    {
        final WarRecord war = new WarRecord(tag.getUUID("id"), tag.getInt("ordinal"), tag.getUUID("attacker"), tag.getUUID("defender"),
            Cause.valueOf(tag.getString("cause")), tag.getLong("evidence"), tag.getLong("declaredAt"), tag.getLong("activeAt"));
        war.status = Status.valueOf(tag.getString("status"));
        war.score = Math.max(-SCORE_LIMIT, Math.min(SCORE_LIMIT, tag.getInt("score")));
        war.battlesWon = Math.max(0, tag.getInt("battlesWon"));
        war.battlesLost = Math.max(0, tag.getInt("battlesLost"));
        tag.getList("battles", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            if (entry.hasUUID("id") && war.battles.size() < MAX_BATTLES) war.battles.add(entry.getUUID("id"));
        });
        war.ceasefireAt = tag.getLong("ceasefireAt");
        war.endedAt = tag.getLong("endedAt");
        war.result = tag.contains("result") ? Result.valueOf(tag.getString("result")) : null;
        war.tribute = Math.max(0, tag.getInt("tribute"));
        war.tributePaid = tag.getBoolean("tributePaid");
        war.peaceApplied = tag.getBoolean("peaceApplied");
        war.lastArmyAt = tag.contains("lastArmyAt") ? tag.getLong("lastArmyAt") : Long.MIN_VALUE;
        war.armiesRaised = Math.max(0, tag.getInt("armiesRaised"));
        if (war.status.terminal() && war.result == null) throw new IllegalArgumentException("Ended war " + war.id + " without result");
        return war;
    }
}
