package com.minecolonies.kingdoms.world.settlement;

import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureCatalog;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureDescriptor;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureSource;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import com.minecolonies.kingdoms.world.settlement.structure.StructureTransform;
import com.minecolonies.kingdoms.world.settlement.structure.TerrainPlacementConstraints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Small, bounded synthetic fixtures shared by settlement planning tests. */
public final class SettlementFixtures
{
    public static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    private SettlementFixtures() {}

    public static SettlementRecord village(final String seed, final int y)
    {
        final UUID id = UUID.nameUUIDFromBytes(seed.getBytes());
        return new SettlementRecord(id, "Fixture", SettlementType.VILLAGE, OVERWORLD,
            new BlockPos(0, y, 0), 0, new BlockPos(0, y, SettlementType.VILLAGE.footprintRadius()), UUID.randomUUID(), 12,
            SettlementPhysicalState.PLANNED, new SettlementRegion(OVERWORLD, 0, 0), null, 0);
    }

    /** Catalog with every building type in each requested style; footprints are 9x9 with a south door. */
    public static SettlementStructureCatalog catalog(final String... styles)
    {
        final List<SettlementStructureDescriptor> descriptors = new ArrayList<>();
        for (final String style : styles)
            for (final SettlementBuildingType type : SettlementBuildingType.values())
                descriptors.add(descriptor(style, type, 9));
        return new SettlementStructureCatalog(List.of(new SettlementStructureSource()
        {
            @Override public String sourceId() { return "fixture"; }
            @Override public java.util.Collection<SettlementStructureDescriptor> descriptors() { return descriptors; }
        }));
    }

    public static SettlementStructureDescriptor descriptor(final String style, final SettlementBuildingType type, final int size)
    {
        final int half = size / 2;
        final Set<StructureTransform> transforms = Set.of(new StructureTransform(0, false),
            new StructureTransform(90, false), new StructureTransform(180, false), new StructureTransform(270, false));
        return new SettlementStructureDescriptor("fixture:" + style, type.name().toLowerCase() + ".blueprint", type,
            style, 1, size, 8, size, new StructureFootprint(-half, -half, size - half - 1, size - half - 1),
            new BlockPos(0, 0, size - half), Direction.SOUTH, transforms, TerrainPlacementConstraints.settlementDefault());
    }

    public static TerrainSample land(final int height) { return new TerrainSample(height, false, true); }
    public static TerrainSample water() { return new TerrainSample(62, true, true); }

    /** Wraps a sampler and counts calls, to prove searches stay bounded. */
    public static TerrainSampler counting(final TerrainSampler delegate, final AtomicInteger counter)
    {
        return (x, z) -> { counter.incrementAndGet(); return delegate.sample(x, z); };
    }
}
