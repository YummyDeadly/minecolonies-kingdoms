package com.minecolonies.kingdoms.world.settlement.growth;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, in-memory record of chunks that NeoForge reported as newly generated during this server session. It is
 * deliberately not persisted: after a restart nothing is assumed to be unexplored, so existing-world protection can
 * only become stricter, never weaker.
 */
public final class FreshChunkTracker
{
    private final int capacity;
    private final LinkedHashMap<Key, Boolean> chunks;

    public FreshChunkTracker(final int capacity)
    {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
        this.chunks = new LinkedHashMap<>(Math.min(capacity, 4_096), 0.75F, false)
        {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<Key, Boolean> eldest)
            {
                return size() > FreshChunkTracker.this.capacity;
            }
        };
    }

    public synchronized void add(final ResourceKey<Level> dimension, final long chunk)
    {
        chunks.put(new Key(Objects.requireNonNull(dimension), chunk), Boolean.TRUE);
    }

    public synchronized boolean contains(final ResourceKey<Level> dimension, final long chunk)
    {
        return chunks.containsKey(new Key(dimension, chunk));
    }

    public synchronized int size() { return chunks.size(); }
    public synchronized void clear() { chunks.clear(); }

    private record Key(ResourceKey<Level> dimension, long chunk) {}
}
