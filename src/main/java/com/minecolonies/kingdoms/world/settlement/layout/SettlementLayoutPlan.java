package com.minecolonies.kingdoms.world.settlement.layout;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SettlementLayoutPlan
{
    private final UUID settlementId;
    private String styleFamily;
    private String templateId = "";
    private final SettlementStreetNetwork streets;
    private final Map<UUID, SettlementLot> lots = new LinkedHashMap<>();
    /** Persisted per-segment chunk markers; a segment slice is written at most once per chunk. */
    private final Map<UUID, Set<Long>> streetChunks = new LinkedHashMap<>();
    private LayoutPlanningDiagnostics diagnostics = LayoutPlanningDiagnostics.empty();
    /** Diagnostics of the most recent search only; rebuildable and intentionally not persisted. */
    private LayoutPlanningDiagnostics lastSearch = LayoutPlanningDiagnostics.empty();

    public SettlementLayoutPlan(final UUID settlementId, final String styleFamily, final SettlementStreetNetwork streets)
    {
        this.settlementId = settlementId;
        this.styleFamily = styleFamily == null ? "" : styleFamily;
        this.streets = streets;
    }
    public UUID settlementId() { return settlementId; }
    public String styleFamily() { return styleFamily; }
    public void styleFamily(final String value) { if (styleFamily.isEmpty()) styleFamily = value; }
    public String templateId() { return templateId; }
    public void templateId(final String value) { if (templateId.isEmpty()) templateId = value == null ? "" : value.trim(); }
    public SettlementStreetNetwork streets() { return streets; }
    public Collection<SettlementLot> lots() { return List.copyOf(lots.values()); }
    public Optional<SettlementLot> lot(final UUID buildingId) { return Optional.ofNullable(lots.get(buildingId)); }
    public boolean putLot(final SettlementLot lot) { return lots.putIfAbsent(lot.buildingId(), lot) == null; }
    public LayoutPlanningDiagnostics diagnostics() { return diagnostics; }
    public LayoutPlanningDiagnostics lastSearch() { return lastSearch; }
    public void recordDiagnostics(final LayoutPlanningDiagnostics value)
    {
        diagnostics = diagnostics.merge(value);
        lastSearch = value;
    }
    public boolean streetChunkGenerated(final UUID segmentId, final long chunk)
    {
        return streetChunks.getOrDefault(segmentId, Set.of()).contains(chunk);
    }
    public void markStreetChunk(final UUID segmentId, final long chunk)
    {
        streetChunks.computeIfAbsent(segmentId, ignored -> new LinkedHashSet<>()).add(chunk);
    }
    public int generatedStreetChunkMarkers() { return streetChunks.values().stream().mapToInt(Set::size).sum(); }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag(); tag.putUUID("settlement", settlementId); tag.putString("style", styleFamily);
        tag.putString("template", templateId);
        tag.put("diagnostics", diagnostics.save());
        tag.put("streets", streets.save()); final ListTag list = new ListTag(); lots.values().forEach(lot -> list.add(lot.save())); tag.put("lots", list);
        final ListTag markers = new ListTag();
        streetChunks.forEach((segment, chunks) -> {
            final CompoundTag entry = new CompoundTag(); entry.putUUID("segment", segment);
            entry.put("chunks", new LongArrayTag(chunks.stream().mapToLong(Long::longValue).toArray())); markers.add(entry);
        });
        tag.put("streetChunks", markers);
        return tag;
    }
    public static SettlementLayoutPlan load(final CompoundTag tag)
    {
        final SettlementLayoutPlan result = new SettlementLayoutPlan(tag.getUUID("settlement"), tag.getString("style"),
            SettlementStreetNetwork.load(tag.getCompound("streets")));
        result.templateId = tag.getString("template");
        if (tag.contains("diagnostics", Tag.TAG_COMPOUND))
            result.diagnostics = LayoutPlanningDiagnostics.load(tag.getCompound("diagnostics"));
        tag.getList("lots", Tag.TAG_COMPOUND).forEach(value -> result.putLot(SettlementLot.load((CompoundTag) value)));
        tag.getList("streetChunks", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            for (final long chunk : entry.getLongArray("chunks")) result.markStreetChunk(entry.getUUID("segment"), chunk);
        });
        return result;
    }
}
