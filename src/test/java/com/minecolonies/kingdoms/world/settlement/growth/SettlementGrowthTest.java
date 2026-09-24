package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementGrowthTest
{
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final TerrainSampler FLAT = (x, z) -> new TerrainSample(64, false, true);

    @Test
    void decisionIsDeterministicAndIndependentOfBuildingInsertionOrder()
    {
        final SettlementRecord settlement = settlement(SettlementType.TOWN);
        final NPCColonyData colony = colony(settlement);
        colony.replaceNeeds(List.of(new ColonyNeed(NeedType.HOUSING_SHORTAGE, NeedSeverity.HIGH, 5, 20, 0)));
        final SettlementBuildingRecord farm = building(settlement, SettlementBuildingType.FARM, 0, 80, 20);
        final SettlementBuildingRecord quarry = building(settlement, SettlementBuildingType.QUARRY, 1, -80, 20);
        final List<SettlementBuildingRecord> forward = new ArrayList<>(List.of(farm, quarry));
        final List<SettlementBuildingRecord> reverse = new ArrayList<>(forward);
        Collections.reverse(reverse);
        final SettlementGrowthEvaluator evaluator = new SettlementGrowthEvaluator();
        assertEquals(SettlementBuildingType.HOUSE, evaluator.decide(settlement, colony, forward).buildingType());
        assertEquals(evaluator.decide(settlement, colony, forward), evaluator.decide(settlement, colony, reverse));
    }

    @Test
    void persistentFoodShortageDoesNotProduceAnEndlessRowOfFarms()
    {
        // Client acceptance: a castle grew nine farms in a row because its structural food deficit never closed.
        final SettlementRecord settlement = settlement(SettlementType.CASTLE);
        final NPCColonyData colony = colony(settlement);
        colony.replaceNeeds(List.of(new ColonyNeed(NeedType.FOOD_SHORTAGE, NeedSeverity.CRITICAL, 0, 100, 0)));
        final SettlementGrowthEvaluator evaluator = new SettlementGrowthEvaluator();
        final List<SettlementBuildingRecord> buildings = new ArrayList<>();
        for (int index = 0; index < 12; index++)
        {
            final GrowthDecision decision = evaluator.decide(settlement, colony, buildings);
            if (!decision.plansBuilding()) break;
            buildings.add(building(settlement, decision.buildingType(), index, 60 + index * 20, 20));
        }
        final long farms = buildings.stream().filter(value -> value.type() == SettlementBuildingType.FARM).count();
        assertTrue(farms >= 2, "the pressing need is still answered first");
        assertTrue(farms <= 2 + buildings.size() / 4, () -> "farms=" + farms + " of " + buildings.size());
        assertTrue(buildings.stream().map(SettlementBuildingRecord::type).distinct().count() >= 4,
            () -> "growth should diversify: " + buildings.stream().map(SettlementBuildingRecord::type).toList());
    }

    @Test
    void rolesWithoutABlueprintInTheStyleAreSkippedInsteadOfBlockingGrowth()
    {
        // Client acceptance: Medieval Birch has no smithy blueprint and growth stopped on "no compatible smithy".
        final SettlementRecord settlement = settlement(SettlementType.CASTLE);
        final NPCColonyData colony = colony(settlement);
        colony.replaceNeeds(List.of(new ColonyNeed(NeedType.IRON_SHORTAGE, NeedSeverity.CRITICAL, 0, 100, 0)));
        final SettlementGrowthEvaluator evaluator = new SettlementGrowthEvaluator();
        final List<SettlementBuildingRecord> buildings = new ArrayList<>();
        for (int index = 0; index < 10; index++)
        {
            final GrowthDecision decision = evaluator.decide(settlement, colony, buildings,
                type -> type != SettlementBuildingType.SMITHY);
            assertTrue(decision.plansBuilding(), decision::reason);
            assertNotEquals(SettlementBuildingType.SMITHY, decision.buildingType());
            buildings.add(building(settlement, decision.buildingType(), index, 60 + index * 20, 20));
        }
    }

    @Test
    void everyStrategicNeedMapsToExpectedBuildingCategory()
    {
        final SettlementRecord settlement = settlement(SettlementType.TOWN);
        final NPCColonyData colony = colony(settlement);
        final SettlementGrowthEvaluator evaluator = new SettlementGrowthEvaluator();
        final var expected = java.util.Map.of(
            NeedType.HOUSING_SHORTAGE, SettlementBuildingType.HOUSE,
            NeedType.STORAGE_SHORTAGE, SettlementBuildingType.STOREHOUSE,
            NeedType.FOOD_SHORTAGE, SettlementBuildingType.FARM,
            NeedType.WOOD_SHORTAGE, SettlementBuildingType.LUMBER_YARD,
            NeedType.STONE_SHORTAGE, SettlementBuildingType.QUARRY,
            NeedType.IRON_SHORTAGE, SettlementBuildingType.SMITHY);
        expected.forEach((need, building) -> {
            colony.replaceNeeds(List.of(new ColonyNeed(need, NeedSeverity.CRITICAL, 0, 100, 0)));
            assertEquals(building, evaluator.decide(settlement, colony, List.of()).buildingType());
        });
    }

    @Test
    void stableIdAndPlotAreDeterministic()
    {
        final SettlementRecord settlement = settlement(SettlementType.VILLAGE);
        final SettlementPlotPlanner planner = new SettlementPlotPlanner();
        final SettlementBuildingRecord first = planner.plan(settlement, SettlementBuildingType.HOUSE, 3, 160,
            FLAT, List.of(), new RoadNetwork(), 100).orElseThrow();
        final SettlementBuildingRecord second = planner.plan(settlement, SettlementBuildingType.HOUSE, 3, 160,
            FLAT, List.of(), new RoadNetwork(), 100).orElseThrow();
        assertEquals(first.id(), second.id());
        assertEquals(first.anchor(), second.anchor());
        assertEquals(SettlementPlotPlanner.stableBuildingId(settlement.id(), 3, SettlementBuildingType.HOUSE, 1), first.id());
    }

    @Test
    void plotsAvoidStarterCenterGateExistingBuildingsAndGlobalRoads()
    {
        final SettlementRecord settlement = settlement(SettlementType.TOWN);
        final RoadNetwork roads = new RoadNetwork();
        roads.put(new RoadRecord(UUID.nameUUIDFromBytes("road".getBytes()), settlement.id(), UUID.randomUUID(), OVERWORLD,
            RoadType.ROYAL, List.of(settlement.gate(), new BlockPos(200, 64, 0)), RoadStatus.PLANNED, 1));
        final SettlementBuildingRecord existing = building(settlement, SettlementBuildingType.FARM, 0, 0, 90);
        final SettlementBuildingRecord planned = new SettlementPlotPlanner().plan(settlement, SettlementBuildingType.HOUSE,
            4, 180, FLAT, List.of(existing), roads, 100).orElseThrow();
        final int radius = Math.max(planned.type().halfWidth(), planned.type().halfDepth());
        assertTrue(Math.sqrt(planned.anchor().distSqr(settlement.anchor())) > settlement.type().footprintRadius() + radius);
        assertTrue(Math.sqrt(planned.anchor().distSqr(settlement.gate())) > radius + 10);
        assertTrue(Math.sqrt(planned.anchor().distSqr(existing.anchor())) > radius + Math.max(existing.type().halfWidth(), existing.type().halfDepth()));
        for (int index = 1; index < roads.roads().iterator().next().polyline().size(); index++)
            assertTrue(SettlementPlotPlanner.distanceToSegment(planned.anchor(), roads.roads().iterator().next().polyline().get(index - 1),
                roads.roads().iterator().next().polyline().get(index)) > radius + RoadType.ROYAL.width());
    }

    @Test
    void registryAndGenerationMarkersAreIdempotentAcrossRoundTrip()
    {
        final SettlementRecord settlement = settlement(SettlementType.VILLAGE);
        final SettlementBuildingRecord building = building(settlement, SettlementBuildingType.HOUSE, 0, 70, 70);
        building.ready(10); building.generating(); building.markGenerated(42L); building.markGenerated(42L);
        final GrowthRegistry registry = new GrowthRegistry();
        assertTrue(registry.put(building));
        assertFalse(registry.put(building));
        final GrowthRegistry loaded = GrowthRegistry.load(registry.save());
        final SettlementBuildingRecord restored = loaded.building(building.id()).orElseThrow();
        assertFalse(restored.needsGeneration(42L));
        assertEquals(List.of(building.id()), loaded.inChunk(new ChunkPos(4, 4)).stream().map(SettlementBuildingRecord::id).toList());
        assertFalse(loaded.put(building));
        assertEquals(1, loaded.buildings().size());
    }

    @Test
    void stageTransitionsAreDeterministicAndIdempotent()
    {
        final SettlementRecord settlement = settlement(SettlementType.VILLAGE);
        final NPCColonyData colony = colony(settlement);
        colony.updatePopulation(settlement.type().minimumPopulation() + 12, 12, 0);
        final List<SettlementBuildingRecord> buildings = new ArrayList<>();
        for (int index = 0; index < 7; index++)
        {
            final SettlementBuildingRecord building = building(settlement, SettlementBuildingType.values()[index], index, 70 + index * 15, 70);
            building.ready(10); buildings.add(building);
        }
        assertEquals(SettlementGrowthStage.PROSPEROUS, SettlementGrowthManager.stageFor(settlement, colony, buildings));
        assertEquals(SettlementGrowthStage.PROSPEROUS, SettlementGrowthManager.stageFor(settlement, colony, buildings));
    }

    @Test
    void populationGrowthObeysFoodHousingAndHardCaps()
    {
        final SettlementRecord settlement = settlement(SettlementType.TOWN);
        final NPCColonyData colony = colony(settlement);
        colony.updatePopulation(20, 12, 2); colony.updateCapacities(30, 1000);
        final EconomyManager economy = new EconomyManager();
        economy.setDesiredReserve(colony, EconomicResource.FOOD, 100);
        economy.setStockpile(colony, EconomicResource.FOOD, 200);
        economy.setProduction(colony, EconomicResource.FOOD, 50);
        economy.setConsumption(colony, EconomicResource.FOOD, 10);
        colony.replaceNeeds(List.of());
        assertTrue(SettlementGrowthManager.getInstance().tryGrowPopulation(colony, SettlementGrowthStage.GROWING, settlement, 100));
        assertEquals(21, colony.population());
        colony.updatePopulation(21, colony.workers(), colony.soldiers());
        assertFalse(SettlementGrowthManager.getInstance().tryGrowPopulation(colony, SettlementGrowthStage.GROWING, settlement, 21));
    }

    @Test
    void criticalFoodShortageBlocksPopulationGrowth()
    {
        final SettlementRecord settlement = settlement(SettlementType.VILLAGE);
        final NPCColonyData colony = colony(settlement);
        colony.updatePopulation(10, 6, 1); colony.updateCapacities(30, 1000);
        final EconomyManager economy = new EconomyManager();
        economy.setStockpile(colony, EconomicResource.FOOD, 200);
        economy.setDesiredReserve(colony, EconomicResource.FOOD, 100);
        economy.setProduction(colony, EconomicResource.FOOD, 50);
        colony.replaceNeeds(List.of(new ColonyNeed(NeedType.FOOD_SHORTAGE, NeedSeverity.CRITICAL, 0, 100, 0)));
        assertFalse(SettlementGrowthManager.getInstance().tryGrowPopulation(colony, SettlementGrowthStage.STARTER, settlement, 100));
        assertEquals(10, colony.population());
    }

    @Test
    void derivedBuildingEffectsDoNotDoubleApply()
    {
        final SettlementRecord settlement = settlement(SettlementType.VILLAGE);
        final NPCColonyData colony = colony(settlement);
        final EconomyManager economy = new EconomyManager();
        economy.setProduction(colony, EconomicResource.FOOD, 10.0D);
        final SettlementGrowthState state = new SettlementGrowthState(settlement.id());
        final SettlementBuildingRecord house = building(settlement, SettlementBuildingType.HOUSE, 0, 70, 70);
        final SettlementBuildingRecord farm = building(settlement, SettlementBuildingType.FARM, 1, 90, 70);
        house.ready(10); farm.ready(10);
        final SettlementGrowthManager manager = SettlementGrowthManager.getInstance();
        manager.applyDerivedEffects(colony, state, List.of(house, farm));
        manager.applyDerivedEffects(colony, state, List.of(farm, house));
        assertEquals(108, colony.housingCapacity());
        assertEquals(50.0D, colony.economy().resource(EconomicResource.FOOD).flow().productionPerDay());
        assertEquals(8, state.appliedHousing());
        assertEquals(40.0D, state.appliedProduction(EconomicResource.FOOD));
    }

    private static SettlementRecord settlement(final SettlementType type)
    {
        final UUID id = UUID.nameUUIDFromBytes(("growth-" + type).getBytes());
        return new SettlementRecord(id, "Growth " + type, type, OVERWORLD, new BlockPos(0, 64, 0), 0,
            new BlockPos(type.footprintRadius(), 64, 0), UUID.nameUUIDFromBytes(("faction-" + type).getBytes()),
            type.minimumPopulation(), SettlementPhysicalState.PLANNED, new SettlementRegion(OVERWORLD, 0, 0), null, 0);
    }

    private static NPCColonyData colony(final SettlementRecord settlement)
    {
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(settlement.id(), OVERWORLD, settlement.anchor(),
            settlement.name(), settlement.factionId(), 0);
        colony.updatePopulation(settlement.initialPopulation(), settlement.initialPopulation() * 2 / 3, 0);
        colony.updateCapacities(100, 1000);
        return colony;
    }

    private static SettlementBuildingRecord building(final SettlementRecord settlement, final SettlementBuildingType type,
        final int sequence, final int x, final int z)
    {
        return new SettlementBuildingRecord(SettlementPlotPlanner.stableBuildingId(settlement.id(), sequence, type, 1),
            settlement.id(), type, new BlockPos(x, 64, z), 0, sequence, 1, 0, SettlementBuildingStatus.PLANNED);
    }
}
