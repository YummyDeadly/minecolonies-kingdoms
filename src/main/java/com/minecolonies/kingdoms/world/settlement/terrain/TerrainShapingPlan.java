package com.minecolonies.kingdoms.world.settlement.terrain;

import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

public record TerrainShapingPlan(int version, BuildingPad pad, StructureFootprint clearanceZone)
{
    public static final int CURRENT_VERSION = 1;

    public TerrainShapingPlan
    {
        Objects.requireNonNull(pad); Objects.requireNonNull(clearanceZone);
        if (version < 1 || !clearanceZone.intersects(pad.footprint()))
            throw new IllegalArgumentException("Invalid terrain shaping plan");
    }

    public TerrainShapingPlan withPad(final BuildingPad value) { return new TerrainShapingPlan(version, value, clearanceZone); }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag(); tag.putInt("version", version);
        tag.put("pad", pad.save()); tag.putInt("clearMinX", clearanceZone.minX());
        tag.putInt("clearMinZ", clearanceZone.minZ()); tag.putInt("clearMaxX", clearanceZone.maxX());
        tag.putInt("clearMaxZ", clearanceZone.maxZ()); return tag;
    }

    public static TerrainShapingPlan load(final CompoundTag tag)
    {
        return new TerrainShapingPlan(Math.max(1, tag.getInt("version")), BuildingPad.load(tag.getCompound("pad")),
            new StructureFootprint(tag.getInt("clearMinX"), tag.getInt("clearMinZ"),
                tag.getInt("clearMaxX"), tag.getInt("clearMaxZ")));
    }
}
