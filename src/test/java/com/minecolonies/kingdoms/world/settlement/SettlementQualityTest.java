package com.minecolonies.kingdoms.world.settlement;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementQualityTest
{
    private static final TerrainSampler FLAT = (x, z) -> new TerrainSample(64, false, true);

    @Test
    void catalogSelectionAndTransformsAreDeterministic()
    {
        final SettlementStructureDescriptor spruce = descriptor("spruce", SettlementBuildingType.HOUSE);
        final SettlementStructureDescriptor stone = descriptor("stone", SettlementBuildingType.HOUSE);
        final SettlementStructureCatalog first = new SettlementStructureCatalog(
            List.of(source("z", List.of(stone)), source("a", List.of(spruce))));
        final SettlementStructureCatalog second = new SettlementStructureCatalog(
            List.of(source("a", List.of(spruce)), source("z", List.of(stone))));
        final UUID id = UUID.nameUUIDFromBytes("catalog-town".getBytes());
        assertEquals(first.selectStyle(id), second.selectStyle(id));
        final String style = first.selectStyle(id).orElseThrow();
        assertEquals(first.select(id, SettlementBuildingType.HOUSE, 4, style),
            second.select(id, SettlementBuildingType.HOUSE, 4, style));
        final StructureFootprint rotated = spruce.footprint(new BlockPos(10, 64, 20), new StructureTransform(90, true));
        assertEquals(7, rotated.width());
        assertEquals(5, rotated.depth());
    }

    @Test
    void fullTerrainEnvelopeRejectsInteriorSpikeAndWater()
    {
        final StructureFootprint footprint = new StructureFootprint(0, 0, 8, 8);
        final TerrainPlacementConstraints limits = TerrainPlacementConstraints.settlementDefault();
        final TerrainEnvelopeEvaluator evaluator = new TerrainEnvelopeEvaluator();
        assertTrue(evaluator.evaluate(footprint, new BlockPos(4, 64, 9), Direction.SOUTH, limits, FLAT).valid());
        final TerrainSampler spike = (x, z) -> new TerrainSample(x == 3 && z == 4 ? 80 : 64, false, true);
        assertFalse(evaluator.evaluate(footprint, new BlockPos(4, 64, 9), Direction.SOUTH, limits, spike).valid());
        final TerrainSampler water = (x, z) -> new TerrainSample(64, x == 2 && z == 2, true);
        assertEquals("water in terrain envelope",
            evaluator.evaluate(footprint, new BlockPos(4, 64, 9), Direction.SOUTH, limits, water).rejection());
    }

    @Test
    void plannedLotHasPersistedEntranceConnectionAndRoundTrips()
    {
        final UUID id = UUID.nameUUIDFromBytes("layout-town".getBytes());
        final SettlementRecord settlement = new SettlementRecord(id, "Layout", SettlementType.VILLAGE,
            ResourceLocation.withDefaultNamespace("overworld"), new BlockPos(0, 64, 0), 0,
            new BlockPos(0, 64, 20), UUID.randomUUID(), 12, SettlementPhysicalState.PLANNED,
            new SettlementRegion(ResourceLocation.withDefaultNamespace("overworld"), 0, 0), null, 0);
        final SettlementLayoutPlanner planner = new SettlementLayoutPlanner();
        final SettlementLayoutPlan layout = planner.createPlan(settlement, "spruce");
        final var selected = new SettlementStructureSelection(descriptor("spruce", SettlementBuildingType.HOUSE),
            new StructureTransform(0, false));
        final UUID building = UUID.nameUUIDFromBytes("layout-building".getBytes());
        final var placement = planner.plan(settlement, building, selected, layout, 160, FLAT, new RoadNetwork()).orElseThrow();
        assertEquals(placement.lot().entrance(), placement.streetExtension().points().getFirst());
        assertFalse(placement.lot().footprint().contains(placement.lot().entrance().getX(), placement.lot().entrance().getZ()));
        layout.streets().put(placement.streetExtension()); layout.putLot(placement.lot());
        final SettlementLayoutPlan loaded = SettlementLayoutPlan.load(layout.save());
        assertEquals(layout.styleFamily(), loaded.styleFamily());
        assertEquals(1, loaded.lots().size());
        assertEquals(layout.streets().segments().size(), loaded.streets().segments().size());
    }

    private static SettlementStructureDescriptor descriptor(final String style, final SettlementBuildingType type)
    {
        final Set<StructureTransform> transforms = Set.of(new StructureTransform(0, false),
            new StructureTransform(90, false), new StructureTransform(180, false), new StructureTransform(270, false));
        return new SettlementStructureDescriptor("test", style + "/house1.blueprint", type, style, 1,
            5, 6, 7, new StructureFootprint(-2, -3, 2, 3), new BlockPos(0, 0, 4),
            Direction.SOUTH, transforms, TerrainPlacementConstraints.settlementDefault());
    }

    private static SettlementStructureSource source(final String id, final List<SettlementStructureDescriptor> values)
    {
        return new SettlementStructureSource()
        {
            @Override public String sourceId() { return id; }
            @Override public java.util.Collection<SettlementStructureDescriptor> descriptors() { return values; }
        };
    }
}
