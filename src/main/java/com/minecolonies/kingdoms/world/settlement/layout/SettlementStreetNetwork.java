package com.minecolonies.kingdoms.world.settlement.layout;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SettlementStreetNetwork
{
    private final Map<UUID, SettlementStreetSegment> segments = new LinkedHashMap<>();

    public boolean put(final SettlementStreetSegment segment) { return segments.putIfAbsent(segment.id(), segment) == null; }
    public Collection<SettlementStreetSegment> segments() { return List.copyOf(segments.values()); }
    public Optional<SettlementStreetSegment> get(final UUID id) { return Optional.ofNullable(segments.get(id)); }
    public List<BlockPos> points()
    {
        return segments.values().stream().sorted(Comparator.comparing(SettlementStreetSegment::id))
            .flatMap(segment -> segment.points().stream()).distinct()
            .sorted(Comparator.comparingInt((BlockPos value) -> value.getX()).thenComparingInt(value -> value.getZ())
                .thenComparingInt(value -> value.getY())).toList();
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag(); final ListTag list = new ListTag();
        segments.values().forEach(segment -> list.add(segment.save())); tag.put("segments", list); return tag;
    }
    public static SettlementStreetNetwork load(final CompoundTag tag)
    {
        final SettlementStreetNetwork result = new SettlementStreetNetwork();
        tag.getList("segments", Tag.TAG_COMPOUND).forEach(value -> result.put(SettlementStreetSegment.load((CompoundTag) value)));
        return result;
    }
}
