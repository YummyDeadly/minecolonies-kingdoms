package com.minecolonies.kingdoms.world.settlement.terrain;

import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record BuildingPad(StructureFootprint footprint, int targetHeight, int originalMinimumHeight,
    int originalMaximumHeight, int cutVolume, int fillVolume, int maximumCutDepth,
    int maximumFillDepth, TerrainShapingMode mode, List<RetainingWall> retainingWalls)
{
    public BuildingPad
    {
        Objects.requireNonNull(footprint); Objects.requireNonNull(mode);
        retainingWalls = List.copyOf(retainingWalls);
        if (originalMinimumHeight > originalMaximumHeight || cutVolume < 0 || fillVolume < 0
            || maximumCutDepth < 0 || maximumFillDepth < 0)
            throw new IllegalArgumentException("Invalid building pad");
    }
    public int terrainWorkVolume() { return cutVolume + fillVolume; }
    public BuildingPad withMode(final TerrainShapingMode value)
    {
        return new BuildingPad(footprint, targetHeight, originalMinimumHeight, originalMaximumHeight, cutVolume,
            fillVolume, maximumCutDepth, maximumFillDepth, value, retainingWalls);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("minX", footprint.minX()); tag.putInt("minZ", footprint.minZ());
        tag.putInt("maxX", footprint.maxX()); tag.putInt("maxZ", footprint.maxZ());
        tag.putInt("target", targetHeight); tag.putInt("originalMin", originalMinimumHeight);
        tag.putInt("originalMax", originalMaximumHeight); tag.putInt("cutVolume", cutVolume);
        tag.putInt("fillVolume", fillVolume); tag.putInt("maxCut", maximumCutDepth);
        tag.putInt("maxFill", maximumFillDepth); tag.putString("mode", mode.name());
        final ListTag walls = new ListTag(); retainingWalls.forEach(wall -> walls.add(wall.save())); tag.put("walls", walls);
        return tag;
    }

    public static BuildingPad load(final CompoundTag tag)
    {
        final List<RetainingWall> walls = new ArrayList<>();
        tag.getList("walls", Tag.TAG_COMPOUND).forEach(value -> walls.add(RetainingWall.load((CompoundTag) value)));
        return new BuildingPad(new StructureFootprint(tag.getInt("minX"), tag.getInt("minZ"),
            tag.getInt("maxX"), tag.getInt("maxZ")), tag.getInt("target"), tag.getInt("originalMin"),
            tag.getInt("originalMax"), tag.getInt("cutVolume"), tag.getInt("fillVolume"),
            tag.getInt("maxCut"), tag.getInt("maxFill"), TerrainShapingMode.valueOf(tag.getString("mode")), walls);
    }
}
