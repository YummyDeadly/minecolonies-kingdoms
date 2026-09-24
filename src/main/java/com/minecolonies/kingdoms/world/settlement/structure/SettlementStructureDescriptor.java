package com.minecolonies.kingdoms.world.settlement.structure;

import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Objects;
import java.util.Set;

public record SettlementStructureDescriptor(String sourceId, String structureId, SettlementBuildingType buildingType,
    String styleFamily, int structureLevel, int width, int height, int depth, StructureFootprint localFootprint, BlockPos entranceOffset,
    Direction entranceFacing, Set<StructureTransform> supportedTransforms, TerrainPlacementConstraints terrainConstraints)
{
    public SettlementStructureDescriptor
    {
        sourceId = require(sourceId, "sourceId");
        structureId = require(structureId, "structureId");
        styleFamily = require(styleFamily, "styleFamily");
        Objects.requireNonNull(buildingType);
        Objects.requireNonNull(entranceOffset);
        Objects.requireNonNull(localFootprint);
        Objects.requireNonNull(entranceFacing);
        Objects.requireNonNull(terrainConstraints);
        if (!entranceFacing.getAxis().isHorizontal()) throw new IllegalArgumentException("Entrance must face horizontally");
        if (structureLevel < 0 || width < 1 || height < 1 || depth < 1 || width > 64 || height > 64 || depth > 64)
            throw new IllegalArgumentException("Unsupported structure dimensions/level");
        if (localFootprint.width() != width || localFootprint.depth() != depth)
            throw new IllegalArgumentException("Local footprint does not match structure dimensions");
        supportedTransforms = Set.copyOf(supportedTransforms);
        if (supportedTransforms.isEmpty()) throw new IllegalArgumentException("At least one transform is required");
    }

    public String stableId() { return sourceId + ':' + structureId; }
    public StructureFootprint footprint(final BlockPos anchor, final StructureTransform transform)
    {
        final BlockPos[] corners = {
            transform.apply(new BlockPos(localFootprint.minX(), 0, localFootprint.minZ())),
            transform.apply(new BlockPos(localFootprint.maxX(), 0, localFootprint.minZ())),
            transform.apply(new BlockPos(localFootprint.minX(), 0, localFootprint.maxZ())),
            transform.apply(new BlockPos(localFootprint.maxX(), 0, localFootprint.maxZ()))};
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final BlockPos corner : corners)
        {
            minX = Math.min(minX, corner.getX()); minZ = Math.min(minZ, corner.getZ());
            maxX = Math.max(maxX, corner.getX()); maxZ = Math.max(maxZ, corner.getZ());
        }
        return new StructureFootprint(anchor.getX() + minX, anchor.getZ() + minZ, anchor.getX() + maxX, anchor.getZ() + maxZ);
    }
    public BlockPos entrance(final BlockPos anchor, final StructureTransform transform)
    {
        return anchor.offset(transform.apply(entranceOffset));
    }
    public Direction entranceFacing(final StructureTransform transform) { return transform.apply(entranceFacing); }

    private static String require(final String value, final String field)
    {
        final String result = Objects.requireNonNull(value, field).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
