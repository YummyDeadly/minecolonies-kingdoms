package com.minecolonies.kingdoms.world.settlement.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashSet;
import java.util.Set;

public record StructureFootprint(int minX, int minZ, int maxX, int maxZ)
{
    public StructureFootprint
    {
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Inverted footprint");
    }

    public static StructureFootprint centered(final BlockPos center, final int width, final int depth)
    {
        if (width < 1 || depth < 1) throw new IllegalArgumentException("Footprint dimensions must be positive");
        final int left = (width - 1) / 2;
        final int back = (depth - 1) / 2;
        return new StructureFootprint(center.getX() - left, center.getZ() - back,
            center.getX() + width - left - 1, center.getZ() + depth - back - 1);
    }

    public int width() { return maxX - minX + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public boolean contains(final int x, final int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    public StructureFootprint expand(final int padding)
    {
        if (padding < 0) throw new IllegalArgumentException("Padding must not be negative");
        return new StructureFootprint(minX - padding, minZ - padding, maxX + padding, maxZ + padding);
    }
    public boolean intersects(final StructureFootprint other)
    {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }
    public boolean intersects(final ChunkPos chunk)
    {
        return maxX >= chunk.getMinBlockX() && minX <= chunk.getMaxBlockX()
            && maxZ >= chunk.getMinBlockZ() && minZ <= chunk.getMaxBlockZ();
    }
    public Set<Long> chunks()
    {
        final Set<Long> result = new LinkedHashSet<>();
        for (int x = minX >> 4; x <= maxX >> 4; x++)
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) result.add(ChunkPos.asLong(x, z));
        return Set.copyOf(result);
    }
}
