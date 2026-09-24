package com.minecolonies.kingdoms.integration.structurize;

import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.util.BlockInfo;
import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureDescriptor;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureSource;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import com.minecolonies.kingdoms.world.settlement.structure.StructureTransform;
import com.minecolonies.kingdoms.world.settlement.structure.TerrainPlacementConstraints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Runtime view of MineColonies blueprints exposed through Structurize's public pack API. */
public final class MineColoniesStructureSource implements SettlementStructureSource
{
    private static final int MAX_PACKS = 32;
    private static final int MAX_BLUEPRINTS_PER_TYPE = 3;
    private static final Map<SettlementBuildingType, List<SearchPath>> PATHS = paths();
    private final List<SettlementStructureDescriptor> descriptors;

    private MineColoniesStructureSource(final List<SettlementStructureDescriptor> descriptors)
    {
        this.descriptors = List.copyOf(descriptors);
    }

    public static MineColoniesStructureSource scan(final HolderLookup.Provider registries,
        final BooleanSupplier cancelled)
    {
        final List<SettlementStructureDescriptor> result = new ArrayList<>();
        checkCancelled(cancelled);
        if (!StructurePacks.waitUntilFinishedLoading())
        {
            checkCancelled(cancelled);
            throw new IllegalStateException("Structurize structure-pack loading was interrupted");
        }
        checkCancelled(cancelled);
        final List<StructurePackMeta> packs = StructurePacks.getPackMetas().stream()
            .filter(pack -> !pack.isDisabled() && pack.getModList().stream().anyMatch("minecolonies"::equalsIgnoreCase))
            .sorted(Comparator.comparing(StructurePackMeta::getName)).limit(MAX_PACKS).toList();
        for (final StructurePackMeta pack : packs)
        {
            checkCancelled(cancelled);
            for (final var entry : PATHS.entrySet())
            {
                int accepted = 0;
                for (final SearchPath search : entry.getValue())
                {
                    final List<Blueprint> blueprints = StructurePacks.getBlueprints(
                        pack.getName(), search.path(), registries);
                    checkCancelled(cancelled);
                    for (final Blueprint blueprint : blueprints)
                    {
                        if (accepted >= MAX_BLUEPRINTS_PER_TYPE) break;
                        if (!blueprint.getFileName().toLowerCase(java.util.Locale.ROOT).contains(search.namePart())) continue;
                        final int structureLevel = trailingLevel(blueprint.getFileName());
                        if (structureLevel > 2) continue;
                        final Optional<Entrance> entrance = entrance(blueprint, entry.getKey());
                        if (entrance.isEmpty()) continue;
                        final BlockPos primary = blueprint.getPrimaryBlockOffset();
                        final StructureFootprint local = new StructureFootprint(-primary.getX(), -primary.getZ(),
                            blueprint.getSizeX() - 1 - primary.getX(), blueprint.getSizeZ() - 1 - primary.getZ());
                        final String relative = relativePath(pack, blueprint, search.path());
                        result.add(new SettlementStructureDescriptor("structurize:" + pack.getName(), relative,
                            entry.getKey(), pack.getName(), structureLevel, blueprint.getSizeX(), blueprint.getSizeY(),
                            blueprint.getSizeZ(), local, entrance.orElseThrow().offset().subtract(primary),
                            entrance.orElseThrow().facing(), transforms(), TerrainPlacementConstraints.settlementDefault()));
                        accepted++;
                    }
                    if (accepted >= MAX_BLUEPRINTS_PER_TYPE) break;
                }
            }
        }
        result.sort(Comparator.comparing(SettlementStructureDescriptor::stableId));
        KingdomsMod.LOGGER.info("Indexed {} compatible MineColonies structures from {} installed style packs", result.size(), packs.size());
        return new MineColoniesStructureSource(result);
    }

    private static void checkCancelled(final BooleanSupplier cancelled)
    {
        if (cancelled.getAsBoolean()) throw new CancellationException("structure catalog scan cancelled");
    }

    @Override
    public String sourceId()
    {
        return "structurize-minecolonies";
    }

    @Override
    public Collection<SettlementStructureDescriptor> descriptors()
    {
        return descriptors;
    }

    private static Optional<Entrance> entrance(final Blueprint blueprint, final SettlementBuildingType type)
    {
        final List<BlockInfo> doors = blueprint.getBlockInfoAsList().stream()
            .filter(info -> info.getState().is(BlockTags.DOORS))
            .filter(info -> info.getState().hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            .sorted(Comparator.comparingInt((BlockInfo info) -> info.getPos().getY())
                .thenComparingInt(info -> edgeDistance(info.getPos(), blueprint))
                .thenComparingInt(info -> info.getPos().getX()).thenComparingInt(info -> info.getPos().getZ())).toList();
        if (!doors.isEmpty())
        {
            final BlockInfo door = doors.get(0);
            final Direction facing = exteriorFacing(door.getPos(), blueprint,
                door.getState().getValue(BlockStateProperties.HORIZONTAL_FACING));
            return Optional.of(new Entrance(door.getPos().relative(facing), facing));
        }
        if (type != SettlementBuildingType.FARM && type != SettlementBuildingType.LUMBER_YARD
            && type != SettlementBuildingType.QUARRY) return Optional.empty();
        final BlockPos primary = blueprint.getPrimaryBlockOffset();
        final BlockState anchor = blueprint.getBlockState(primary);
        final Direction facing = anchor.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? anchor.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.SOUTH;
        return Optional.of(new Entrance(primary.relative(facing), facing));
    }

    private static Direction exteriorFacing(final BlockPos pos, final Blueprint blueprint, final Direction declared)
    {
        final int west = pos.getX(); final int east = blueprint.getSizeX() - 1 - pos.getX();
        final int north = pos.getZ(); final int south = blueprint.getSizeZ() - 1 - pos.getZ();
        final int minimum = Math.min(Math.min(west, east), Math.min(north, south));
        if (declared == Direction.WEST && west == minimum || declared == Direction.EAST && east == minimum
            || declared == Direction.NORTH && north == minimum || declared == Direction.SOUTH && south == minimum) return declared;
        if (west == minimum) return Direction.WEST;
        if (east == minimum) return Direction.EAST;
        if (north == minimum) return Direction.NORTH;
        return Direction.SOUTH;
    }

    private static int edgeDistance(final BlockPos pos, final Blueprint blueprint)
    {
        return Math.min(Math.min(pos.getX(), blueprint.getSizeX() - 1 - pos.getX()),
            Math.min(pos.getZ(), blueprint.getSizeZ() - 1 - pos.getZ()));
    }

    private static String relativePath(final StructurePackMeta pack, final Blueprint blueprint, final String fallback)
    {
        final Path parent = blueprint.getFilePath();
        final String folder;
        try { folder = pack.getPath().relativize(parent).toString().replace('\\', '/'); }
        catch (IllegalArgumentException ignored) { return fallback + '/' + blueprint.getFileName() + ".blueprint"; }
        return folder + '/' + blueprint.getFileName() + ".blueprint";
    }

    private static int trailingLevel(final String name)
    {
        int start = name.length();
        while (start > 0 && Character.isDigit(name.charAt(start - 1))) start--;
        if (start == name.length()) return 1;
        try { return Integer.parseInt(name.substring(start)); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private static Set<StructureTransform> transforms()
    {
        final Set<StructureTransform> result = new LinkedHashSet<>();
        for (final boolean mirrored : new boolean[] {false, true})
            for (final int rotation : new int[] {0, 90, 180, 270}) result.add(new StructureTransform(rotation, mirrored));
        return Set.copyOf(result);
    }

    private static Map<SettlementBuildingType, List<SearchPath>> paths()
    {
        final Map<SettlementBuildingType, List<SearchPath>> result = new EnumMap<>(SettlementBuildingType.class);
        result.put(SettlementBuildingType.HOUSE, List.of(new SearchPath("fundamentals", "residence")));
        result.put(SettlementBuildingType.FARM, List.of(new SearchPath("agriculture/horticulture", "farmer")));
        result.put(SettlementBuildingType.STOREHOUSE, List.of(new SearchPath("craftsmanship/storage", "warehouse")));
        result.put(SettlementBuildingType.LUMBER_YARD, List.of(new SearchPath("fundamentals", "lumberjack")));
        result.put(SettlementBuildingType.QUARRY, List.of(new SearchPath("fundamentals", "miner"),
            new SearchPath("infrastructure/mineshafts", "simplequarry")));
        result.put(SettlementBuildingType.SMITHY, List.of(new SearchPath("craftsmanship/metallurgy", "blacksmith")));
        result.put(SettlementBuildingType.MARKET, List.of(new SearchPath("fundamentals", "tavern"),
            new SearchPath("craftsmanship/storage", "deliveryman")));
        result.put(SettlementBuildingType.CIVIC, List.of(new SearchPath("fundamentals", "builder")));
        return Map.copyOf(result);
    }

    private record Entrance(BlockPos offset, Direction facing) {}
    private record SearchPath(String path, String namePart) {}
}
