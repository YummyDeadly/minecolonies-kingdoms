package com.minecolonies.kingdoms.military;

import com.minecolonies.kingdoms.KingdomsMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted military state (schema 14): one garrison per NPC settlement and the time of the last security evaluation.
 * Physical guards are never persisted. {@link MilitaryService} is the only writer.
 */
public final class MilitaryRegistry
{
    private final Map<UUID, GarrisonRecord> garrisons = new LinkedHashMap<>();
    private long lastEvaluatedAt = -1L;

    public Collection<GarrisonRecord> garrisons() { return Collections.unmodifiableCollection(garrisons.values()); }
    public Optional<GarrisonRecord> garrison(final UUID settlementId) { return Optional.ofNullable(garrisons.get(settlementId)); }
    public long lastEvaluatedAt() { return lastEvaluatedAt; }

    void setLastEvaluatedAt(final long gameTime) { lastEvaluatedAt = gameTime; }
    void put(final GarrisonRecord garrison) { garrisons.put(garrison.settlementId(), garrison); }

    /** Drops garrisons of settlements that no longer exist; returns how many were removed. */
    int retain(final Set<UUID> settlements)
    {
        final int before = garrisons.size();
        garrisons.keySet().retainAll(settlements);
        return before - garrisons.size();
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        garrisons.values().forEach(value -> list.add(value.save()));
        tag.put("garrisons", list);
        tag.putLong("lastEvaluatedAt", lastEvaluatedAt);
        return tag;
    }

    public static MilitaryRegistry load(final CompoundTag tag)
    {
        final MilitaryRegistry registry = new MilitaryRegistry();
        tag.getList("garrisons", Tag.TAG_COMPOUND).forEach(value -> {
            try
            {
                final GarrisonRecord garrison = GarrisonRecord.load((CompoundTag) value);
                registry.garrisons.put(garrison.settlementId(), garrison);
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Dropping unreadable garrison {}", value, exception);
            }
        });
        registry.lastEvaluatedAt = tag.contains("lastEvaluatedAt") ? tag.getLong("lastEvaluatedAt") : -1L;
        return registry;
    }
}
