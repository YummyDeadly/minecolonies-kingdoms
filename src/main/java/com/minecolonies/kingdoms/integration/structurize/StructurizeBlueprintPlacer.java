package com.minecolonies.kingdoms.integration.structurize;

import com.ldtteam.structurize.api.RotationMirror;
import com.ldtteam.structurize.blocks.interfaces.IAnchorBlock;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.placement.AbstractBlueprintIterator;
import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.BlueprintIteratorDefault;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.ldtteam.structurize.placement.structure.CreativeStructureHandler;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.structure.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

/** Places one already-loaded chunk slice and never creates a colony, work order, player or entity. */
public final class StructurizeBlueprintPlacer
{
    private static final String SOURCE_PREFIX = "structurize:";
    private static final int MAX_BLUEPRINT_VOLUME = 64 * 64 * 64;

    public PlacementResult placeChunk(final ServerLevel level, final net.minecraft.world.level.chunk.LevelChunk chunk,
        final SettlementBuildingRecord building)
    {
        if (!building.structureSource().startsWith(SOURCE_PREFIX)) return PlacementResult.failed("unsupported structure source");
        final String pack = building.structureSource().substring(SOURCE_PREFIX.length());
        final Blueprint blueprint = StructurePacks.getBlueprint(pack, building.structureId(), true, level.registryAccess());
        if (blueprint == null) return PlacementResult.failed("blueprint is unavailable: " + building.structureId());
        final int volume = blueprint.getSizeX() * blueprint.getSizeY() * blueprint.getSizeZ();
        if (volume <= 0 || volume > MAX_BLUEPRINT_VOLUME) return PlacementResult.failed("blueprint volume exceeds safety limit");
        final StructureTransform transform = new StructureTransform(building.rotation(), building.mirrored());
        blueprint.setRotationMirror(rotation(transform), level);
        final SafeCreativeHandler handler = new SafeCreativeHandler(level, building.anchor(), blueprint, rotation(transform), volume);
        final BlueprintIteratorDefault iterator = new BlueprintIteratorDefault(handler);
        final ChunkPos target = chunk.getPos();
        if (containsProtectedBlockEntity(level, handler, blueprint, target))
            return PlacementResult.blocked("block entity inside blueprint chunk slice");
        final StructurePlacer placer = new StructurePlacer(handler, iterator);
        BlockPos progress = AbstractBlueprintIterator.NULL_POS;
        int changed = 0;
        for (int call = 0; call <= volume + 1; call++)
        {
            final int[] candidates = {0};
            final StructurePhasePlacementResult phase = placer.executeStructureStep(level, null, progress,
                StructurePlacer.Operation.BLOCK_PLACEMENT,
                () -> iterator.increment((info, worldPos, ignored) -> {
                    final boolean skip = !target.equals(new ChunkPos(worldPos))
                        || info.getBlockInfo().getState().getBlock() instanceof IAnchorBlock
                        || level.getBlockEntity(worldPos) != null;
                    if (!skip) candidates[0]++;
                    return skip;
                }), false);
            changed += candidates[0];
            final BlockPlacementResult.Result result = phase.getBlockResult().getResult();
            if (result == BlockPlacementResult.Result.FINISHED) return PlacementResult.success(changed);
            if (result == BlockPlacementResult.Result.FAIL || result == BlockPlacementResult.Result.BREAK_BLOCK
                || result == BlockPlacementResult.Result.MISSING_ITEMS)
                return PlacementResult.failed("Structurize placement returned " + result.name().toLowerCase());
            progress = phase.getIteratorPos();
        }
        return PlacementResult.failed("blueprint placement iteration limit exceeded");
    }

    /** Read-only check that a chunk slice can be placed without touching an existing block entity. */
    public java.util.Optional<String> preflight(final ServerLevel level, final net.minecraft.world.level.chunk.LevelChunk chunk,
        final SettlementBuildingRecord building)
    {
        if (!building.structureSource().startsWith(SOURCE_PREFIX)) return java.util.Optional.of("unsupported structure source");
        final Blueprint blueprint = StructurePacks.getBlueprint(building.structureSource().substring(SOURCE_PREFIX.length()),
            building.structureId(), true, level.registryAccess());
        if (blueprint == null) return java.util.Optional.of("blueprint is unavailable: " + building.structureId());
        final int volume = blueprint.getSizeX() * blueprint.getSizeY() * blueprint.getSizeZ();
        if (volume <= 0 || volume > MAX_BLUEPRINT_VOLUME) return java.util.Optional.of("blueprint volume exceeds safety limit");
        final StructureTransform transform = new StructureTransform(building.rotation(), building.mirrored());
        blueprint.setRotationMirror(rotation(transform), level);
        final SafeCreativeHandler handler = new SafeCreativeHandler(level, building.anchor(), blueprint, rotation(transform), volume);
        return containsProtectedBlockEntity(level, handler, blueprint, chunk.getPos())
            ? java.util.Optional.of("BLOCK_ENTITY: block entity inside blueprint slice in chunk " + chunk.getPos())
            : java.util.Optional.empty();
    }

    private static boolean containsProtectedBlockEntity(final ServerLevel level, final SafeCreativeHandler handler,
        final Blueprint blueprint, final ChunkPos target)
    {
        for (int y = 0; y < blueprint.getSizeY(); y++) for (int z = 0; z < blueprint.getSizeZ(); z++)
            for (int x = 0; x < blueprint.getSizeX(); x++)
            {
                final BlockPos worldPos = handler.getProgressPosInWorld(new BlockPos(x, y, z));
                if (target.equals(new ChunkPos(worldPos)) && level.getBlockEntity(worldPos) != null) return true;
            }
        return false;
    }

    private static RotationMirror rotation(final StructureTransform transform)
    {
        final int index = transform.rotation() / 90;
        return transform.mirrored() ? RotationMirror.MIRRORED[index] : RotationMirror.NOT_MIRRORED[index];
    }

    private static final class SafeCreativeHandler extends CreativeStructureHandler
    {
        private final int limit;
        private SafeCreativeHandler(final ServerLevel level, final BlockPos anchor, final Blueprint blueprint,
            final RotationMirror rotation, final int limit)
        {
            // fancyPlacement=true: Structurize 1.0.832 resolves blocksolidsubstitution/blocksubstitution/fluid
            // substitution placeholders only in fancy mode; otherwise the placeholder blocks themselves are written.
            super(level, anchor, blueprint, rotation, true);
            this.limit = limit + 1;
        }
        @Override public int getMaxBlocksCheckedPerCall() { return limit; }
        @Override public int getStepsPerCall() { return limit; }
        @Override public void triggerSuccess(final BlockPos pos, final List<ItemStack> required, final boolean placement) {}
    }

    public record PlacementResult(int changes, boolean blocked, String error)
    {
        public static PlacementResult success(final int changes) { return new PlacementResult(changes, false, ""); }
        public static PlacementResult blocked(final String reason) { return new PlacementResult(0, true, reason); }
        public static PlacementResult failed(final String reason) { return new PlacementResult(0, false, reason); }
        public boolean successful() { return error.isEmpty(); }
    }
}
