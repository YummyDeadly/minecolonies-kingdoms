package com.minecolonies.kingdoms.worldevent;

import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded strategic world history (Phase 11): what happened, when, and to whom, for diagnostics and later storytelling.
 * Entries are written at the exactly-once transaction points of the owning domains (world events, camps, wars, battles,
 * colony removal) through {@link #record}; each entry's ID is derived from its source, so recording the same fact again
 * changes nothing. Only the newest {@link #limit()} entries are kept.
 */
public final class WorldHistory
{
    public static final int DEFAULT_LIMIT = 256;
    public static final int MAX_DETAIL = 200;

    public enum Kind
    {
        EVENT_STARTED, EVENT_ENDED, CAMP_ESTABLISHED, CAMP_CLEARED, CAMP_DISBANDED, WAR_DECLARED, WAR_ENDED, BATTLE, COLONY_ABANDONED
    }

    /** One history entry; {@code first}/{@code second} are the main subjects (settlement, faction, road, or camp IDs). */
    public record Entry(UUID id, long gameTime, Kind kind, UUID source, UUID first, UUID second, String detail)
    {
        public Entry
        {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            detail = detail == null ? "" : detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
        }

        /** The entry for one fact about one source (an event, camp, war, battle, or colony): its ID never changes. */
        public static Entry of(final Kind kind, final UUID source, final long gameTime, final UUID first, final UUID second, final String detail)
        {
            return new Entry(entryId(kind, source), gameTime, kind, source, first, second, detail);
        }

        CompoundTag save()
        {
            final CompoundTag tag = new CompoundTag();
            tag.putUUID("id", id);
            tag.putLong("time", gameTime);
            tag.putString("kind", kind.name());
            tag.putUUID("source", source);
            if (first != null) tag.putUUID("first", first);
            if (second != null) tag.putUUID("second", second);
            tag.putString("detail", detail);
            return tag;
        }

        static Entry load(final CompoundTag tag)
        {
            return new Entry(tag.getUUID("id"), tag.getLong("time"), Kind.valueOf(tag.getString("kind")), tag.getUUID("source"),
                tag.hasUUID("first") ? tag.getUUID("first") : null, tag.hasUUID("second") ? tag.getUUID("second") : null,
                tag.getString("detail"));
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Set<UUID> ids = new HashSet<>();
    private int limit = DEFAULT_LIMIT;
    private long recorded;

    public static UUID entryId(final Kind kind, final UUID source)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-history:" + kind + ':' + source).getBytes(StandardCharsets.UTF_8));
    }

    /** Records one fact once; returns false if it was already recorded (or the history is disabled with a limit of 0). */
    public static boolean record(final KingdomsSavedData data, final Entry entry)
    {
        final boolean added = data.history().add(entry);
        if (added) data.markChanged();
        return added;
    }

    boolean add(final Entry entry)
    {
        if (limit <= 0 || ids.contains(entry.id())) return false;
        entries.addLast(entry);
        ids.add(entry.id());
        recorded++;
        trim();
        return true;
    }

    /** The configured size; lowering it drops the oldest entries at once. */
    public void setLimit(final int value)
    {
        limit = Math.max(0, value);
        trim();
    }

    private void trim()
    {
        while (entries.size() > limit)
        {
            final Entry oldest = entries.removeFirst();
            ids.remove(oldest.id());
        }
    }

    public int limit() { return limit; }
    public int size() { return entries.size(); }
    /** Entries recorded since the server started (not persisted; for diagnostics). */
    public long recordedThisSession() { return recorded; }
    public boolean contains(final UUID id) { return ids.contains(id); }

    /** Oldest first. */
    public List<Entry> entries() { return Collections.unmodifiableList(new ArrayList<>(entries)); }

    /** The newest {@code count} entries, newest first. */
    public List<Entry> latest(final int count)
    {
        final List<Entry> result = new ArrayList<>();
        final var iterator = entries.descendingIterator();
        while (iterator.hasNext() && result.size() < Math.max(0, count)) result.add(iterator.next());
        return result;
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        entries.forEach(entry -> list.add(entry.save()));
        tag.put("entries", list);
        tag.putInt("limit", limit);
        return tag;
    }

    public static WorldHistory load(final CompoundTag tag)
    {
        final WorldHistory history = new WorldHistory();
        history.limit = tag.contains("limit") ? Math.max(0, tag.getInt("limit")) : DEFAULT_LIMIT;
        tag.getList("entries", Tag.TAG_COMPOUND).forEach(value -> {
            try
            {
                final Entry entry = Entry.load((CompoundTag) value);
                if (history.ids.add(entry.id())) history.entries.addLast(entry);
            }
            catch (RuntimeException ignored)
            {
                // an unreadable history entry is only lost history, never state
            }
        });
        history.trim();
        return history;
    }
}
