package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingOrigin;
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
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementDistrictPlannerTest
{
    @Test
    void starterDistrictIsDeterministicCoherentAndUsesRealDescriptors()
    {
        final SettlementRecord settlement = settlement();
        final SettlementStructureCatalog catalog = catalog();
        final int[] samples = {0};
        final var terrain = (com.minecolonies.kingdoms.world.settlement.TerrainSampler)
            (x, z) -> { samples[0]++; return new TerrainSample(64, false, true); };
        final SettlementDistrictPlanner planner = new SettlementDistrictPlanner();
        final var first = planner.plan(settlement, catalog, terrain, new RoadNetwork(), 10).plan();
        final var second = planner.plan(settlement, catalog, terrain, new RoadNetwork(), 10).plan();
        assertNotNull(first);
        assertEquals(4, first.buildings().size());
        assertEquals(first.layout().save(), second.layout().save());
        assertEquals(first.buildings().stream().map(value -> value.save().toString()).toList(),
            second.buildings().stream().map(value -> value.save().toString()).toList());
        assertTrue(first.buildings().stream().allMatch(value -> value.origin() == SettlementBuildingOrigin.STARTER));
        assertTrue(first.buildings().stream().allMatch(value -> value.usesBlueprintVisual()));
        assertTrue(first.buildings().stream().allMatch(value -> value.styleFamily().equals("test-style")));
        assertTrue(first.buildings().stream().allMatch(value -> value.terrainShaping() != null));
        assertTrue(samples[0] < 30_000, () -> "Unbounded/redundant terrain sampling: " + samples[0]);
        for (int left = 0; left < first.buildings().size(); left++)
            for (int right = left + 1; right < first.buildings().size(); right++)
                assertFalse(first.buildings().get(left).footprint().intersects(first.buildings().get(right).footprint()));
    }

    private static SettlementRecord settlement()
    {
        final ResourceLocation overworld = ResourceLocation.withDefaultNamespace("overworld");
        final UUID id = UUID.nameUUIDFromBytes("starter-district".getBytes());
        return new SettlementRecord(id, "Starter", SettlementType.VILLAGE, overworld,
            new BlockPos(0, 64, 0), 0, new BlockPos(0, 64, 24), UUID.randomUUID(), 12,
            SettlementPhysicalState.PLANNED, new SettlementRegion(overworld, 0, 0), null, 0);
    }

    private static SettlementStructureCatalog catalog()
    {
        final List<SettlementStructureDescriptor> descriptors = Arrays.stream(SettlementBuildingType.values())
            .map(SettlementDistrictPlannerTest::descriptor).toList();
        return new SettlementStructureCatalog(List.of(new SettlementStructureSource()
        {
            @Override public String sourceId() { return "test"; }
            @Override public java.util.Collection<SettlementStructureDescriptor> descriptors() { return descriptors; }
        }));
    }

    private static SettlementStructureDescriptor descriptor(final SettlementBuildingType type)
    {
        final Set<StructureTransform> transforms = Set.of(new StructureTransform(0, false),
            new StructureTransform(90, false), new StructureTransform(180, false), new StructureTransform(270, false));
        return new SettlementStructureDescriptor("test", type.name().toLowerCase() + ".blueprint", type,
            "test-style", 1, 9, 8, 9, new StructureFootprint(-4, -4, 4, 4),
            new BlockPos(0, 0, 5), Direction.SOUTH, transforms, TerrainPlacementConstraints.settlementDefault());
    }
}
