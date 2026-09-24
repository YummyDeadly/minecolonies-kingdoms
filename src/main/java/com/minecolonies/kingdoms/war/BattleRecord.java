package com.minecolonies.kingdoms.war;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One siege of a settlement by an army (Phase 10): the authoritative battle record. Physical soldiers and guards only
 * represent it; their deaths during the siege are recorded here (bounded by the two sides' strengths) and folded into
 * the single resolution, which applies losses and consequences exactly once.
 *
 * <pre>
 * PENDING --resolveAt reached, or one side destroyed physically--> RESOLVED
 * PENDING --war over / army gone / operator--> CANCELLED
 * </pre>
 *
 * The outcome is a pure function of the persisted seed, the strengths frozen at the start, and the recorded physical
 * losses, so a restart never re-rolls it. State changes are package-private and made only by {@link CampaignService}.
 */
public final class BattleRecord
{
    public enum Status
    {
        PENDING, RESOLVED, CANCELLED;

        public boolean terminal() { return this != PENDING; }
    }

    public enum Outcome { ATTACKER_VICTORY, DEFENDER_VICTORY }

    public static final int MAX_DEFENDERS = 8;
    static final int MAX_DEFENDERS_WITH_HOLDER = MAX_DEFENDERS + 2;

    private final UUID id;
    private final UUID warId;
    private final UUID armyId;
    private final UUID attacker;
    private final UUID defender;
    private final UUID settlementId;
    private final ResourceLocation dimension;
    private final BlockPos position;
    private final long seed;
    private final int attackerStrength;
    private final int defenderStrength;
    private final int fortification;
    private final long startedAt;
    private final long resolveAt;
    private Status status = Status.PENDING;
    private int attackerPhysicalLosses;
    private int defenderPhysicalLosses;
    private final List<UUID> defenders = new ArrayList<>();
    private final List<UUID> attackersOfSoldiers = new ArrayList<>();
    private Outcome outcome;
    private int attackerLosses;
    private int defenderLosses;
    private int tributeTaken;
    private long resolvedAt = -1L;
    private boolean consequencesApplied;

    BattleRecord(final UUID id, final UUID warId, final UUID armyId, final UUID attacker, final UUID defender, final UUID settlementId,
        final ResourceLocation dimension, final BlockPos position, final int attackerStrength, final int defenderStrength,
        final int fortification, final long startedAt, final long resolveAt)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.warId = Objects.requireNonNull(warId, "warId");
        this.armyId = Objects.requireNonNull(armyId, "armyId");
        this.attacker = Objects.requireNonNull(attacker, "attacker");
        this.defender = Objects.requireNonNull(defender, "defender");
        this.settlementId = Objects.requireNonNull(settlementId, "settlementId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.position = Objects.requireNonNull(position, "position").immutable();
        this.seed = com.minecolonies.kingdoms.bandit.EncounterRules.seed(id);
        this.attackerStrength = Math.max(0, attackerStrength);
        this.defenderStrength = Math.max(0, defenderStrength);
        this.fortification = Math.max(0, Math.min(100, fortification));
        this.startedAt = startedAt;
        this.resolveAt = Math.max(startedAt + 1L, resolveAt);
    }

    public UUID id() { return id; }
    public UUID warId() { return warId; }
    public UUID armyId() { return armyId; }
    public UUID attacker() { return attacker; }
    public UUID defender() { return defender; }
    public UUID settlementId() { return settlementId; }
    public ResourceLocation dimension() { return dimension; }
    public BlockPos position() { return position; }
    public long seed() { return seed; }
    public int attackerStrength() { return attackerStrength; }
    public int defenderStrength() { return defenderStrength; }
    public int fortification() { return fortification; }
    public long startedAt() { return startedAt; }
    public long resolveAt() { return resolveAt; }
    public Status status() { return status; }
    public boolean open() { return status == Status.PENDING; }
    public int attackerPhysicalLosses() { return attackerPhysicalLosses; }
    public int defenderPhysicalLosses() { return defenderPhysicalLosses; }
    /** Players who fought for the besieged settlement. */
    public List<UUID> defenders() { return List.copyOf(defenders); }
    /** Players who killed soldiers of the attacking army during this battle. */
    public List<UUID> attackersOfSoldiers() { return List.copyOf(attackersOfSoldiers); }
    public Outcome outcome() { return outcome; }
    public int attackerLosses() { return attackerLosses; }
    public int defenderLosses() { return defenderLosses; }
    public int tributeTaken() { return tributeTaken; }
    public long resolvedAt() { return resolvedAt; }
    public boolean consequencesApplied() { return consequencesApplied; }

    // ------------------------------------------------------------------------------------------------ transitions

    /** A soldier of the attacking army died in the siege (bounded by the attacking strength). */
    boolean attackerFell()
    {
        if (!open() || attackerPhysicalLosses >= attackerStrength) return false;
        attackerPhysicalLosses++;
        return true;
    }

    /** A defending guard died in the siege (bounded by the defending strength). */
    boolean defenderFell()
    {
        if (!open() || defenderPhysicalLosses >= defenderStrength) return false;
        defenderPhysicalLosses++;
        return true;
    }

    void defender(final UUID player, final boolean holder)
    {
        if (!open() || defenders.contains(player)) return;
        if (defenders.size() < MAX_DEFENDERS || (holder && defenders.size() < MAX_DEFENDERS_WITH_HOLDER)) defenders.add(player);
    }

    void soldierKilledBy(final UUID player)
    {
        if (open() && !attackersOfSoldiers.contains(player) && attackersOfSoldiers.size() < MAX_DEFENDERS_WITH_HOLDER)
            attackersOfSoldiers.add(player);
    }

    void resolve(final Outcome result, final int attackerLost, final int defenderLost, final long gameTime)
    {
        if (!open()) throw new IllegalStateException("Battle " + id + " is already " + status);
        status = Status.RESOLVED;
        outcome = Objects.requireNonNull(result, "outcome");
        attackerLosses = Math.max(0, Math.min(attackerStrength, attackerLost));
        defenderLosses = Math.max(0, Math.min(defenderStrength, defenderLost));
        resolvedAt = gameTime;
    }

    void cancel(final long gameTime)
    {
        if (!open()) throw new IllegalStateException("Battle " + id + " is already " + status);
        status = Status.CANCELLED;
        resolvedAt = gameTime;
    }

    void tribute(final int amount) { tributeTaken = Math.max(0, amount); }

    void markConsequencesApplied() { consequencesApplied = true; }

    // ------------------------------------------------------------------------------------------------ persistence

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("war", warId);
        tag.putUUID("army", armyId);
        tag.putUUID("attacker", attacker);
        tag.putUUID("defender", defender);
        tag.putUUID("settlement", settlementId);
        tag.putString("dimension", dimension.toString());
        tag.putLong("position", position.asLong());
        tag.putInt("attackerStrength", attackerStrength);
        tag.putInt("defenderStrength", defenderStrength);
        tag.putInt("fortification", fortification);
        tag.putLong("startedAt", startedAt);
        tag.putLong("resolveAt", resolveAt);
        tag.putString("status", status.name());
        tag.putInt("attackerPhysicalLosses", attackerPhysicalLosses);
        tag.putInt("defenderPhysicalLosses", defenderPhysicalLosses);
        tag.put("defenders", uuids(defenders));
        tag.put("attackersOfSoldiers", uuids(attackersOfSoldiers));
        if (outcome != null) tag.putString("outcome", outcome.name());
        tag.putInt("attackerLosses", attackerLosses);
        tag.putInt("defenderLosses", defenderLosses);
        tag.putInt("tributeTaken", tributeTaken);
        tag.putLong("resolvedAt", resolvedAt);
        tag.putBoolean("consequencesApplied", consequencesApplied);
        return tag;
    }

    static BattleRecord load(final CompoundTag tag)
    {
        final BattleRecord battle = new BattleRecord(tag.getUUID("id"), tag.getUUID("war"), tag.getUUID("army"), tag.getUUID("attacker"),
            tag.getUUID("defender"), tag.getUUID("settlement"), ResourceLocation.parse(tag.getString("dimension")),
            BlockPos.of(tag.getLong("position")), tag.getInt("attackerStrength"), tag.getInt("defenderStrength"), tag.getInt("fortification"),
            tag.getLong("startedAt"), tag.getLong("resolveAt"));
        battle.status = Status.valueOf(tag.getString("status"));
        battle.attackerPhysicalLosses = Math.max(0, Math.min(battle.attackerStrength, tag.getInt("attackerPhysicalLosses")));
        battle.defenderPhysicalLosses = Math.max(0, Math.min(battle.defenderStrength, tag.getInt("defenderPhysicalLosses")));
        readUuids(tag.getList("defenders", Tag.TAG_COMPOUND), battle.defenders, MAX_DEFENDERS_WITH_HOLDER);
        readUuids(tag.getList("attackersOfSoldiers", Tag.TAG_COMPOUND), battle.attackersOfSoldiers, MAX_DEFENDERS_WITH_HOLDER);
        battle.outcome = tag.contains("outcome") ? Outcome.valueOf(tag.getString("outcome")) : null;
        battle.attackerLosses = Math.max(0, tag.getInt("attackerLosses"));
        battle.defenderLosses = Math.max(0, tag.getInt("defenderLosses"));
        battle.tributeTaken = Math.max(0, tag.getInt("tributeTaken"));
        battle.resolvedAt = tag.getLong("resolvedAt");
        battle.consequencesApplied = tag.getBoolean("consequencesApplied");
        if (battle.status == Status.RESOLVED && battle.outcome == null) throw new IllegalArgumentException("Resolved battle " + battle.id + " without outcome");
        return battle;
    }

    private static ListTag uuids(final List<UUID> values)
    {
        final ListTag list = new ListTag();
        values.forEach(value -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", value);
            list.add(entry);
        });
        return list;
    }

    private static void readUuids(final ListTag list, final List<UUID> into, final int limit)
    {
        list.forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            if (entry.hasUUID("id") && into.size() < limit && !into.contains(entry.getUUID("id"))) into.add(entry.getUUID("id"));
        });
    }
}
