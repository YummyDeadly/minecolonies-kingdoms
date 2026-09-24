package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SettlementBuildingRecord
{
    private final UUID id;
    private final UUID settlementId;
    private final SettlementBuildingType type;
    private final BlockPos anchor;
    private final int rotation;
    private final int sequence;
    private final int generationVersion;
    private final long createdAt;
    private SettlementBuildingOrigin origin;
    private String structureSource = "legacy_procedural";
    private String structureId = "legacy";
    private String styleFamily = "legacy";
    private boolean mirrored;
    private BlockPos entrance;
    private StructureFootprint footprint;
    private TerrainShapingPlan terrainShaping;
    private SettlementBuildingStatus status;
    private long readyAt;
    private long completedAt;
    private String blocker;
    private final Map<Long, Integer> generatedChunks = new LinkedHashMap<>();

    public SettlementBuildingRecord(final UUID id, final UUID settlementId, final SettlementBuildingType type,
        final BlockPos anchor, final int rotation, final int sequence, final int generationVersion,
        final long createdAt, final SettlementBuildingStatus status)
    {
        this(id, settlementId, type, anchor, rotation, sequence, generationVersion, createdAt, status,
            SettlementBuildingOrigin.LEGACY);
    }

    public SettlementBuildingRecord(final UUID id, final UUID settlementId, final SettlementBuildingType type,
        final BlockPos anchor, final int rotation, final int sequence, final int generationVersion,
        final long createdAt, final SettlementBuildingStatus status, final SettlementBuildingOrigin origin)
    {
        this.id = Objects.requireNonNull(id);
        this.settlementId = Objects.requireNonNull(settlementId);
        this.type = Objects.requireNonNull(type);
        this.anchor = Objects.requireNonNull(anchor).immutable();
        if (rotation % 90 != 0 || sequence < 0 || generationVersion < 1 || createdAt < 0L)
            throw new IllegalArgumentException("Invalid building record");
        this.rotation = Math.floorMod(rotation, 360);
        this.sequence = sequence;
        this.generationVersion = generationVersion;
        this.createdAt = createdAt;
        this.status = Objects.requireNonNull(status);
        this.origin = Objects.requireNonNull(origin);
        this.footprint = StructureFootprint.centered(anchor, type.halfWidth() * 2 + 1, type.halfDepth() * 2 + 1);
        this.entrance = anchor.offset(0, 0, type.halfDepth() + 1);
    }

    public UUID id() { return id; }
    public UUID settlementId() { return settlementId; }
    public SettlementBuildingType type() { return type; }
    public BlockPos anchor() { return anchor; }
    public int rotation() { return rotation; }
    public int sequence() { return sequence; }
    public int generationVersion() { return generationVersion; }
    public long createdAt() { return createdAt; }
    public SettlementBuildingOrigin origin() { return origin; }
    public long readyAt() { return readyAt; }
    public long completedAt() { return completedAt; }
    public SettlementBuildingStatus status() { return status; }
    public String structureSource() { return structureSource; }
    public String structureId() { return structureId; }
    public String styleFamily() { return styleFamily; }
    public boolean mirrored() { return mirrored; }
    public BlockPos entrance() { return entrance; }
    public StructureFootprint footprint() { return footprint; }
    public TerrainShapingPlan terrainShaping() { return terrainShaping; }
    public boolean usesBlueprintVisual() { return !"legacy_procedural".equals(structureSource); }
    public String blocker() { return blocker; }
    public Map<Long, Integer> generatedChunks() { return Map.copyOf(generatedChunks); }
    public boolean needsGeneration(final long chunk) { return generatedChunks.getOrDefault(chunk, 0) < generationVersion; }
    public void markGenerated(final long chunk) { generatedChunks.put(chunk, generationVersion); }
    public boolean intersects(final ChunkPos chunk)
    {
        return footprint.intersects(chunk);
    }
    public void assignVisual(final String source, final String visualId, final String style, final boolean mirror,
        final BlockPos visualEntrance, final StructureFootprint visualFootprint)
    {
        if (usesBlueprintVisual()) throw new IllegalStateException("Visual structure is already assigned");
        structureSource = requireText(source); structureId = requireText(visualId); styleFamily = requireText(style);
        mirrored = mirror; entrance = Objects.requireNonNull(visualEntrance).immutable(); footprint = Objects.requireNonNull(visualFootprint);
    }
    public void assignTerrainShaping(final TerrainShapingPlan plan)
    {
        if (terrainShaping != null) throw new IllegalStateException("Terrain shaping is already assigned");
        if (!Objects.requireNonNull(plan).pad().footprint().equals(footprint))
            throw new IllegalArgumentException("Terrain shaping footprint does not match building footprint");
        terrainShaping = plan;
    }
    public void ready(final long gameTime)
    {
        if (status == SettlementBuildingStatus.PLANNED)
        {
            status = SettlementBuildingStatus.READY;
            readyAt = Math.max(createdAt, gameTime);
            blocker = null;
        }
    }
    public void generating()
    {
        if (status == SettlementBuildingStatus.READY) status = SettlementBuildingStatus.GENERATING;
        if (status == SettlementBuildingStatus.GENERATING) blocker = null;
    }
    public void completed(final long gameTime)
    {
        status = SettlementBuildingStatus.COMPLETED;
        completedAt = Math.max(readyAt, gameTime);
        blocker = null;
    }
    public void blocked(final String reason)
    {
        status = SettlementBuildingStatus.BLOCKED;
        blocker = requireText(reason);
    }
    /** Records why automatic work is waiting without changing the lifecycle status. */
    public void awaiting(final String reason)
    {
        if (status == SettlementBuildingStatus.READY || status == SettlementBuildingStatus.GENERATING) blocker = requireText(reason);
    }
    /** Explicit operator retry: a blocked building becomes ready again; completed chunk markers are kept. */
    public boolean retryAfterBlock()
    {
        if (status != SettlementBuildingStatus.BLOCKED) return false;
        status = generatedChunks.isEmpty() ? SettlementBuildingStatus.READY : SettlementBuildingStatus.GENERATING;
        blocker = null;
        return true;
    }
    public void failed(final String reason)
    {
        status = SettlementBuildingStatus.FAILED;
        blocker = requireText(reason);
    }

    public Set<Long> footprintChunks()
    {
        final java.util.LinkedHashSet<Long> result = new java.util.LinkedHashSet<>();
        result.addAll(footprint.chunks());
        return Set.copyOf(result);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id); tag.putUUID("settlement", settlementId); tag.putString("type", type.name());
        tag.putLong("anchor", anchor.asLong()); tag.putInt("rotation", rotation); tag.putInt("sequence", sequence);
        tag.putInt("generationVersion", generationVersion); tag.putLong("createdAt", createdAt);
        tag.putString("origin", origin.name());
        tag.putString("structureSource", structureSource); tag.putString("structureId", structureId); tag.putString("styleFamily", styleFamily);
        tag.putBoolean("mirrored", mirrored); tag.putLong("entrance", entrance.asLong());
        tag.putInt("footprintMinX", footprint.minX()); tag.putInt("footprintMinZ", footprint.minZ());
        tag.putInt("footprintMaxX", footprint.maxX()); tag.putInt("footprintMaxZ", footprint.maxZ());
        if (terrainShaping != null) tag.put("terrainShaping", terrainShaping.save());
        tag.putLong("readyAt", readyAt); tag.putLong("completedAt", completedAt); tag.putString("status", status.name());
        if (blocker != null) tag.putString("blocker", blocker);
        final ListTag chunks = new ListTag();
        generatedChunks.forEach((chunk, version) -> { final CompoundTag value = new CompoundTag(); value.putLong("chunk", chunk); value.putInt("version", version); chunks.add(value); });
        tag.put("generatedChunks", chunks);
        return tag;
    }

    public static SettlementBuildingRecord load(final CompoundTag tag)
    {
        final SettlementBuildingRecord record = new SettlementBuildingRecord(tag.getUUID("id"), tag.getUUID("settlement"),
            SettlementBuildingType.valueOf(tag.getString("type")), BlockPos.of(tag.getLong("anchor")), tag.getInt("rotation"),
            tag.getInt("sequence"), Math.max(1, tag.getInt("generationVersion")), Math.max(0L, tag.getLong("createdAt")),
            SettlementBuildingStatus.valueOf(tag.getString("status")));
        record.origin = tag.contains("origin", Tag.TAG_STRING)
            ? SettlementBuildingOrigin.valueOf(tag.getString("origin")) : SettlementBuildingOrigin.LEGACY;
        record.readyAt = Math.max(0L, tag.getLong("readyAt"));
        if (tag.contains("structureSource", Tag.TAG_STRING))
        {
            record.structureSource = tag.getString("structureSource"); record.structureId = tag.getString("structureId");
            record.styleFamily = tag.getString("styleFamily"); record.mirrored = tag.getBoolean("mirrored");
            record.entrance = BlockPos.of(tag.getLong("entrance"));
            record.footprint = new StructureFootprint(tag.getInt("footprintMinX"), tag.getInt("footprintMinZ"),
                tag.getInt("footprintMaxX"), tag.getInt("footprintMaxZ"));
            if (tag.contains("terrainShaping", Tag.TAG_COMPOUND))
                record.terrainShaping = TerrainShapingPlan.load(tag.getCompound("terrainShaping"));
            if (!tag.contains("origin", Tag.TAG_STRING) && record.usesBlueprintVisual())
                record.origin = SettlementBuildingOrigin.GROWTH;
        }
        record.completedAt = Math.max(0L, tag.getLong("completedAt"));
        record.blocker = tag.contains("blocker", Tag.TAG_STRING) ? tag.getString("blocker") : null;
        tag.getList("generatedChunks", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            record.generatedChunks.put(entry.getLong("chunk"), entry.getInt("version"));
        });
        return record;
    }

    private static String requireText(final String value)
    {
        final String checked = Objects.requireNonNull(value).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException("Reason must not be blank");
        return checked;
    }
}
