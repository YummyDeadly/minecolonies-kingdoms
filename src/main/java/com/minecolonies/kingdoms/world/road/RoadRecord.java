package com.minecolonies.kingdoms.world.road;

import com.minecolonies.kingdoms.world.road.geometry.RoadGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RoadRecord
{
    private final UUID id;
    private final UUID firstSettlementId;
    private final UUID secondSettlementId;
    private final ResourceLocation dimension;
    private final RoadType type;
    private final RoadGeometry geometry;
    private final boolean physicalGeometry;
    private final double length;
    private RoadStatus status;
    private final int generationVersion;
    private final Map<Long, Integer> generatedChunks = new LinkedHashMap<>();

    public RoadRecord(final UUID id, final UUID firstSettlementId, final UUID secondSettlementId,
        final ResourceLocation dimension, final RoadType type, final List<BlockPos> polyline,
        final RoadStatus status, final int generationVersion)
    {
        this(id, firstSettlementId, secondSettlementId, dimension, type, RoadGeometry.legacy(polyline), true, status, generationVersion);
    }

    public RoadRecord(final UUID id, final UUID firstSettlementId, final UUID secondSettlementId,
        final ResourceLocation dimension, final RoadType type, final RoadGeometry geometry,
        final RoadStatus status, final int generationVersion)
    {
        this(id, firstSettlementId, secondSettlementId, dimension, type, geometry, true, status, generationVersion);
    }

    private RoadRecord(final UUID id, final UUID firstSettlementId, final UUID secondSettlementId,
        final ResourceLocation dimension, final RoadType type, final RoadGeometry geometry, final boolean physicalGeometry,
        final RoadStatus status, final int generationVersion)
    {
        this.id = Objects.requireNonNull(id);
        this.firstSettlementId = Objects.requireNonNull(firstSettlementId);
        this.secondSettlementId = Objects.requireNonNull(secondSettlementId);
        if (firstSettlementId.equals(secondSettlementId)) throw new IllegalArgumentException("Road endpoints must differ");
        this.dimension = Objects.requireNonNull(dimension);
        this.type = Objects.requireNonNull(type);
        this.geometry = Objects.requireNonNull(geometry);
        this.physicalGeometry = physicalGeometry;
        this.length = geometry.length();
        this.status = Objects.requireNonNull(status);
        this.generationVersion = Math.max(1, generationVersion);
    }

    public static RoadRecord unrouteable(final UUID id, final UUID firstSettlementId, final UUID secondSettlementId,
        final ResourceLocation dimension, final RoadType type, final BlockPos first, final BlockPos second, final int generationVersion)
    {
        return new RoadRecord(id, firstSettlementId, secondSettlementId, dimension, type,
            RoadGeometry.legacy(List.of(first, second)), false, RoadStatus.UNROUTABLE, generationVersion);
    }

    public UUID id() { return id; }
    public UUID firstSettlementId() { return firstSettlementId; }
    public UUID secondSettlementId() { return secondSettlementId; }
    public ResourceLocation dimension() { return dimension; }
    public RoadType type() { return type; }
    public List<BlockPos> polyline() { return geometry.positions(); }
    public RoadGeometry geometry() { return geometry; }
    public boolean hasPhysicalGeometry() { return physicalGeometry; }
    public double length() { return length; }
    public RoadStatus status() { return status; }
    public int generationVersion() { return generationVersion; }
    public void status(final RoadStatus value) { status = Objects.requireNonNull(value); }
    public boolean needsGeneration(final long chunk) { return generatedChunks.getOrDefault(chunk, 0) < generationVersion; }
    public void markGenerated(final long chunk) { generatedChunks.put(chunk, generationVersion); }
    public void invalidateChunk(final long chunk) { generatedChunks.remove(chunk); }
    public Map<Long, Integer> generatedChunks() { return Map.copyOf(generatedChunks); }
    public UUID other(final UUID endpoint)
    {
        if (firstSettlementId.equals(endpoint)) return secondSettlementId;
        if (secondSettlementId.equals(endpoint)) return firstSettlementId;
        throw new IllegalArgumentException("Settlement is not a road endpoint");
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("first", firstSettlementId);
        tag.putUUID("second", secondSettlementId);
        tag.putString("dimension", dimension.toString());
        tag.putString("type", type.name());
        tag.putString("status", status.name());
        tag.putInt("generationVersion", generationVersion);
        tag.putBoolean("physicalGeometry", physicalGeometry);
        tag.put("geometry", geometry.save());
        final ListTag points = new ListTag();
        polyline().forEach(point -> { final CompoundTag value = new CompoundTag(); value.putLong("pos", point.asLong()); points.add(value); });
        tag.put("polyline", points);
        final ListTag chunks = new ListTag();
        generatedChunks.forEach((chunk, version) -> { final CompoundTag value = new CompoundTag(); value.putLong("chunk", chunk); value.putInt("version", version); chunks.add(value); });
        tag.put("generatedChunks", chunks);
        return tag;
    }

    public static RoadRecord load(final CompoundTag tag)
    {
        final List<BlockPos> points = new ArrayList<>();
        tag.getList("polyline", Tag.TAG_COMPOUND).forEach(value -> points.add(BlockPos.of(((CompoundTag) value).getLong("pos"))));
        final RoadGeometry geometry = tag.contains("geometry", Tag.TAG_COMPOUND)
            ? RoadGeometry.load(tag.getCompound("geometry")) : RoadGeometry.legacy(points);
        final boolean physical = !tag.contains("physicalGeometry") || tag.getBoolean("physicalGeometry");
        final RoadRecord road = new RoadRecord(tag.getUUID("id"), tag.getUUID("first"), tag.getUUID("second"),
            ResourceLocation.parse(tag.getString("dimension")), RoadType.valueOf(tag.getString("type")), geometry, physical,
            RoadStatus.valueOf(tag.getString("status")), tag.getInt("generationVersion"));
        tag.getList("generatedChunks", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            road.generatedChunks.put(entry.getLong("chunk"), entry.getInt("version"));
        });
        return road;
    }

}
