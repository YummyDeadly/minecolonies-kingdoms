package com.minecolonies.kingdoms.diplomacy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Player reputation (schema 11): {@code player UUID -> faction UUID -> record}. This is the only store of player
 * standing; faction-to-faction relations are a separate domain. Entries exist only for pairs that ever changed, and
 * a bounded audit log keeps the most recent changes.
 */
public final class ReputationRegistry
{
    public static final int MAX_EVENTS = 256;

    public enum Cause { CONTRACT_COMPLETED, CONTRACT_FAILED, CONTRACT_CANCELLED, REPRESENTATIVE_KILLED, ENCOUNTER_DEFENDED, SPILLOVER, ADMIN_SET, MIGRATED }

    /** Standing of one player with one faction plus lifetime counters for diagnostics. */
    public static final class Record
    {
        private int value;
        private long lastChangedAt;
        private int completed;
        private int failed;
        private int cancelled;
        private int killed;

        public int value() { return value; }
        public long lastChangedAt() { return lastChangedAt; }
        public int completed() { return completed; }
        public int failed() { return failed; }
        public int cancelled() { return cancelled; }
        public int killed() { return killed; }

        void count(final Cause cause)
        {
            switch (cause)
            {
                case CONTRACT_COMPLETED -> completed++;
                case CONTRACT_FAILED -> failed++;
                case CONTRACT_CANCELLED -> cancelled++;
                case REPRESENTATIVE_KILLED -> killed++;
                default -> { }
            }
        }

        CompoundTag save()
        {
            final CompoundTag tag = new CompoundTag();
            tag.putInt("value", value);
            tag.putLong("changedAt", lastChangedAt);
            tag.putInt("completed", completed);
            tag.putInt("failed", failed);
            tag.putInt("cancelled", cancelled);
            tag.putInt("killed", killed);
            return tag;
        }

        static Record load(final CompoundTag tag)
        {
            final Record record = new Record();
            record.value = DiplomacyRules.clamp(tag.getInt("value"));
            record.lastChangedAt = tag.getLong("changedAt");
            record.completed = Math.max(0, tag.getInt("completed"));
            record.failed = Math.max(0, tag.getInt("failed"));
            record.cancelled = Math.max(0, tag.getInt("cancelled"));
            record.killed = Math.max(0, tag.getInt("killed"));
            return record;
        }
    }

    /** One audited change; {@code reference} is the contract, the source faction of a spillover, or null. */
    public record Event(long gameTime, UUID player, UUID faction, int delta, int after, Cause cause, UUID reference)
    {
        CompoundTag save()
        {
            final CompoundTag tag = new CompoundTag();
            tag.putLong("time", gameTime);
            tag.putUUID("player", player);
            tag.putUUID("faction", faction);
            tag.putInt("delta", delta);
            tag.putInt("after", after);
            tag.putString("cause", cause.name());
            if (reference != null) tag.putUUID("reference", reference);
            return tag;
        }

        static Event load(final CompoundTag tag)
        {
            return new Event(tag.getLong("time"), tag.getUUID("player"), tag.getUUID("faction"), tag.getInt("delta"),
                tag.getInt("after"), Cause.valueOf(tag.getString("cause")), tag.hasUUID("reference") ? tag.getUUID("reference") : null);
        }
    }

    private final Map<UUID, Map<UUID, Record>> records = new LinkedHashMap<>();
    private final Deque<Event> events = new ArrayDeque<>();

    public int value(final UUID player, final UUID faction)
    {
        return record(player, faction).map(Record::value).orElse(0);
    }

    public Optional<Record> record(final UUID player, final UUID faction)
    {
        final Map<UUID, Record> byFaction = records.get(player);
        return byFaction == null ? Optional.empty() : Optional.ofNullable(byFaction.get(faction));
    }

    /** All non-default standings of one player, in insertion order. */
    public Map<UUID, Record> of(final UUID player)
    {
        return Collections.unmodifiableMap(records.getOrDefault(player, Map.of()));
    }

    public int players() { return records.size(); }

    public int entries() { return records.values().stream().mapToInt(Map::size).sum(); }

    /** Newest first. */
    public List<Event> events() { return List.copyOf(events); }

    /** Applies a change, clamps, counts, and logs it; returns the event, or empty when the value did not change. */
    Optional<Event> change(final UUID player, final UUID faction, final int delta, final Cause cause, final UUID reference,
        final long gameTime)
    {
        final Record record = records.computeIfAbsent(player, key -> new LinkedHashMap<>()).computeIfAbsent(faction, key -> new Record());
        record.count(cause);
        final int after = DiplomacyRules.clamp(record.value + delta);
        if (after == record.value) return Optional.empty();
        final Event event = new Event(gameTime, player, faction, after - record.value, after, cause, reference);
        record.value = after;
        record.lastChangedAt = gameTime;
        log(event);
        return Optional.of(event);
    }

    Optional<Event> set(final UUID player, final UUID faction, final int value, final Cause cause, final long gameTime)
    {
        final int current = value(player, faction);
        return change(player, faction, DiplomacyRules.clamp(value) - current, cause, null, gameTime);
    }

    /** Drops the standings of factions that no longer exist; returns how many were dropped. */
    public int retainFactions(final java.util.Set<UUID> factions)
    {
        int removed = 0;
        for (final Map<UUID, Record> byFaction : records.values())
        {
            final int before = byFaction.size();
            byFaction.keySet().retainAll(factions);
            removed += before - byFaction.size();
        }
        records.values().removeIf(Map::isEmpty);
        return removed;
    }

    private void log(final Event event)
    {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) events.removeLast();
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag players = new ListTag();
        records.forEach((player, byFaction) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("player", player);
            final ListTag factions = new ListTag();
            byFaction.forEach((faction, record) -> {
                final CompoundTag value = record.save();
                value.putUUID("faction", faction);
                factions.add(value);
            });
            entry.put("factions", factions);
            players.add(entry);
        });
        tag.put("players", players);
        final ListTag log = new ListTag();
        events.forEach(event -> log.add(event.save()));
        tag.put("events", log);
        return tag;
    }

    public static ReputationRegistry load(final CompoundTag tag)
    {
        final ReputationRegistry registry = new ReputationRegistry();
        tag.getList("players", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            if (!entry.hasUUID("player")) return;
            final Map<UUID, Record> byFaction = new LinkedHashMap<>();
            entry.getList("factions", Tag.TAG_COMPOUND).forEach(raw -> {
                final CompoundTag record = (CompoundTag) raw;
                if (record.hasUUID("faction")) byFaction.put(record.getUUID("faction"), Record.load(record));
            });
            if (!byFaction.isEmpty()) registry.records.put(entry.getUUID("player"), byFaction);
        });
        final ListTag log = tag.getList("events", Tag.TAG_COMPOUND);
        for (int index = 0; index < log.size() && index < MAX_EVENTS; index++)
        {
            try { registry.events.addLast(Event.load(log.getCompound(index))); }
            catch (RuntimeException ignored) { /* an unreadable audit entry is dropped; standings are unaffected */ }
        }
        return registry;
    }
}
