package com.minecolonies.kingdoms.world.settlement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SettlementRegistry
{
    private final Map<UUID, SettlementRecord> records = new LinkedHashMap<>();
    private final Map<String, UUID> byRegion = new LinkedHashMap<>();
    private final Set<String> plannedRegions = new LinkedHashSet<>();

    public Collection<SettlementRecord> records() { return Collections.unmodifiableCollection(records.values()); }
    public Optional<SettlementRecord> get(final UUID id) { return Optional.ofNullable(records.get(id)); }
    public Optional<SettlementRecord> inRegion(final SettlementRegion region)
    {
        final UUID id = byRegion.get(region.key());
        return id == null ? Optional.empty() : get(id);
    }
    public boolean isRegionPlanned(final SettlementRegion region) { return plannedRegions.contains(region.key()); }
    public void markRegionPlanned(final SettlementRegion region) { plannedRegions.add(region.key()); }
    public boolean put(final SettlementRecord record)
    {
        if (records.putIfAbsent(record.id(), record) != null) return false;
        byRegion.put(record.creationRegion().key(), record.id());
        plannedRegions.add(record.creationRegion().key());
        return true;
    }

    public CompoundTag save()
    {
        final CompoundTag root = new CompoundTag();
        final ListTag entries = new ListTag();
        records.values().forEach(record -> entries.add(record.save()));
        root.put("records", entries);
        final ListTag regions = new ListTag();
        plannedRegions.forEach(key -> { final CompoundTag tag = new CompoundTag(); tag.putString("key", key); regions.add(tag); });
        root.put("plannedRegions", regions);
        return root;
    }

    public static SettlementRegistry load(final CompoundTag root)
    {
        final SettlementRegistry registry = new SettlementRegistry();
        root.getList("records", Tag.TAG_COMPOUND).forEach(value -> registry.put(SettlementRecord.load((CompoundTag) value)));
        root.getList("plannedRegions", Tag.TAG_COMPOUND).forEach(value -> registry.plannedRegions.add(((CompoundTag) value).getString("key")));
        return registry;
    }
}
