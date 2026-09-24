package com.minecolonies.kingdoms.world.settlement.structure;

import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SettlementStructureCatalog
{
    private static final SettlementStructureCatalog EMPTY = new SettlementStructureCatalog(List.of());
    private final Map<String, SettlementStructureDescriptor> descriptors;

    public SettlementStructureCatalog(final Collection<? extends SettlementStructureSource> sources)
    {
        final Map<String, SettlementStructureDescriptor> collected = new LinkedHashMap<>();
        sources.stream().sorted(Comparator.comparing(SettlementStructureSource::sourceId)).forEach(source ->
            source.descriptors().stream().sorted(Comparator.comparing(SettlementStructureDescriptor::stableId))
                .forEach(descriptor -> collected.putIfAbsent(descriptor.stableId(), descriptor)));
        descriptors = Map.copyOf(collected);
    }

    public static SettlementStructureCatalog empty() { return EMPTY; }

    public Collection<SettlementStructureDescriptor> descriptors() { return List.copyOf(descriptors.values()); }
    public Optional<SettlementStructureDescriptor> get(final String stableId) { return Optional.ofNullable(descriptors.get(stableId)); }

    public Optional<String> selectStyle(final UUID settlementId)
    {
        final List<String> families = descriptors.values().stream().map(SettlementStructureDescriptor::styleFamily).distinct().sorted().toList();
        if (families.isEmpty()) return Optional.empty();
        return Optional.of(families.get(stableIndex("style:" + settlementId, families.size())));
    }

    public Optional<String> selectStyle(final UUID settlementId,
        final Collection<SettlementBuildingType> requiredTypes)
    {
        final java.util.Set<SettlementBuildingType> required = java.util.Set.copyOf(requiredTypes);
        final List<String> families = descriptors.values().stream().map(SettlementStructureDescriptor::styleFamily)
            .distinct().filter(style -> required.stream().allMatch(type -> descriptors.values().stream()
                .anyMatch(value -> value.styleFamily().equals(style) && value.buildingType() == type)))
            .sorted().toList();
        if (families.isEmpty()) return Optional.empty();
        return Optional.of(families.get(stableIndex("starter-style:" + settlementId, families.size())));
    }

    public Optional<SettlementStructureSelection> select(final UUID settlementId, final SettlementBuildingType type,
        final int sequence, final String styleFamily)
    {
        return candidates(settlementId, type, sequence, styleFamily, 1).stream().findFirst();
    }

    public List<SettlementStructureSelection> candidates(final UUID settlementId, final SettlementBuildingType type,
        final int sequence, final String styleFamily, final int limit)
    {
        if (limit < 1) return List.of();
        final List<SettlementStructureDescriptor> matching = descriptors.values().stream()
            .filter(value -> value.buildingType() == type && value.styleFamily().equals(styleFamily))
            .sorted(Comparator.comparing(SettlementStructureDescriptor::stableId)).toList();
        if (matching.isEmpty()) return List.of();
        final int descriptorOffset = stableIndex("structure:" + settlementId + ':' + type + ':' + sequence, matching.size());
        final List<SettlementStructureSelection> result = new ArrayList<>();
        for (int descriptorIndex = 0; descriptorIndex < matching.size() && result.size() < limit; descriptorIndex++)
        {
            final SettlementStructureDescriptor descriptor = matching.get(
                Math.floorMod(descriptorIndex + descriptorOffset, matching.size()));
            final List<StructureTransform> transforms = new ArrayList<>(descriptor.supportedTransforms());
            transforms.sort(Comparator.comparingInt(StructureTransform::rotation).thenComparing(StructureTransform::mirrored));
            final StructureTransform transform = transforms.get(stableIndex(
                "transform:" + settlementId + ':' + descriptor.stableId() + ':' + sequence, transforms.size()));
            result.add(new SettlementStructureSelection(descriptor, transform));
        }
        return List.copyOf(result);
    }

    private static int stableIndex(final String key, final int bound)
    {
        final UUID value = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return Math.floorMod((int) (value.getMostSignificantBits() ^ value.getLeastSignificantBits()), bound);
    }
}
