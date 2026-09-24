package com.minecolonies.kingdoms.world.settlement.layout;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bounded planning counters. Positions are coarse candidate centers, pads are full-footprint terrain evaluations,
 * and accepted counts pads that also received a valid local street extension.
 */
public record LayoutPlanningDiagnostics(int structuresConsidered, int rotationsConsidered,
    int candidatePadsChecked, int acceptedCandidates, int selectedTerrainWork,
    int streetExtensionsCreated, long lastPlanningNanos, Map<LayoutRejectionReason, Integer> rejections,
    int positionsChecked)
{
    public LayoutPlanningDiagnostics
    {
        rejections = Map.copyOf(rejections);
    }

    public LayoutPlanningDiagnostics(final int structuresConsidered, final int rotationsConsidered,
        final int candidatePadsChecked, final int acceptedCandidates, final int selectedTerrainWork,
        final int streetExtensionsCreated, final long lastPlanningNanos, final Map<LayoutRejectionReason, Integer> rejections)
    {
        this(structuresConsidered, rotationsConsidered, candidatePadsChecked, acceptedCandidates, selectedTerrainWork,
            streetExtensionsCreated, lastPlanningNanos, rejections, 0);
    }

    public static LayoutPlanningDiagnostics empty()
    {
        return new LayoutPlanningDiagnostics(0, 0, 0, 0, 0, 0, 0L, Map.of(), 0);
    }

    public LayoutPlanningDiagnostics merge(final LayoutPlanningDiagnostics other)
    {
        final EnumMap<LayoutRejectionReason, Integer> merged = new EnumMap<>(LayoutRejectionReason.class);
        merged.putAll(rejections);
        other.rejections.forEach((reason, count) -> merged.merge(reason, count, Integer::sum));
        return new LayoutPlanningDiagnostics(structuresConsidered + other.structuresConsidered,
            rotationsConsidered + other.rotationsConsidered, candidatePadsChecked + other.candidatePadsChecked,
            acceptedCandidates + other.acceptedCandidates, selectedTerrainWork + other.selectedTerrainWork,
            streetExtensionsCreated + other.streetExtensionsCreated, other.lastPlanningNanos, merged,
            positionsChecked + other.positionsChecked);
    }

    /** Compact single-line explanation suitable for blockers and chat output. */
    public String summary()
    {
        final String rejected = rejections.entrySet().stream()
            .sorted(Map.Entry.<LayoutRejectionReason, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> entry.getKey().name() + '=' + entry.getValue()).collect(Collectors.joining(","));
        return String.format(Locale.ROOT, "positions=%d structures=%d rotations=%d pads=%d accepted=%d terrainWork=%d ms=%.1f rejected={%s}",
            positionsChecked, structuresConsidered, rotationsConsidered, candidatePadsChecked, acceptedCandidates,
            selectedTerrainWork, lastPlanningNanos / 1_000_000.0D, rejected);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("structures", structuresConsidered); tag.putInt("rotations", rotationsConsidered);
        tag.putInt("pads", candidatePadsChecked); tag.putInt("accepted", acceptedCandidates);
        tag.putInt("terrainWork", selectedTerrainWork); tag.putInt("streetExtensions", streetExtensionsCreated);
        tag.putInt("positions", positionsChecked);
        final ListTag list = new ListTag();
        rejections.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            final CompoundTag value = new CompoundTag(); value.putString("reason", entry.getKey().name());
            value.putInt("count", entry.getValue()); list.add(value);
        });
        tag.put("rejections", list);
        return tag;
    }

    public static LayoutPlanningDiagnostics load(final CompoundTag tag)
    {
        final EnumMap<LayoutRejectionReason, Integer> rejections = new EnumMap<>(LayoutRejectionReason.class);
        tag.getList("rejections", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            try { rejections.put(LayoutRejectionReason.valueOf(entry.getString("reason")), entry.getInt("count")); }
            catch (IllegalArgumentException ignored) { /* diagnostics only; unknown historical reason */ }
        });
        return new LayoutPlanningDiagnostics(tag.getInt("structures"), tag.getInt("rotations"), tag.getInt("pads"),
            tag.getInt("accepted"), tag.getInt("terrainWork"), tag.getInt("streetExtensions"), 0L, rejections,
            tag.getInt("positions"));
    }

    static final class Builder
    {
        int structures;
        int rotations;
        int pads;
        int accepted;
        int terrainWork;
        int streets;
        int positions;
        final EnumMap<LayoutRejectionReason, Integer> rejections = new EnumMap<>(LayoutRejectionReason.class);
        void reject(final LayoutRejectionReason reason) { rejections.merge(reason, 1, Integer::sum); }
        LayoutPlanningDiagnostics build(final long nanos)
        {
            return new LayoutPlanningDiagnostics(structures, rotations, pads, accepted, terrainWork,
                streets, nanos, rejections, positions);
        }
    }
}
