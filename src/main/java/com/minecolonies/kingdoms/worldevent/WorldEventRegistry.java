package com.minecolonies.kingdoms.worldevent;

import com.minecolonies.kingdoms.KingdomsMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persisted world-event state (schema 16): the events, per (type, subject) cooldowns, the world's event seed, and the
 * number of evaluations done, which is the index (and so the seed) of the next one: an evaluation is never repeated,
 * and so never re-rolled, after a restart. Written only by {@link WorldEventService}.
 */
public final class WorldEventRegistry
{
    public static final long ENDED_RETENTION_TICKS = 168_000L;
    public static final int MAX_ENDED = 128;

    private final Map<UUID, WorldEventRecord> events = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private long seed;
    private boolean seeded;
    private long evaluations;
    private long lastEvaluatedAt = -1L;
    private int adminCounter;

    public Collection<WorldEventRecord> events() { return Collections.unmodifiableCollection(events.values()); }
    public Optional<WorldEventRecord> event(final UUID id) { return Optional.ofNullable(events.get(id)); }
    public List<WorldEventRecord> open() { return events.values().stream().filter(WorldEventRecord::open).toList(); }
    public long seed() { return seed; }
    public boolean seeded() { return seeded; }
    /** Evaluations done so far; also the index of the next evaluation. */
    public long evaluations() { return evaluations; }
    public long lastEvaluatedAt() { return lastEvaluatedAt; }
    public int adminCounter() { return adminCounter; }

    /** Fixes the world's event seed once (from the world seed); later calls change nothing. */
    public void seedIfUnset(final long value)
    {
        if (seeded) return;
        seed = value;
        seeded = true;
    }

    /** Resolves a full UUID or a unique prefix of at least 6 characters. */
    public Optional<WorldEventRecord> resolve(final String text)
    {
        try { return event(UUID.fromString(text)); }
        catch (IllegalArgumentException ignored) { /* prefix */ }
        final String prefix = text.toLowerCase(java.util.Locale.ROOT);
        if (prefix.length() < 6) return Optional.empty();
        final List<WorldEventRecord> matches = events.values().stream().filter(value -> value.id().toString().startsWith(prefix)).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------ writes (service only)

    boolean put(final WorldEventRecord record)
    {
        return events.putIfAbsent(record.id(), record) == null;
    }

    void markEvaluated(final long gameTime)
    {
        evaluations++;
        lastEvaluatedAt = gameTime;
    }

    int nextAdminCounter() { return ++adminCounter; }

    static String cooldownKey(final WorldEventType type, final UUID subject)
    {
        return type.name() + '|' + subject;
    }

    long lastPlanned(final WorldEventType type, final UUID subject)
    {
        return cooldowns.getOrDefault(cooldownKey(type, subject), Long.MIN_VALUE);
    }

    void planned(final WorldEventType type, final UUID subject, final long gameTime)
    {
        cooldowns.merge(cooldownKey(type, subject), gameTime, Math::max);
    }

    /** Drops cooldowns that have run out and old ended events (bounded). */
    int prune(final long gameTime, final long cooldownTicks)
    {
        final int before = cooldowns.size() + events.size();
        cooldowns.values().removeIf(at -> gameTime - at >= cooldownTicks);
        events.values().removeIf(value -> value.status().terminal() && gameTime - value.resolvedAt() > ENDED_RETENTION_TICKS);
        final List<WorldEventRecord> ended = new ArrayList<>(events.values().stream().filter(value -> value.status().terminal()).toList());
        if (ended.size() > MAX_ENDED)
        {
            ended.sort(Comparator.comparingLong(WorldEventRecord::resolvedAt));
            ended.subList(0, ended.size() - MAX_ENDED).forEach(value -> events.remove(value.id()));
        }
        return before - (cooldowns.size() + events.size());
    }

    public int cooldowns() { return cooldowns.size(); }

    // ------------------------------------------------------------------------------------------------ persistence

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        events.values().forEach(value -> list.add(value.save()));
        tag.put("events", list);
        final ListTag cooldownList = new ListTag();
        cooldowns.forEach((key, at) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putString("key", key);
            entry.putLong("at", at);
            cooldownList.add(entry);
        });
        tag.put("cooldowns", cooldownList);
        tag.putLong("seed", seed);
        tag.putBoolean("seeded", seeded);
        tag.putLong("evaluations", evaluations);
        tag.putLong("lastEvaluatedAt", lastEvaluatedAt);
        tag.putInt("adminCounter", adminCounter);
        return tag;
    }

    public static WorldEventRegistry load(final CompoundTag tag)
    {
        final WorldEventRegistry registry = new WorldEventRegistry();
        tag.getList("events", Tag.TAG_COMPOUND).forEach(value -> {
            try
            {
                final WorldEventRecord record = WorldEventRecord.load((CompoundTag) value);
                registry.events.put(record.id(), record);
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Dropping unreadable world event {}", value, exception);
            }
        });
        tag.getList("cooldowns", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            if (!entry.getString("key").isEmpty()) registry.cooldowns.put(entry.getString("key"), entry.getLong("at"));
        });
        registry.seed = tag.getLong("seed");
        registry.seeded = tag.getBoolean("seeded");
        registry.evaluations = Math.max(0L, tag.getLong("evaluations"));
        registry.lastEvaluatedAt = tag.contains("lastEvaluatedAt") ? tag.getLong("lastEvaluatedAt") : -1L;
        registry.adminCounter = Math.max(0, tag.getInt("adminCounter"));
        return registry;
    }
}
