package com.minecolonies.kingdoms.diplomacy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Persisted diplomacy bookkeeping (schema 11): when trade was last evaluated and a bounded audit log of every relation
 * change. The relation values themselves live on {@code Faction.relations()} and are written only by
 * {@link DiplomacyService}.
 */
public final class DiplomacyState
{
    public static final int MAX_EVENTS = 256;

    public enum Cause
    {
        /** A pair became neighbours; the relation starts at its conservative baseline. */
        FIRST_CONTACT,
        /** Shipments delivered between the pair during one evaluation window; evidence = number of deliveries. */
        TRADE_DELIVERIES,
        ADMIN_SET,
        /** Value carried over from a save written before relation changes were audited. */
        MIGRATED
    }

    /** One audited relation change between an unordered pair (first &lt; second). */
    public record Event(long gameTime, UUID first, UUID second, int before, int after, Cause cause, long evidence)
    {
        public DiplomaticStance stanceBefore() { return DiplomaticStance.of(before); }
        public DiplomaticStance stanceAfter() { return DiplomaticStance.of(after); }

        CompoundTag save()
        {
            final CompoundTag tag = new CompoundTag();
            tag.putLong("time", gameTime);
            tag.putUUID("first", first);
            tag.putUUID("second", second);
            tag.putInt("before", before);
            tag.putInt("after", after);
            tag.putString("cause", cause.name());
            tag.putLong("evidence", evidence);
            return tag;
        }

        static Event load(final CompoundTag tag)
        {
            return new Event(tag.getLong("time"), tag.getUUID("first"), tag.getUUID("second"), tag.getInt("before"),
                tag.getInt("after"), Cause.valueOf(tag.getString("cause")), tag.getLong("evidence"));
        }
    }

    private long lastEvaluatedAt = -1L;
    private final Deque<Event> events = new ArrayDeque<>();

    public long lastEvaluatedAt() { return lastEvaluatedAt; }
    public void setLastEvaluatedAt(final long gameTime) { lastEvaluatedAt = gameTime; }

    /** Newest first. */
    public List<Event> events() { return List.copyOf(events); }

    void record(final Event event)
    {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) events.removeLast();
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putLong("lastEvaluatedAt", lastEvaluatedAt);
        final ListTag list = new ListTag();
        events.forEach(event -> list.add(event.save()));
        tag.put("relationEvents", list);
        return tag;
    }

    public static DiplomacyState load(final CompoundTag tag)
    {
        final DiplomacyState state = new DiplomacyState();
        state.lastEvaluatedAt = tag.contains("lastEvaluatedAt") ? tag.getLong("lastEvaluatedAt") : -1L;
        final ListTag list = tag.getList("relationEvents", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size() && index < MAX_EVENTS; index++)
        {
            try { state.events.addLast(Event.load(list.getCompound(index))); }
            catch (RuntimeException ignored) { /* an unreadable audit entry is dropped; relation values are unaffected */ }
        }
        return state;
    }
}
