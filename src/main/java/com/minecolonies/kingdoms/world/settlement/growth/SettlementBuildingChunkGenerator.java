package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.integration.structurize.SettlementStructureService;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

public final class SettlementBuildingChunkGenerator
{
    private final TerrainShapingChunkGenerator terrainShaper = new TerrainShapingChunkGenerator();

    public int generate(final ServerLevel level, final LevelChunk chunk, final SettlementBuildingRecord building,
        final long gameTime)
    {
        if (!building.intersects(chunk.getPos()) || !building.needsGeneration(chunk.getPos().toLong())
            || building.status() == SettlementBuildingStatus.PLANNED
            || building.status() == SettlementBuildingStatus.BLOCKED
            || building.status() == SettlementBuildingStatus.FAILED) return 0;
        if (building.usesBlueprintVisual()) return generateBlueprint(level, chunk, building, gameTime);
        if (containsBlockEntity(level, chunk, building))
        {
            building.blocked("block entity inside construction footprint");
            return 0;
        }
        building.generating();
        int changed = 0;
        final int minX = Math.max(chunk.getPos().getMinBlockX(), building.anchor().getX() - building.type().halfWidth());
        final int maxX = Math.min(chunk.getPos().getMaxBlockX(), building.anchor().getX() + building.type().halfWidth());
        final int minZ = Math.max(chunk.getPos().getMinBlockZ(), building.anchor().getZ() - building.type().halfDepth());
        final int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), building.anchor().getZ() + building.type().halfDepth());
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
        {
            final int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (surface <= level.getMinBuildHeight() || surface + 6 >= level.getMaxBuildHeight()) continue;
            changed += generateColumn(level, building, x, z, surface,
                x - building.anchor().getX(), z - building.anchor().getZ());
        }
        building.markGenerated(chunk.getPos().toLong());
        if (building.footprintChunks().stream().allMatch(chunkKey -> !building.needsGeneration(chunkKey)))
            building.completed(gameTime);
        chunk.setUnsaved(true);
        return changed;
    }

    /**
     * Validates every pending slice before any is written, so a refusal (block entity, terrain drift) never leaves a
     * partially built structure. Only valid for blueprint buildings with a READY catalog.
     */
    public java.util.Optional<String> preflight(final ServerLevel level, final java.util.List<LevelChunk> slices,
        final SettlementBuildingRecord building)
    {
        if (!building.usesBlueprintVisual()) return java.util.Optional.empty();
        if (!SettlementStructureService.getInstance().ready()) return java.util.Optional.of("STRUCTURE_CATALOG_LOADING");
        for (final LevelChunk slice : slices)
        {
            if (building.terrainShaping() != null)
            {
                final var terrain = terrainShaper.preflight(level, slice, building.terrainShaping());
                if (terrain.isPresent()) return terrain;
            }
            final var blueprint = SettlementStructureService.getInstance().placer().preflight(level, slice, building);
            if (blueprint.isPresent()) return blueprint;
        }
        return java.util.Optional.empty();
    }

    private int generateBlueprint(final ServerLevel level, final LevelChunk chunk,
        final SettlementBuildingRecord building, final long gameTime)
    {
        // StructurePacks.getBlueprint waits on Structurize's loading barrier. On an integrated client that barrier
        // opens only after the client world ticks, so calling it on the server thread before READY can deadlock.
        if (!SettlementStructureService.getInstance().ready())
        {
            building.awaiting("STRUCTURE_CATALOG_LOADING");
            return 0;
        }
        building.generating();
        int terrainChanges = 0;
        if (building.terrainShaping() != null)
        {
            final var shaping = terrainShaper.generate(level, chunk, building.terrainShaping());
            if (shaping.blocked()) { building.blocked(shaping.reason()); return 0; }
            terrainChanges = shaping.changes();
        }
        final var result = SettlementStructureService.getInstance().placer().placeChunk(level, chunk, building);
        if (result.blocked()) { building.blocked(result.error()); return terrainChanges; }
        if (!result.successful()) { building.failed(result.error()); return terrainChanges; }
        building.markGenerated(chunk.getPos().toLong());
        if (building.footprintChunks().stream().allMatch(chunkKey -> !building.needsGeneration(chunkKey)))
            building.completed(gameTime);
        if (result.changes() > 0) chunk.setUnsaved(true);
        return terrainChanges + result.changes();
    }

    private static int generateColumn(final ServerLevel level, final SettlementBuildingRecord building,
        final int x, final int z, final int surface, final int dx, final int dz)
    {
        final SettlementBuildingType type = building.type();
        if (type == SettlementBuildingType.FARM) return farm(level, type, x, z, surface, dx, dz);
        if (type == SettlementBuildingType.QUARRY) return quarry(level, type, x, z, surface, dx, dz);
        if (type == SettlementBuildingType.LUMBER_YARD) return lumberYard(level, type, x, z, surface, dx, dz);
        int changed = set(level, new BlockPos(x, surface, z), block(type.floorBlockId()).defaultBlockState());
        final boolean edge = Math.abs(dx) == type.halfWidth() || Math.abs(dz) == type.halfDepth();
        final boolean door = dz == type.halfDepth() && Math.abs(dx) <= 1;
        if (edge && !door)
        {
            for (int y = 1; y <= 3; y++) changed += set(level, new BlockPos(x, surface + y, z), block(type.wallBlockId()).defaultBlockState());
        }
        else changed += clear(level, x, surface + 1, z, 3);
        changed += set(level, new BlockPos(x, surface + 4, z), block(type.roofBlockId()).defaultBlockState());
        return changed;
    }

    private static int farm(final ServerLevel level, final SettlementBuildingType type, final int x, final int z,
        final int surface, final int dx, final int dz)
    {
        final boolean edge = Math.abs(dx) == type.halfWidth() || Math.abs(dz) == type.halfDepth();
        final boolean irrigation = !edge && Math.floorMod(dx, 4) == 0 && Math.floorMod(dz, 4) == 0;
        int changed = set(level, new BlockPos(x, surface, z), edge ? Blocks.OAK_LOG.defaultBlockState()
            : irrigation ? Blocks.WATER.defaultBlockState() : Blocks.FARMLAND.defaultBlockState());
        changed += clear(level, x, surface + 1, z, 2);
        if (edge && Math.floorMod(x + z, 4) == 0) changed += set(level, new BlockPos(x, surface + 1, z), Blocks.HAY_BLOCK.defaultBlockState());
        return changed;
    }

    private static int quarry(final ServerLevel level, final SettlementBuildingType type, final int x, final int z,
        final int surface, final int dx, final int dz)
    {
        final boolean edge = Math.abs(dx) == type.halfWidth() || Math.abs(dz) == type.halfDepth();
        int changed = set(level, new BlockPos(x, surface, z), edge ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
        changed += clear(level, x, surface + 1, z, 3);
        if (edge) changed += set(level, new BlockPos(x, surface + 1, z), Blocks.COBBLESTONE_WALL.defaultBlockState());
        return changed;
    }

    private static int lumberYard(final ServerLevel level, final SettlementBuildingType type, final int x, final int z,
        final int surface, final int dx, final int dz)
    {
        int changed = set(level, new BlockPos(x, surface, z), Blocks.COARSE_DIRT.defaultBlockState());
        changed += clear(level, x, surface + 1, z, 3);
        if (Math.abs(dx) < type.halfWidth() && Math.abs(dz) < type.halfDepth()
            && Math.floorMod(dx, 3) == 0 && Math.floorMod(dz, 3) == 0)
            changed += set(level, new BlockPos(x, surface + 1, z), Blocks.SPRUCE_LOG.defaultBlockState());
        return changed;
    }

    private static boolean containsBlockEntity(final ServerLevel level, final LevelChunk chunk,
        final SettlementBuildingRecord building)
    {
        final int minX = Math.max(chunk.getPos().getMinBlockX(), building.anchor().getX() - building.type().halfWidth());
        final int maxX = Math.min(chunk.getPos().getMaxBlockX(), building.anchor().getX() + building.type().halfWidth());
        final int minZ = Math.max(chunk.getPos().getMinBlockZ(), building.anchor().getZ() - building.type().halfDepth());
        final int maxZ = Math.min(chunk.getPos().getMaxBlockZ(), building.anchor().getZ() + building.type().halfDepth());
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
        {
            final int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = Math.max(level.getMinBuildHeight(), surface - 1); y <= Math.min(level.getMaxBuildHeight() - 1, surface + 5); y++)
                if (level.getBlockEntity(new BlockPos(x, y, z)) != null) return true;
        }
        return false;
    }

    private static int set(final ServerLevel level, final BlockPos pos, final BlockState state)
    {
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE) ? 1 : 0;
    }

    private static Block block(final String id)
    {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    private static int clear(final ServerLevel level, final int x, final int y, final int z, final int height)
    {
        int changed = 0;
        for (int offset = 0; offset < height; offset++)
            changed += set(level, new BlockPos(x, y + offset, z), Blocks.AIR.defaultBlockState());
        return changed;
    }
}
