package com.minecolonies.kingdoms.command;

import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.integration.structurize.SettlementStructureService;
import com.minecolonies.kingdoms.world.WorldGenerationStats;
import com.minecolonies.kingdoms.world.WorldSettlementManager;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadRoutePlanner;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.growth.GrowthEvaluationResult;
import com.minecolonies.kingdoms.world.settlement.growth.GrowthStats;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementGrowthManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class WorldCommands
{
    private WorldCommands() {}

    static ArgumentBuilder<CommandSourceStack, ?> settlementCommand()
    {
        return Commands.literal("settlement")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("list").executes(context -> listSettlements(context.getSource())))
            .then(Commands.literal("info").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> settlementInfo(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("locate").executes(context -> locate(context.getSource())))
            .then(Commands.literal("region").executes(context -> region(context.getSource())))
            .then(Commands.literal("plan-region")
                .executes(context -> planRegion(context.getSource(), 0))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                    .executes(context -> planRegion(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
            .then(Commands.literal("materialize").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> materialize(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("site")
                .executes(context -> probeSite(context.getSource(), com.minecolonies.kingdoms.world.settlement.SettlementType.VILLAGE))
                .then(Commands.argument("type", StringArgumentType.word())
                    .executes(context -> probeSite(context.getSource(), siteType(StringArgumentType.getString(context, "type"))))))
            .then(Commands.literal("column").executes(context -> column(context.getSource())))
            .then(Commands.literal("stats").executes(context -> stats(context.getSource())));
    }

    /** Diagnostic: planning heights and the block stack at the command position (never loads chunks). */
    private static int column(final CommandSourceStack source)
    {
        final BlockPos here = BlockPos.containing(source.getPosition());
        final var level = source.getLevel();
        final var loaded = com.minecolonies.kingdoms.world.WorldgenTerrainSampler.loadedOnly(level).sample(here.getX(), here.getZ());
        final var generator = com.minecolonies.kingdoms.world.WorldgenTerrainSampler.generatorOnly(level).sample(here.getX(), here.getZ());
        final var chunk = level.getChunkSource().getChunkNow(here.getX() >> 4, here.getZ() >> 4);
        source.sendSuccess(() -> Component.literal("Column " + here.getX() + "," + here.getZ() + " loadedPlanning="
            + (loaded.known() ? loaded.height() + (loaded.water() ? " water" : "") : "UNKNOWN(neighbourhood not loaded)")
            + " generatorPlanning=" + generator.height()).withStyle(ChatFormatting.GOLD), false);
        if (chunk == null) { source.sendFailure(Component.literal("chunk not loaded")); return 0; }
        final int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, here.getX(), here.getZ());
        for (int y = top; y >= top - 10; y--)
        {
            final BlockPos pos = new BlockPos(here.getX(), y, here.getZ());
            final var state = level.getBlockState(pos);
            final int shownY = y;
            source.sendSuccess(() -> Component.literal("  y=" + shownY + " " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + (com.minecolonies.kingdoms.world.WorldgenTerrainSampler.isVegetation(state) ? " [not-ground]" : " [ground]")), false);
        }
        return 1;
    }

    private static com.minecolonies.kingdoms.world.settlement.SettlementType siteType(final String text)
    {
        try { return com.minecolonies.kingdoms.world.settlement.SettlementType.valueOf(text.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return com.minecolonies.kingdoms.world.settlement.SettlementType.VILLAGE; }
    }

    private static int probeSite(final CommandSourceStack source, final com.minecolonies.kingdoms.world.settlement.SettlementType type)
    {
        final BlockPos here = BlockPos.containing(source.getPosition());
        final var site = WorldSettlementManager.getInstance().probeSite(source.getLevel(), here.getX(), here.getZ(), type);
        source.sendSuccess(() -> Component.literal("Site probe " + type + " @ " + here.getX() + "," + here.getZ())
            .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(siteLine(site)), false);
        return site.accepted() ? 1 : 0;
    }

    static ArgumentBuilder<CommandSourceStack, ?> roadCommand()
    {
        return Commands.literal("road")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("list").executes(context -> listRoads(context.getSource())))
            .then(Commands.literal("info").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> roadInfo(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("nearby")
                .executes(context -> nearby(context.getSource(), 512))
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 8192))
                    .executes(context -> nearby(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
            .then(Commands.literal("route").then(Commands.argument("from", StringArgumentType.word())
                .then(Commands.argument("to", StringArgumentType.word())
                    .executes(context -> route(context.getSource(), StringArgumentType.getString(context, "from"),
                        StringArgumentType.getString(context, "to"))))))
            .then(Commands.literal("connect").then(Commands.argument("from", StringArgumentType.word())
                .then(Commands.argument("to", StringArgumentType.word())
                    .executes(context -> connect(context.getSource(), StringArgumentType.getString(context, "from"),
                        StringArgumentType.getString(context, "to"))))))
            .then(Commands.literal("sample").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                    .executes(context -> roadSample(context.getSource(), StringArgumentType.getString(context, "id"),
                        IntegerArgumentType.getInteger(context, "percent"))))))
            .then(Commands.literal("regenerate-segment")
                .executes(context -> regenerate(context.getSource())));
    }

    static ArgumentBuilder<CommandSourceStack, ?> growthCommand()
    {
        return Commands.literal("growth")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("list").executes(context -> listGrowth(context.getSource())))
            .then(Commands.literal("info").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> growthInfo(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("evaluate").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> evaluateGrowth(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("tick").executes(context -> growthTick(context.getSource())))
            .then(Commands.literal("materialize").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> materializeBuilding(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("grow").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> grow(context.getSource(), StringArgumentType.getString(context, "id"), 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 8))
                    .executes(context -> grow(context.getSource(), StringArgumentType.getString(context, "id"),
                        IntegerArgumentType.getInteger(context, "count"))))))
            .then(Commands.literal("catalog").executes(context -> structureCatalog(context.getSource())))
            .then(Commands.literal("layout").then(Commands.argument("id", StringArgumentType.word())
                .executes(context -> growthLayout(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("stats").executes(context -> growthStats(context.getSource())));
    }

    private static int listSettlements(final CommandSourceStack source)
    {
        final List<SettlementRecord> values = KingdomsSavedData.get(source.getLevel()).settlements().records().stream()
            .sorted(Comparator.comparing(SettlementRecord::name)).toList();
        source.sendSuccess(() -> Component.literal("Settlements: " + values.size()).withStyle(ChatFormatting.GOLD), false);
        values.forEach(value -> source.sendSuccess(() -> Component.literal("- " + value.name() + " " + value.id()
            + " " + value.type() + " " + value.physicalState() + " @ " + pos(value.anchor())), false));
        return values.size();
    }

    private static int settlementInfo(final CommandSourceStack source, final String text)
    {
        final SettlementRecord value = settlement(source, text);
        if (value == null) return 0;
        source.sendSuccess(() -> Component.literal(value.name() + " [" + value.type() + "]").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("id=" + value.id() + " dimension=" + value.dimension()
            + " anchor=" + pos(value.anchor()) + " gate=" + pos(value.gate()) + " facing=" + value.orientation()), false);
        source.sendSuccess(() -> Component.literal("faction=" + value.factionId() + " population=" + value.initialPopulation()
            + " physical=" + value.physicalState() + " generatedChunks=" + value.generatedChunks().size()), false);
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var growth = data.growth().state(value.id()).orElse(null);
        final List<SettlementBuildingRecord> buildings = data.growth().forSettlement(value.id());
        final int population = data.colony(value.id()).map(com.minecolonies.kingdoms.colony.NPCColonyData::population)
            .orElse(value.initialPopulation());
        source.sendSuccess(() -> Component.literal("growth=" + (growth == null ? "STARTER" : growth.stage())
            + " population=" + population + " planned=" + buildings.stream().filter(building -> building.status() == SettlementBuildingStatus.PLANNED).count()
            + " completed=" + buildings.stream().filter(building -> building.status() == SettlementBuildingStatus.COMPLETED).count()
            + " next=" + (growth == null ? "not evaluated" : growth.lastDecision())), false);
        if (growth != null) source.sendSuccess(() -> Component.literal("starter=" + growth.starterDistrictStatus()
            + " blocker=" + (growth.blocker() == null ? "none" : growth.blocker())), false);
        value.siteAnalysis().ifPresent(site -> source.sendSuccess(() -> Component.literal(siteLine(site)), false));
        return 1;
    }

    private static int listGrowth(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<SettlementRecord> settlements = data.settlements().records().stream().sorted(Comparator.comparing(SettlementRecord::id)).toList();
        source.sendSuccess(() -> Component.literal("Settlement growth states: " + settlements.size()).withStyle(ChatFormatting.GOLD), false);
        settlements.forEach(settlement -> {
            final var state = data.growth().state(settlement.id()).orElse(null);
            final int population = data.colony(settlement.id()).map(com.minecolonies.kingdoms.colony.NPCColonyData::population).orElse(0);
            source.sendSuccess(() -> Component.literal("- " + settlement.name() + " " + settlement.id() + " stage="
                + (state == null ? "STARTER" : state.stage()) + " population=" + population
                + " buildings=" + data.growth().forSettlement(settlement.id()).size()), false);
        });
        return settlements.size();
    }

    private static int growthInfo(final CommandSourceStack source, final String text)
    {
        final SettlementRecord settlement = settlement(source, text);
        if (settlement == null) return 0;
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var state = data.growth().state(settlement.id()).orElse(null);
        final List<SettlementBuildingRecord> buildings = data.growth().forSettlement(settlement.id());
        source.sendSuccess(() -> Component.literal("Growth " + settlement.name() + " stage="
            + (state == null ? "STARTER" : state.stage()) + " buildings=" + buildings.size()).withStyle(ChatFormatting.GOLD), false);
        if (state != null) source.sendSuccess(() -> Component.literal("lastEvaluation=" + state.lastEvaluationAt()
            + " decision=" + state.lastDecision() + " blocker=" + (state.blocker() == null ? "none" : state.blocker())), false);
        buildings.forEach(building -> source.sendSuccess(() -> Component.literal("- " + building.id() + " " + building.type()
            + " " + building.status() + " origin=" + building.origin() + " @ " + pos(building.anchor())
            + " chunks=" + building.generatedChunks().size()
            + " visual=" + building.structureSource() + ':' + building.structureId() + " style=" + building.styleFamily()
            + " entrance=" + pos(building.entrance()) + " footprint=" + building.footprint()
            + (building.terrainShaping() == null ? "" : " terrain=" + building.terrainShaping().pad().mode()
                + "/" + building.terrainShaping().pad().terrainWorkVolume())
            + buildingSample(source, settlement, building)
            + (building.blocker() == null ? "" : " blocker=" + building.blocker())), false));
        return 1;
    }

    private static int evaluateGrowth(final CommandSourceStack source, final String text)
    {
        final SettlementRecord settlement = settlement(source, text);
        if (settlement == null) return 0;
        final GrowthEvaluationResult result = SettlementGrowthManager.getInstance().evaluateNow(source.getServer(), settlement.id());
        source.sendSuccess(() -> Component.literal("Growth evaluated: stage=" + result.stage() + " planned="
            + (result.plannedBuildingId() == null ? "none" : result.plannedBuildingType() + " " + result.plannedBuildingId())
            + " populationGrew=" + result.populationGrew() + " detail=" + result.detail()), true);
        return 1;
    }

    private static int growthTick(final CommandSourceStack source)
    {
        final List<GrowthEvaluationResult> results = SettlementGrowthManager.getInstance().tickNow(source.getServer());
        source.sendSuccess(() -> Component.literal("Growth cycle evaluated settlements=" + results.size()), true);
        results.forEach(result -> source.sendSuccess(() -> Component.literal("- " + result.settlementId() + " " + result.detail()), false));
        return Math.max(1, results.size());
    }

    private static int materializeBuilding(final CommandSourceStack source, final String text)
    {
        final UUID id = uuid(source, text);
        if (id == null) return 0;
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final SettlementBuildingRecord building = data.growth().building(id).orElse(null);
        if (building == null && data.settlements().get(id).isPresent())
        {
            final SettlementRecord owner = data.settlements().get(id).orElseThrow();
            final var level = source.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, owner.dimension()));
            if (level == null) { source.sendFailure(Component.literal("Settlement dimension is unavailable")); return 0; }
            final var result = SettlementGrowthManager.getInstance().materializeSettlement(level, owner);
            if (!result.blocker().isEmpty()) { source.sendFailure(Component.literal("Blocked: " + result.blocker())); return 0; }
            source.sendSuccess(() -> Component.literal("Materialized " + owner.name() + " buildings=" + result.buildingsProcessed()
                + " changed blocks=" + result.changedBlocks()), true);
            return 1;
        }
        if (building == null) { source.sendFailure(Component.literal("Unknown growth building or settlement " + id)); return 0; }
        final SettlementRecord settlement = data.settlements().get(building.settlementId()).orElse(null);
        if (settlement == null) { source.sendFailure(Component.literal("Building settlement is unavailable")); return 0; }
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION, settlement.dimension());
        final net.minecraft.server.level.ServerLevel level = source.getServer().getLevel(key);
        if (level == null) { source.sendFailure(Component.literal("Building dimension is unavailable")); return 0; }
        final int changed = SettlementGrowthManager.getInstance().materialize(level, building)
            + SettlementGrowthManager.getInstance().materializeStreets(level, data, settlement);
        source.sendSuccess(() -> Component.literal("Materialized " + building.type() + " " + building.id()
            + "; status=" + building.status() + " changed blocks=" + changed
            + (building.blocker() == null ? "" : " blocker=" + building.blocker())), true);
        return 1;
    }

    private static int grow(final CommandSourceStack source, final String text, final int count)
    {
        final SettlementRecord settlement = settlement(source, text);
        if (settlement == null) return 0;
        final var level = source.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION, settlement.dimension()));
        if (level == null) { source.sendFailure(Component.literal("Settlement dimension is unavailable")); return 0; }
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        int grown = 0;
        for (int index = 0; index < count; index++)
        {
            data.growth().forSettlement(settlement.id()).stream()
                .filter(value -> value.status() == SettlementBuildingStatus.PLANNED)
                .forEach(value -> SettlementGrowthManager.getInstance().materialize(level, value));
            final GrowthEvaluationResult result = SettlementGrowthManager.getInstance().evaluateNow(source.getServer(), settlement.id());
            if (result.plannedBuildingId() == null)
            {
                source.sendSuccess(() -> Component.literal("Growth stopped: " + result.detail()), false);
                break;
            }
            final SettlementBuildingRecord building = data.growth().building(result.plannedBuildingId()).orElseThrow();
            final int changed = SettlementGrowthManager.getInstance().materialize(level, building);
            SettlementGrowthManager.getInstance().materializeStreets(level, data, settlement);
            grown++;
            source.sendSuccess(() -> Component.literal("- " + building.type() + " " + building.id() + " status=" + building.status()
                + " @ " + pos(building.anchor()) + " changed=" + changed
                + (building.blocker() == null ? "" : " blocker=" + building.blocker())), false);
        }
        final int total = grown;
        source.sendSuccess(() -> Component.literal("Grew " + settlement.name() + " by " + total + " building(s); total="
            + data.growth().forSettlement(settlement.id()).size()), true);
        return Math.max(1, total);
    }

    private static String siteLine(final com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalysis site)
    {
        return "site accepted=" + site.accepted() + " rejection=" + site.rejection()
            + " samples=" + site.totalSamples() + " buildable=" + percent(site.buildableFraction())
            + " connected=" + percent(site.connectedFraction()) + " water=" + percent(site.waterFraction())
            + " elevation=" + site.minimumHeight() + ".." + site.maximumHeight() + " maxStep=" + site.maximumLocalStep()
            + " gateApproaches=" + site.gateApproaches() + " estimatedTerrainWork=" + site.estimatedTerrainWork();
    }

    private static int growthStats(final CommandSourceStack source)
    {
        final GrowthStats stats = SettlementGrowthManager.getInstance().stats();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Growth stats: evaluations=%d transitions=%d planned=%d completed=%d blocked=%d chunks=%d population=%d avg=%.3fms max=%.3fms",
            stats.evaluations(), stats.stageTransitions(), stats.buildingsPlanned(), stats.buildingsCompleted(), stats.blockedBuilds(),
            stats.physicalChunksProcessed(), stats.populationGrowthEvents(), stats.averageEvaluationNanos() / 1_000_000.0D,
            stats.maximumEvaluationNanos() / 1_000_000.0D)).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int structureCatalog(final CommandSourceStack source)
    {
        final var service = SettlementStructureService.getInstance();
        final var descriptors = service.catalog().descriptors();
        source.sendSuccess(() -> Component.literal("Structure catalog: state="
            + service.state() + " descriptors=" + descriptors.size()
            + (service.failure().isBlank() ? "" : " failure=" + service.failure()))
            .withStyle(ChatFormatting.GOLD), false);
        descriptors.stream().collect(java.util.stream.Collectors.groupingBy(value -> value.styleFamily() + "/" + value.buildingType(),
                java.util.TreeMap::new, java.util.stream.Collectors.counting()))
            .forEach((key, count) -> source.sendSuccess(() -> Component.literal("- " + key + "=" + count), false));
        return descriptors.size();
    }

    private static int growthLayout(final CommandSourceStack source, final String text)
    {
        final SettlementRecord settlement = settlement(source, text);
        if (settlement == null) return 0;
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final var layout = data.growth().layout(settlement.id()).orElse(null);
        final var state = data.growth().state(settlement.id()).orElse(null);
        settlement.siteAnalysis().ifPresent(site -> source.sendSuccess(() -> Component.literal(siteLine(site)), false));
        if (state != null)
            source.sendSuccess(() -> Component.literal("starter=" + state.starterDistrictStatus() + " plannerVersion="
                + state.starterPlannerVersion() + " blocker=" + (state.blocker() == null ? "none" : state.blocker())), false);
        if (layout == null)
        {
            source.sendSuccess(() -> Component.literal("Layout " + settlement.name() + ": none persisted (starter not planned or blocked)")
                .withStyle(ChatFormatting.GOLD), false);
            if (state != null) sendDiagnostics(source, "starter planning", state.starterDiagnostics());
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Layout " + settlement.name() + " style=" + layout.styleFamily()
            + " template=" + (layout.templateId().isBlank() ? "legacy/growth" : layout.templateId())
            + " streets=" + layout.streets().segments().size() + " streetChunksGenerated=" + layout.generatedStreetChunkMarkers()
            + " lots=" + layout.lots().size()).withStyle(ChatFormatting.GOLD), false);
        sendDiagnostics(source, "cumulative planning", layout.diagnostics());
        if (layout.lastSearch().positionsChecked() > 0) sendDiagnostics(source, "last search", layout.lastSearch());
        layout.streets().segments().forEach(street -> source.sendSuccess(() -> Component.literal("- street " + street.purpose()
            + " width=" + street.width() + " points=" + street.points().size()
            + " kinds=" + street.geometry().stream().collect(java.util.stream.Collectors.groupingBy(
                com.minecolonies.kingdoms.world.settlement.layout.LocalStreetPoint::kind, java.util.TreeMap::new,
                java.util.stream.Collectors.counting()))
            + " from=" + pos(street.points().getFirst()) + " to=" + pos(street.points().getLast())), false));
        layout.lots().forEach(lot -> {
            final var building = data.growth().building(lot.buildingId()).orElse(null);
            source.sendSuccess(() -> Component.literal("- lot " + (building == null ? "?" : building.type() + " " + building.status())
                + " id=" + lot.buildingId() + " entrance=" + pos(lot.entrance()) + " join=" + pos(lot.streetJoin())
                + " footprint=" + lot.footprint()
                + (building == null || building.terrainShaping() == null ? "" : " pad=" + building.terrainShaping().pad().mode()
                    + " y=" + building.terrainShaping().pad().targetHeight() + " cut=" + building.terrainShaping().pad().cutVolume()
                    + " fill=" + building.terrainShaping().pad().fillVolume() + " maxCut=" + building.terrainShaping().pad().maximumCutDepth()
                    + " maxFill=" + building.terrainShaping().pad().maximumFillDepth()
                    + " walls=" + building.terrainShaping().pad().retainingWalls().size())), false);
        });
        return 1;
    }

    private static void sendDiagnostics(final CommandSourceStack source, final String label,
        final com.minecolonies.kingdoms.world.settlement.layout.LayoutPlanningDiagnostics diagnostics)
    {
        source.sendSuccess(() -> Component.literal(label + ": positions=" + diagnostics.positionsChecked()
            + " structures=" + diagnostics.structuresConsidered() + " rotations=" + diagnostics.rotationsConsidered()
            + " pads=" + diagnostics.candidatePadsChecked() + " accepted=" + diagnostics.acceptedCandidates()
            + " terrainWork=" + diagnostics.selectedTerrainWork() + " streetExtensions=" + diagnostics.streetExtensionsCreated()
            + " lastMs=" + String.format(java.util.Locale.ROOT, "%.3f", diagnostics.lastPlanningNanos() / 1_000_000.0D)), false);
        diagnostics.rejections().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> source.sendSuccess(() -> Component.literal("  rejected " + entry.getKey() + "=" + entry.getValue()), false));
    }

    private static String percent(final double value)
    {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0D);
    }

    private static int locate(final CommandSourceStack source)
    {
        final BlockPos here = BlockPos.containing(source.getPosition());
        final SettlementRecord nearest = KingdomsSavedData.get(source.getLevel()).settlements().records().stream()
            .filter(value -> value.dimension().equals(source.getLevel().dimension().location()))
            .min(Comparator.comparingDouble(value -> value.anchor().distSqr(here))).orElse(null);
        if (nearest == null) { source.sendFailure(Component.literal("No planned settlement in this dimension")); return 0; }
        source.sendSuccess(() -> Component.literal("Nearest settlement: " + nearest.name() + " " + pos(nearest.anchor())
            + " distance=" + (int) Math.sqrt(nearest.anchor().distSqr(here))).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int region(final CommandSourceStack source)
    {
        final BlockPos here = BlockPos.containing(source.getPosition());
        final int size = com.minecolonies.kingdoms.config.KingdomsConfig.SERVER.settlementRegionSize.get();
        final SettlementRegion region = SettlementRegion.containing(source.getLevel().dimension().location(), here.getX(), here.getZ(), size);
        final var registry = KingdomsSavedData.get(source.getLevel()).settlements();
        source.sendSuccess(() -> Component.literal("Settlement region " + region.x() + "," + region.z()
            + " planned=" + registry.isRegionPlanned(region) + " settlement="
            + registry.inRegion(region).map(SettlementRecord::name).orElse("none")), false);
        return 1;
    }

    private static int planRegion(final CommandSourceStack source, final int radius)
    {
        final BlockPos here = BlockPos.containing(source.getPosition());
        final int size = com.minecolonies.kingdoms.config.KingdomsConfig.SERVER.settlementRegionSize.get();
        final SettlementRegion region = SettlementRegion.containing(source.getLevel().dimension().location(), here.getX(), here.getZ(), size);
        // Generator height queries cost ~1-2 ms each, so region planning runs on the background planner and the
        // result is reported here when committed; the server/client thread never waits for it.
        final boolean scheduled = WorldSettlementManager.getInstance().schedulePlanning(source.getLevel(), region, radius,
            report -> source.sendSuccess(() -> Component.literal("Planned region radius " + radius + ": " + report), true));
        source.sendSuccess(() -> Component.literal(scheduled
            ? "Planning region radius " + radius + " in the background; a completion message follows"
            : "Region radius " + radius + " is already planned or being planned; new settlements=0"), false);
        return 1;
    }

    private static int materialize(final CommandSourceStack source, final String text)
    {
        final SettlementRecord value = settlement(source, text);
        if (value == null) return 0;
        final var result = WorldSettlementManager.getInstance().materialize(source.getLevel(), value);
        if (!result.blocker().isEmpty())
        {
            source.sendFailure(Component.literal("Materialization of " + value.name() + " blocked: " + result.blocker()));
            source.sendFailure(Component.literal("See /kingdoms growth layout " + value.id() + " for rejection counts"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Materialized " + value.name() + "; buildings=" + result.buildingsProcessed()
            + " changed blocks=" + result.changedBlocks()), true);
        return 1;
    }

    private static int listRoads(final CommandSourceStack source)
    {
        final List<RoadRecord> values = KingdomsSavedData.get(source.getLevel()).roads().roads().stream()
            .sorted(Comparator.comparing(RoadRecord::id)).toList();
        source.sendSuccess(() -> Component.literal("Roads: " + values.size()).withStyle(ChatFormatting.GOLD), false);
        values.forEach(value -> source.sendSuccess(() -> Component.literal("- " + value.id() + " " + value.type()
            + " " + value.status() + " physical=" + value.hasPhysicalGeometry()
            + String.format(Locale.ROOT, " length=%.1f points=%d", value.length(), value.polyline().size())), false));
        return values.size();
    }

    private static int roadInfo(final CommandSourceStack source, final String text)
    {
        final UUID id = uuid(source, text);
        if (id == null) return 0;
        final RoadRecord road = KingdomsSavedData.get(source.getLevel()).roads().get(id).orElse(null);
        if (road == null) { source.sendFailure(Component.literal("Unknown road " + id)); return 0; }
        source.sendSuccess(() -> Component.literal("Road " + road.id() + " [" + road.type() + "]").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(road.firstSettlementId() + " -> " + road.secondSettlementId()
            + " status=" + road.status() + " version=" + road.generationVersion()), false);
        final var kinds = road.geometry().points().stream().collect(java.util.stream.Collectors.groupingBy(
            com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPoint::kind,
            () -> new java.util.EnumMap<>(com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind.class),
            java.util.stream.Collectors.counting()));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "length=%.1f points=%d generatedChunks=%d physical=%s geometry=%s",
            road.length(), road.polyline().size(), road.generatedChunks().size(), road.hasPhysicalGeometry(), kinds)), false);
        source.sendSuccess(() -> Component.literal("Samples: " + roadSamples(source, road)), false);
        return 1;
    }

    private static int nearby(final CommandSourceStack source, final int radius)
    {
        final BlockPos here = BlockPos.containing(source.getPosition());
        final double maximum = (double) radius * radius;
        final List<RoadRecord> roads = KingdomsSavedData.get(source.getLevel()).roads().roads().stream()
            .filter(road -> road.dimension().equals(source.getLevel().dimension().location()))
            .filter(road -> road.polyline().stream().anyMatch(point -> point.distSqr(here) <= maximum)).toList();
        source.sendSuccess(() -> Component.literal("Roads within " + radius + " blocks: " + roads.size()), false);
        roads.forEach(road -> source.sendSuccess(() -> Component.literal("- " + road.id() + " " + road.type()), false));
        return roads.size();
    }

    private static int route(final CommandSourceStack source, final String from, final String to)
    {
        final SettlementRecord first = settlement(source, from);
        final SettlementRecord second = settlement(source, to);
        if (first == null || second == null) return 0;
        final var route = new RoadRoutePlanner().shortest(KingdomsSavedData.get(source.getLevel()).roads(), first.id(), second.id());
        if (route.isEmpty()) { source.sendFailure(Component.literal("No road route between settlements")); return 0; }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Route: edges=%d length=%.1f %s",
            route.get().roadIds().size(), route.get().length(), route.get().roadIds())).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int connect(final CommandSourceStack source, final String from, final String to)
    {
        final SettlementRecord first = settlement(source, from);
        final SettlementRecord second = settlement(source, to);
        if (first == null || second == null) return 0;
        final RoadRecord road = WorldSettlementManager.getInstance().connect(source.getLevel(), first, second).orElse(null);
        if (road == null) { source.sendFailure(Component.literal("Settlements cannot be connected")); return 0; }
        source.sendSuccess(() -> Component.literal("Road ready: " + road.id()), true);
        return 1;
    }

    private static int regenerate(final CommandSourceStack source)
    {
        final BlockPos here = BlockPos.containing(source.getPosition());
        final int changed = WorldSettlementManager.getInstance().regenerateRoadSegment(source.getLevel(), new ChunkPos(here));
        source.sendSuccess(() -> Component.literal("Regenerated current road segment; changed blocks=" + changed), true);
        return Math.max(1, changed);
    }

    private static int stats(final CommandSourceStack source)
    {
        final WorldGenerationStats stats = WorldSettlementManager.getInstance().stats();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Worldgen stats: regions=%d settlements=%d roads=%d settlementChunks=%d roadChunks=%d blocks=%d planning avg=%.3fms max=%.3fms",
            stats.regionsPlanned(), stats.settlementsPlanned(), stats.roadsPlanned(), stats.settlementChunksGenerated(),
            stats.roadChunksGenerated(), stats.blocksChanged(), stats.averagePlanningDurationNanos() / 1_000_000.0D,
            stats.maximumPlanningDurationNanos() / 1_000_000.0D)).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static SettlementRecord settlement(final CommandSourceStack source, final String text)
    {
        final UUID id = uuid(source, text);
        if (id == null) return null;
        final SettlementRecord value = KingdomsSavedData.get(source.getLevel()).settlements().get(id).orElse(null);
        if (value == null) source.sendFailure(Component.literal("Unknown settlement " + id));
        return value;
    }
    private static UUID uuid(final CommandSourceStack source, final String text)
    {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException exception) { source.sendFailure(Component.literal("Invalid UUID: " + text)); return null; }
    }
    private static String pos(final BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }

    private static String buildingSample(final CommandSourceStack source, final SettlementRecord settlement,
        final SettlementBuildingRecord building)
    {
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION, settlement.dimension());
        final net.minecraft.server.level.ServerLevel level = source.getServer().getLevel(key);
        if (level == null || !level.hasChunkAt(building.anchor())) return " sample=[unloaded]";
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            building.anchor().getX(), building.anchor().getZ()) - 1;
        final BlockPos sample = new BlockPos(building.anchor().getX(), y, building.anchor().getZ());
        return " sample=" + level.getBlockState(sample).getBlock().builtInRegistryHolder().key().location() + "@" + pos(sample);
    }

    /** Diagnostic: the centre-line point at a percentage of a road and the block stack there (never loads chunks). */
    private static int roadSample(final CommandSourceStack source, final String text, final int percent)
    {
        final UUID id = uuid(source, text);
        if (id == null) return 0;
        final RoadRecord road = KingdomsSavedData.get(source.getLevel()).roads().get(id).orElse(null);
        if (road == null) { source.sendFailure(Component.literal("Unknown road " + id)); return 0; }
        final var points = road.geometry().points();
        final int index = Math.min(points.size() - 1, points.size() * percent / 100);
        final var point = points.get(index);
        final BlockPos walking = point.position();
        final BlockPos before = points.get(Math.max(0, index - 1)).position();
        final BlockPos after = points.get(Math.min(points.size() - 1, index + 1)).position();
        final int stepX = -Integer.signum(after.getZ() - before.getZ());
        final int stepZ = Integer.signum(after.getX() - before.getX());
        source.sendSuccess(() -> Component.literal("Road " + road.type() + " " + percent + "% centre=" + pos(walking)
            + " kind=" + point.kind() + " (pavement is at y-1)").withStyle(ChatFormatting.GOLD), false);
        if (source.getLevel().getChunkSource().getChunkNow(walking.getX() >> 4, walking.getZ() >> 4) == null)
        {
            source.sendFailure(Component.literal("chunk not loaded"));
            return 0;
        }
        for (int y = walking.getY() + 3; y >= walking.getY() - 3; y--)
        {
            final int shown = y;
            final StringBuilder row = new StringBuilder("  y=" + y + ":");
            for (int lateral = -3; lateral <= 3; lateral++)
            {
                final var state = source.getLevel().getBlockState(new BlockPos(walking.getX() + lateral * stepX, shown,
                    walking.getZ() + lateral * stepZ));
                final String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                row.append(' ').append(name.length() > 14 ? name.substring(0, 14) : name);
            }
            source.sendSuccess(() -> Component.literal(row.toString()), false);
        }
        return 1;
    }

    private static String roadSamples(final CommandSourceStack source, final RoadRecord road)
    {
        final int[] percentages = {10, 25, 50, 75, 90};
        final StringBuilder result = new StringBuilder();
        for (final int percentage : percentages)
        {
            if (!result.isEmpty()) result.append("; ");
            final BlockPos point = pointAt(road, percentage / 100.0D);
            result.append(percentage).append("%=").append(pos(point));
            if (source.getLevel().hasChunkAt(point))
            {
                final int y = source.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    point.getX(), point.getZ()) - 1;
                result.append('[').append(source.getLevel().getBlockState(new BlockPos(point.getX(), y, point.getZ()))
                    .getBlock().builtInRegistryHolder().key().location()).append(']');
            }
            else result.append("[unloaded]");
        }
        return result.toString();
    }

    private static BlockPos pointAt(final RoadRecord road, final double progress)
    {
        final double target = road.length() * progress;
        double traversed = 0.0D;
        for (int index = 1; index < road.polyline().size(); index++)
        {
            final BlockPos from = road.polyline().get(index - 1);
            final BlockPos to = road.polyline().get(index);
            final double segment = Math.sqrt(from.distSqr(to));
            if (traversed + segment >= target)
            {
                final double local = segment == 0.0D ? 0.0D : (target - traversed) / segment;
                return new BlockPos((int) Math.round(from.getX() + (to.getX() - from.getX()) * local),
                    (int) Math.round(from.getY() + (to.getY() - from.getY()) * local),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * local));
            }
            traversed += segment;
        }
        return road.polyline().getLast();
    }
}
