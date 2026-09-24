package com.minecolonies.kingdoms.military;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The strategic garrison of one NPC settlement (Phase 9): how many soldiers it has, how fast it recruits, and the
 * derived security it lends to its roads. It is authoritative; physical guards only represent some of its soldiers
 * near players and never change it. State changes are package-private and made only by {@link MilitaryService}.
 *
 * <p>Strategic casualties arrive only through an authoritative resolution (a bandit encounter, a patrol sortie, later
 * a battle), each identified by an event ID. The primary exactly-once guard is the resolution itself: an encounter or
 * battle ends once (its terminal status is persisted) and a patrol's attempt number is persisted before its roll. The
 * last {@value #MAX_APPLIED_EVENTS} applied event IDs are kept as a second guard against a repeated call; an evicted ID
 * is not protected by it, so no caller may re-apply an old resolution on its own.
 */
public final class GarrisonRecord
{
    public static final int MAX_APPLIED_EVENTS = 32;

    private final UUID settlementId;
    private int strength;
    private double recruitProgress;
    private int capacity;
    private int security;
    private long lastEvaluatedAt = -1L;
    private long alertUntil = Long.MIN_VALUE;
    private int recentDefences;
    private long lastDefenceAt = -1L;
    private int casualties;
    private int recruited;
    private long vulnerableUntil = Long.MIN_VALUE;
    private long lastSortieAt = Long.MIN_VALUE;
    private int sorties;
    private int detached;
    private final Deque<UUID> appliedEvents = new ArrayDeque<>();

    GarrisonRecord(final UUID settlementId, final int strength, final int capacity)
    {
        this.settlementId = Objects.requireNonNull(settlementId, "settlementId");
        this.strength = Math.max(0, strength);
        this.capacity = Math.max(0, capacity);
    }

    public UUID settlementId() { return settlementId; }
    /** Soldiers at home (armies away are counted in {@link #detached()}). */
    public int strength() { return strength; }
    public double recruitProgress() { return recruitProgress; }
    public int capacity() { return capacity; }
    /** Security 0..100, recomputed at every evaluation from strategic state only. */
    public int security() { return security; }
    public long lastEvaluatedAt() { return lastEvaluatedAt; }
    public long alertUntil() { return alertUntil; }
    public boolean alertAt(final long gameTime) { return alertUntil > gameTime; }
    public int recentDefences() { return recentDefences; }
    public long lastDefenceAt() { return lastDefenceAt; }
    public int casualties() { return casualties; }
    public int recruited() { return recruited; }
    public long vulnerableUntil() { return vulnerableUntil; }
    public boolean vulnerableAt(final long gameTime) { return vulnerableUntil > gameTime; }
    public long lastSortieAt() { return lastSortieAt; }
    public int sorties() { return sorties; }
    /** Soldiers away with armies (Phase 10); they return through the army's own transaction. */
    public int detached() { return detached; }
    public boolean applied(final UUID eventId) { return appliedEvents.contains(eventId); }
    public List<UUID> appliedEvents() { return List.copyOf(appliedEvents); }

    // ------------------------------------------------------------------------------------------------ transitions

    void evaluated(final int newCapacity, final int newSecurity, final long gameTime)
    {
        capacity = Math.max(0, newCapacity);
        security = Math.max(0, Math.min(100, newSecurity));
        lastEvaluatedAt = gameTime;
    }

    /** Adds recruitment progress; whole soldiers join up to the capacity. Returns the soldiers that joined. */
    int recruit(final double progress)
    {
        if (strength + detached >= capacity)
        {
            recruitProgress = 0.0D;
            return 0;
        }
        recruitProgress += Math.max(0.0D, progress);
        final int whole = (int) Math.floor(recruitProgress);
        final int joined = Math.max(0, Math.min(whole, capacity - strength - detached));
        recruitProgress -= whole;
        strength += joined;
        recruited += joined;
        return joined;
    }

    /** Applies losses of one authoritative resolution once; returns the soldiers actually lost (0 if already applied). */
    int lose(final UUID eventId, final int losses)
    {
        if (!markApplied(eventId)) return 0;
        final int lost = Math.max(0, Math.min(strength, losses));
        strength -= lost;
        casualties += lost;
        return lost;
    }

    /** Marks an event as applied; false if it already was. */
    boolean markApplied(final UUID eventId)
    {
        Objects.requireNonNull(eventId, "eventId");
        if (appliedEvents.contains(eventId)) return false;
        appliedEvents.addLast(eventId);
        while (appliedEvents.size() > MAX_APPLIED_EVENTS) appliedEvents.removeFirst();
        return true;
    }

    void defended(final long gameTime)
    {
        recentDefences = Math.min(10, recentDefences + 1);
        lastDefenceAt = gameTime;
    }

    /** Defence momentum fades: one point per {@code decayTicks} without a new defence. */
    void decayDefences(final long gameTime, final long decayTicks)
    {
        if (recentDefences <= 0 || lastDefenceAt < 0L || decayTicks <= 0L) return;
        final long steps = (gameTime - lastDefenceAt) / decayTicks;
        if (steps <= 0L) return;
        recentDefences = (int) Math.max(0L, recentDefences - steps);
        lastDefenceAt += steps * decayTicks;
    }

    void alert(final long until) { alertUntil = Math.max(alertUntil, until); }

    void vulnerable(final long until) { vulnerableUntil = Math.max(vulnerableUntil, until); }

    void sortie(final long gameTime)
    {
        sorties++;
        lastSortieAt = gameTime;
    }

    /** Operator override; recorded by the caller. */
    void adminStrength(final int value)
    {
        strength = Math.max(0, value);
    }

    /** Soldiers leave for an army (Phase 10); returns how many actually left. */
    int detach(final int wanted)
    {
        final int leaving = Math.max(0, Math.min(strength, wanted));
        strength -= leaving;
        detached += leaving;
        return leaving;
    }

    /**
     * Soldiers of an army come home (Phase 10): {@code returning} survivors of {@code left} that departed, once per army
     * ({@code eventId}); returns the soldiers that came back (0 if already applied).
     */
    int reattach(final UUID eventId, final int left, final int returning)
    {
        if (!markApplied(eventId)) return 0;
        detached = Math.max(0, detached - Math.max(0, left));
        final int back = Math.max(0, Math.min(left, returning));
        strength += back;
        return back;
    }

    /** Soldiers counted away although no army of this settlement is in the field (an unreadable army record) come home. */
    int reconcileDetached(final int inTheField)
    {
        final int stray = detached - Math.max(0, inTheField);
        if (stray <= 0) return 0;
        detached -= stray;
        strength += stray;
        return stray;
    }

    // ------------------------------------------------------------------------------------------------ persistence

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("settlement", settlementId);
        tag.putInt("strength", strength);
        tag.putDouble("recruitProgress", recruitProgress);
        tag.putInt("capacity", capacity);
        tag.putInt("security", security);
        tag.putLong("lastEvaluatedAt", lastEvaluatedAt);
        tag.putLong("alertUntil", alertUntil);
        tag.putInt("recentDefences", recentDefences);
        tag.putLong("lastDefenceAt", lastDefenceAt);
        tag.putInt("casualties", casualties);
        tag.putInt("recruited", recruited);
        tag.putLong("vulnerableUntil", vulnerableUntil);
        tag.putLong("lastSortieAt", lastSortieAt);
        tag.putInt("sorties", sorties);
        tag.putInt("detached", detached);
        final ListTag events = new ListTag();
        appliedEvents.forEach(id -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            events.add(entry);
        });
        tag.put("appliedEvents", events);
        return tag;
    }

    static GarrisonRecord load(final CompoundTag tag)
    {
        final GarrisonRecord record = new GarrisonRecord(tag.getUUID("settlement"), tag.getInt("strength"), tag.getInt("capacity"));
        final double progress = tag.getDouble("recruitProgress");
        record.recruitProgress = Double.isFinite(progress) ? Math.max(0.0D, Math.min(1.0D, progress)) : 0.0D;
        record.security = Math.max(0, Math.min(100, tag.getInt("security")));
        record.lastEvaluatedAt = tag.contains("lastEvaluatedAt") ? tag.getLong("lastEvaluatedAt") : -1L;
        record.alertUntil = tag.contains("alertUntil") ? tag.getLong("alertUntil") : Long.MIN_VALUE;
        record.recentDefences = Math.max(0, Math.min(10, tag.getInt("recentDefences")));
        record.lastDefenceAt = tag.contains("lastDefenceAt") ? tag.getLong("lastDefenceAt") : -1L;
        record.casualties = Math.max(0, tag.getInt("casualties"));
        record.recruited = Math.max(0, tag.getInt("recruited"));
        record.vulnerableUntil = tag.contains("vulnerableUntil") ? tag.getLong("vulnerableUntil") : Long.MIN_VALUE;
        record.lastSortieAt = tag.contains("lastSortieAt") ? tag.getLong("lastSortieAt") : Long.MIN_VALUE;
        record.sorties = Math.max(0, tag.getInt("sorties"));
        record.detached = Math.max(0, tag.getInt("detached"));
        final ListTag events = tag.getList("appliedEvents", Tag.TAG_COMPOUND);
        for (int index = Math.max(0, events.size() - MAX_APPLIED_EVENTS); index < events.size(); index++)
        {
            final CompoundTag entry = events.getCompound(index);
            if (entry.hasUUID("id")) record.appliedEvents.addLast(entry.getUUID("id"));
        }
        return record;
    }
}
