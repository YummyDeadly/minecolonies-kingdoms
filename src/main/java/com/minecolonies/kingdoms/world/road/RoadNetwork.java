package com.minecolonies.kingdoms.world.road;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RoadNetwork
{
    private final Map<UUID, RoadRecord> roads = new LinkedHashMap<>();
    private final Map<Long, Set<UUID>> spatialIndex = new LinkedHashMap<>();

    public Collection<RoadRecord> roads() { return Collections.unmodifiableCollection(roads.values()); }
    public Optional<RoadRecord> get(final UUID id) { return Optional.ofNullable(roads.get(id)); }
    public List<RoadRecord> incident(final UUID settlementId)
    {
        return roads.values().stream().filter(road -> road.firstSettlementId().equals(settlementId)
            || road.secondSettlementId().equals(settlementId)).toList();
    }
    public List<RoadRecord> inChunk(final ChunkPos chunk)
    {
        return spatialIndex.getOrDefault(chunk.toLong(), Set.of()).stream().map(roads::get).filter(java.util.Objects::nonNull).toList();
    }
    public boolean put(final RoadRecord road)
    {
        if (roads.putIfAbsent(road.id(), road) != null) return false;
        index(road);
        return true;
    }
    public void rebuildIndex()
    {
        spatialIndex.clear();
        roads.values().forEach(this::index);
    }

    public boolean isFullyGenerated(final RoadRecord road)
    {
        boolean indexed = false;
        for (final Map.Entry<Long, Set<UUID>> entry : spatialIndex.entrySet())
        {
            if (!entry.getValue().contains(road.id())) continue;
            indexed = true;
            if (road.needsGeneration(entry.getKey())) return false;
        }
        return indexed;
    }

    private void index(final RoadRecord road)
    {
        if (!road.hasPhysicalGeometry()) return;
        final int radius = road.type().width() + 1;
        for (final BlockPos point : road.polyline())
        {
            for (int ox = -radius; ox <= radius; ox += Math.max(1, radius))
                for (int oz = -radius; oz <= radius; oz += Math.max(1, radius))
                    spatialIndex.computeIfAbsent(ChunkPos.asLong((point.getX() + ox) >> 4, (point.getZ() + oz) >> 4), ignored -> new LinkedHashSet<>()).add(road.id());
        }
    }

    public CompoundTag save()
    {
        final CompoundTag root = new CompoundTag();
        final ListTag list = new ListTag();
        roads.values().forEach(road -> list.add(road.save()));
        root.put("records", list);
        return root;
    }

    public static RoadNetwork load(final CompoundTag root)
    {
        final RoadNetwork network = new RoadNetwork();
        root.getList("records", Tag.TAG_COMPOUND).forEach(value -> network.put(RoadRecord.load((CompoundTag) value)));
        return network;
    }
}
