package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SettlementLot(UUID buildingId, StructureFootprint footprint, BlockPos entrance,
    Direction entranceFacing, BlockPos streetJoin, List<UUID> streetSegmentIds)
{
    public SettlementLot
    {
        Objects.requireNonNull(buildingId); Objects.requireNonNull(footprint); Objects.requireNonNull(entrance);
        Objects.requireNonNull(entranceFacing); Objects.requireNonNull(streetJoin);
        streetSegmentIds = List.copyOf(streetSegmentIds);
        if (!entranceFacing.getAxis().isHorizontal() || streetSegmentIds.isEmpty())
            throw new IllegalArgumentException("Lot requires horizontal frontage and a street");
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag(); tag.putUUID("building", buildingId);
        tag.putInt("minX", footprint.minX()); tag.putInt("minZ", footprint.minZ());
        tag.putInt("maxX", footprint.maxX()); tag.putInt("maxZ", footprint.maxZ());
        tag.putLong("entrance", entrance.asLong()); tag.putString("facing", entranceFacing.getName()); tag.putLong("streetJoin", streetJoin.asLong());
        final ListTag streets = new ListTag();
        streetSegmentIds.forEach(id -> { final CompoundTag value = new CompoundTag(); value.putUUID("id", id); streets.add(value); });
        tag.put("streets", streets); return tag;
    }
    public static SettlementLot load(final CompoundTag tag)
    {
        final List<UUID> streets = new ArrayList<>();
        tag.getList("streets", Tag.TAG_COMPOUND).forEach(value -> streets.add(((CompoundTag) value).getUUID("id")));
        return new SettlementLot(tag.getUUID("building"), new StructureFootprint(tag.getInt("minX"), tag.getInt("minZ"), tag.getInt("maxX"), tag.getInt("maxZ")),
            BlockPos.of(tag.getLong("entrance")), Direction.byName(tag.getString("facing")), BlockPos.of(tag.getLong("streetJoin")), streets);
    }
}
