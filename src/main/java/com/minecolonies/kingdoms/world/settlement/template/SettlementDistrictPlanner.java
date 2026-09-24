package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingOrigin;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementPlotPlanner;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureCatalog;

import java.util.ArrayList;
import java.util.List;

public final class SettlementDistrictPlanner
{
    public static final int STARTER_GENERATION_VERSION = 3;
    /** Bumped whenever starter planning changes; a starter blocked by an older planner is re-planned once. */
    public static final int STARTER_PLANNER_VERSION = 6;
    private final SettlementTemplateCatalog templates = new SettlementTemplateCatalog();
    private final SettlementLayoutPlanner layouts;

    public SettlementDistrictPlanner()
    {
        this(new SettlementLayoutPlanner());
    }

    public SettlementDistrictPlanner(final SettlementLayoutPlanner layouts)
    {
        this.layouts = layouts;
    }

    public SettlementDistrictPlanningResult plan(final SettlementRecord settlement,
        final SettlementStructureCatalog structures, final TerrainSampler terrain,
        final RoadNetwork globalRoads, final long gameTime)
    {
        final java.util.Map<Long, com.minecolonies.kingdoms.world.settlement.TerrainSample> samples = new java.util.HashMap<>();
        final TerrainSampler cachedTerrain = (x, z) -> samples.computeIfAbsent(
            ((long) x << 32) ^ (z & 0xffffffffL), ignored -> terrain.sample(x, z));
        final var site = settlement.siteAnalysis().orElse(null);
        if (site != null && !site.accepted())
            return SettlementDistrictPlanningResult.rejected("SITE_REJECTED:" + site.rejection().name());
        final SettlementTemplate template = templates.select(settlement.type(), settlement.id());
        final String style = structures.selectStyle(settlement.id(), template.slots().stream()
            .filter(SettlementTemplateSlot::required).map(SettlementTemplateSlot::buildingType).toList()).orElse(null);
        if (style == null) return SettlementDistrictPlanningResult.rejected("NO_COHERENT_STRUCTURE_STYLE");
        if (!cachedTerrain.sample(settlement.anchor().getX(), settlement.anchor().getZ()).known()
            || !cachedTerrain.sample(settlement.gate().getX(), settlement.gate().getZ()).known())
            return SettlementDistrictPlanningResult.rejected("UNLOADED_TERRAIN:ANCHOR_OR_GATE");
        final SettlementLayoutPlan layout = layouts.createPlan(settlement, style, cachedTerrain).orElse(null);
        if (layout == null) return SettlementDistrictPlanningResult.rejected("STREET_UNREACHABLE:GATE_TO_PLAZA");
        layout.templateId(template.id());
        final List<SettlementBuildingRecord> buildings = new ArrayList<>();
        int terrainWork = 0;
        int sequence = 0;
        for (final SettlementTemplateSlot slot : template.slots())
        {
            final var selections = structures.candidates(settlement.id(), slot.buildingType(), sequence, style, 6);
            if (selections.isEmpty())
            {
                if (slot.required()) return SettlementDistrictPlanningResult.rejected(
                    "NO_STRUCTURE:" + slot.buildingType().name());
                continue;
            }
            final var buildingId = SettlementPlotPlanner.stableBuildingId(settlement.id(), sequence,
                slot.buildingType(), STARTER_GENERATION_VERSION);
            final var placement = layouts.planStarter(settlement, buildingId, selections, layout,
                slot.minimumDistance(), slot.maximumDistance(), cachedTerrain, globalRoads);
            if (placement.isEmpty())
            {
                if (slot.required()) return SettlementDistrictPlanningResult.rejected(
                    "NO_VALID_STARTER_PAD:" + slot.buildingType().name() + "[" + layout.lastSearch().summary() + "]",
                    layout.diagnostics());
                continue;
            }
            final var accepted = placement.orElseThrow();
            final var selected = accepted.structure();
            final SettlementBuildingRecord building = new SettlementBuildingRecord(buildingId, settlement.id(),
                slot.buildingType(), accepted.anchor(), selected.transform().rotation(), sequence,
                STARTER_GENERATION_VERSION, gameTime, SettlementBuildingStatus.READY,
                SettlementBuildingOrigin.STARTER);
            building.assignVisual(selected.descriptor().sourceId(), selected.descriptor().structureId(),
                selected.descriptor().styleFamily(), selected.transform().mirrored(),
                accepted.lot().entrance(), accepted.lot().footprint());
            building.assignTerrainShaping(accepted.terrain());
            buildings.add(building);
            terrainWork += accepted.terrain().pad().terrainWorkVolume();
            layout.streets().put(accepted.streetExtension());
            layout.putLot(accepted.lot());
            sequence++;
        }
        if (buildings.size() < 2) return SettlementDistrictPlanningResult.rejected("INSUFFICIENT_STARTER_BUILDINGS",
            layout.diagnostics());
        return SettlementDistrictPlanningResult.accepted(new SettlementDistrictPlan(template.id(), style,
            layout, buildings, terrainWork));
    }
}
