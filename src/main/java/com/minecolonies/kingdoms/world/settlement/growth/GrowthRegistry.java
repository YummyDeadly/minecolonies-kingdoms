package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GrowthRegistry
{
    private final Map<UUID, SettlementGrowthState> states = new LinkedHashMap<>();
    private final Map<UUID, SettlementBuildingRecord> buildings = new LinkedHashMap<>();
    private final Map<UUID, SettlementLayoutPlan> layouts = new LinkedHashMap<>();
    /** Rebuilt from the authoritative building records; never persisted separately. */
    private final Map<Long, Set<UUID>> buildingIdsByChunk = new LinkedHashMap<>();

    public Collection<SettlementGrowthState> states() { return Collections.unmodifiableCollection(states.values()); }
    public Collection<SettlementBuildingRecord> buildings() { return Collections.unmodifiableCollection(buildings.values()); }
    public Optional<SettlementGrowthState> state(final UUID settlementId) { return Optional.ofNullable(states.get(settlementId)); }
    public SettlementGrowthState stateOrCreate(final UUID settlementId) { return states.computeIfAbsent(settlementId, SettlementGrowthState::new); }
    public Optional<SettlementBuildingRecord> building(final UUID id) { return Optional.ofNullable(buildings.get(id)); }
    public Collection<SettlementLayoutPlan> layouts() { return Collections.unmodifiableCollection(layouts.values()); }
    public Optional<SettlementLayoutPlan> layout(final UUID settlementId) { return Optional.ofNullable(layouts.get(settlementId)); }
    public SettlementLayoutPlan layoutOrCreate(final UUID settlementId, final java.util.function.Supplier<SettlementLayoutPlan> factory)
    { return layouts.computeIfAbsent(settlementId, ignored -> factory.get()); }
    public boolean putDistrict(final SettlementLayoutPlan layout, final List<SettlementBuildingRecord> records)
    {
        if (layouts.containsKey(layout.settlementId()) || records.isEmpty()
            || records.stream().anyMatch(record -> buildings.containsKey(record.id())
                || !record.settlementId().equals(layout.settlementId()))) return false;
        final Set<UUID> unique = new HashSet<>();
        if (records.stream().anyMatch(record -> !unique.add(record.id()))) return false;
        layouts.put(layout.settlementId(), layout);
        records.forEach(this::put);
        return true;
    }
    public boolean put(final SettlementBuildingRecord record)
    {
        if (buildings.putIfAbsent(record.id(), record) != null)
        {
            return false;
        }
        for (final long chunk : record.footprintChunks())
        {
            buildingIdsByChunk.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(record.id());
        }
        return true;
    }
    public List<SettlementBuildingRecord> forSettlement(final UUID settlementId)
    {
        return buildings.values().stream().filter(value -> value.settlementId().equals(settlementId))
            .sorted(Comparator.comparingInt(SettlementBuildingRecord::sequence).thenComparing(SettlementBuildingRecord::id)).toList();
    }
    public List<SettlementBuildingRecord> inChunk(final ChunkPos chunk)
    {
        return buildingIdsByChunk.getOrDefault(chunk.toLong(), Set.of()).stream()
            .map(buildings::get).filter(value -> value != null && value.intersects(chunk))
            .sorted(Comparator.comparing(SettlementBuildingRecord::id)).toList();
    }

    public CompoundTag save()
    {
        final CompoundTag root = new CompoundTag();
        final ListTag stateList = new ListTag(); states.values().forEach(value -> stateList.add(value.save())); root.put("states", stateList);
        final ListTag buildingList = new ListTag(); buildings.values().forEach(value -> buildingList.add(value.save())); root.put("buildings", buildingList);
        final ListTag layoutList = new ListTag(); layouts.values().forEach(value -> layoutList.add(value.save())); root.put("layouts", layoutList);
        return root;
    }

    public static GrowthRegistry load(final CompoundTag root)
    {
        final GrowthRegistry registry = new GrowthRegistry();
        root.getList("states", Tag.TAG_COMPOUND).forEach(value -> {
            final SettlementGrowthState state = SettlementGrowthState.load((CompoundTag) value);
            registry.states.put(state.settlementId(), state);
        });
        root.getList("buildings", Tag.TAG_COMPOUND).forEach(value -> registry.put(SettlementBuildingRecord.load((CompoundTag) value)));
        root.getList("layouts", Tag.TAG_COMPOUND).forEach(value -> {
            final SettlementLayoutPlan layout = SettlementLayoutPlan.load((CompoundTag) value);
            registry.layouts.put(layout.settlementId(), layout);
        });
        return registry;
    }
}
