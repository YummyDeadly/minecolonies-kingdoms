package com.minecolonies.kingdoms.war;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persisted per-pair war bookkeeping (Phase 10): consecutive hostile diplomacy evaluations, truces after a war, and
 * the war ordinal of each pair (so war IDs are stable and never reused). Keys are unordered faction pairs. Bounded by
 * the number of neighbour pairs; entries of pairs without hostility, truce, or history are pruned.
 */
public final class WarState
{
    private final Map<String, Integer> hostility = new HashMap<>();
    private final Map<String, Long> truces = new HashMap<>();
    private final Map<String, Integer> ordinals = new HashMap<>();

    static String key(final UUID a, final UUID b)
    {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    public int hostility(final UUID a, final UUID b) { return hostility.getOrDefault(key(a, b), 0); }
    public long truceUntil(final UUID a, final UUID b) { return truces.getOrDefault(key(a, b), Long.MIN_VALUE); }
    public int wars(final UUID a, final UUID b) { return ordinals.getOrDefault(key(a, b), 0); }

    int observeHostility(final UUID a, final UUID b, final boolean hostile)
    {
        final String key = key(a, b);
        if (!hostile)
        {
            hostility.remove(key);
            return 0;
        }
        return hostility.merge(key, 1, (x, y) -> Math.min(1_000, x + y));
    }

    void resetHostility(final UUID a, final UUID b) { hostility.remove(key(a, b)); }

    /** Pairs that are no longer neighbours lose their streak ("consecutive" means evaluated as neighbours every time). */
    void retainHostility(final java.util.Set<String> neighbours) { hostility.keySet().retainAll(neighbours); }

    void truce(final UUID a, final UUID b, final long until) { truces.merge(key(a, b), until, Math::max); }

    int nextWarOrdinal(final UUID a, final UUID b) { return ordinals.merge(key(a, b), 1, Integer::sum); }

    /** Drops expired truces. War ordinals are kept (they are tiny and keep war IDs unique forever). */
    void prune(final long gameTime)
    {
        truces.values().removeIf(until -> until <= gameTime);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.put("hostility", write(hostility));
        final ListTag truceList = new ListTag();
        truces.forEach((key, until) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putString("pair", key);
            entry.putLong("until", until);
            truceList.add(entry);
        });
        tag.put("truces", truceList);
        tag.put("ordinals", write(ordinals));
        return tag;
    }

    public static WarState load(final CompoundTag tag)
    {
        final WarState state = new WarState();
        read(tag.getList("hostility", Tag.TAG_COMPOUND), state.hostility);
        tag.getList("truces", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            state.truces.put(entry.getString("pair"), entry.getLong("until"));
        });
        read(tag.getList("ordinals", Tag.TAG_COMPOUND), state.ordinals);
        return state;
    }

    private static ListTag write(final Map<String, Integer> values)
    {
        final ListTag list = new ListTag();
        values.forEach((key, value) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putString("pair", key);
            entry.putInt("value", value);
            list.add(entry);
        });
        return list;
    }

    private static void read(final ListTag list, final Map<String, Integer> into)
    {
        list.forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            into.put(entry.getString("pair"), Math.max(0, entry.getInt("value")));
        });
    }
}
