package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Server-side placement and removal of camp structures (Phase 8.1). Bounded: at most one camp is built or taken down
 * per bandit cycle, a site check reads at most an 11x11 area around the site, and only chunks that are already
 * entity-ticking are read or written; nothing is ever loaded. Every placed block is recorded on the camp, and removal
 * takes away only blocks that are still exactly what was placed. Runtime timers only; nothing here is persisted.
 */
final class CampWorks
{
    static final long RETRY_TICKS = 200L;
    /**
     * Blocks are taken down only when no player is this close (the camp "packs up" out of sight); until then an ended
     * camp's campfire is put out, so it reads as abandoned.
     */
    static final double REMOVAL_PLAYER_DISTANCE = 64.0D;
    static final int ROAD_CLEARANCE = 3;

    /** A camp about to materialize waits at most this long for its structure (then its bandits appear anyway). */
    static final long MATERIALIZATION_WAIT_TICKS = 400L;
    /** While a player waits at an unbuilt camp, the site is checked at most this often. */
    static final long READY_RETRY_TICKS = 40L;

    private final Map<UUID, Long> nextAttempt = new HashMap<>();
    private final Map<UUID, Long> waitingSince = new HashMap<>();

    void clear()
    {
        nextAttempt.clear();
        waitingSince.clear();
    }

    /**
     * Called before a camp's bandits materialize near a player: an unbuilt camp is built first, so a player who arrives
     * suddenly (teleport, respawn) still finds the tents before the fight. Returns false while the camp's chunks are not
     * ready yet (materialization waits a cycle), but never longer than {@link #MATERIALIZATION_WAIT_TICKS}.
     */
    boolean readyToMaterialize(final KingdomsSavedData data, final BanditEncounter encounter, final ServerLevel level, final long gameTime,
        final BanditSettings settings)
    {
        if (encounter.kind() != BanditEncounter.Kind.CAMP || !settings.camps().structures()) return true;
        final BanditCamp camp = data.bandits().campByEncounter(encounter.id()).orElse(null);
        if (camp == null || !camp.active() || camp.structure() != BanditCamp.Structure.NONE)
        {
            if (camp != null) waitingSince.remove(camp.id());
            return true;
        }
        if (nextAttempt.getOrDefault(camp.id(), Long.MIN_VALUE) <= gameTime)
        {
            nextAttempt.put(camp.id(), gameTime + READY_RETRY_TICKS);
            build(data, camp, level, gameTime, settings, false);
        }
        if (camp.structure() != BanditCamp.Structure.NONE)
        {
            waitingSince.remove(camp.id());
            return true;
        }
        final long since = waitingSince.computeIfAbsent(camp.id(), id -> gameTime);
        if (gameTime - since < MATERIALIZATION_WAIT_TICKS) return false;
        waitingSince.remove(camp.id());
        return true;
    }

    /** One bounded step: the first due camp to build or take down. Returns a short description of what happened, if anything. */
    Optional<String> update(final KingdomsSavedData data, final long gameTime, final BanditSettings settings,
        final Function<ResourceLocation, ServerLevel> levels)
    {
        nextAttempt.values().removeIf(at -> at <= gameTime - 72_000L);
        waitingSince.values().removeIf(since -> since <= gameTime - 72_000L);
        for (final BanditCamp camp : List.copyOf(data.bandits().camps()))
        {
            if (nextAttempt.getOrDefault(camp.id(), Long.MIN_VALUE) > gameTime) continue;
            final ServerLevel level = levels.apply(camp.dimension());
            if (level == null) continue;
            if (!camp.active() && camp.structure() == BanditCamp.Structure.BUILT)
            {
                nextAttempt.put(camp.id(), gameTime + RETRY_TICKS);
                if (remove(data, camp, level, gameTime, false)) return Optional.of("took down camp " + camp.id());
                continue;
            }
            if (camp.active() && camp.structure() == BanditCamp.Structure.NONE && settings.camps().structures())
            {
                nextAttempt.put(camp.id(), gameTime + RETRY_TICKS);
                final Optional<String> built = build(data, camp, level, gameTime, settings, false);
                if (built.isPresent()) return built;
            }
        }
        return Optional.empty();
    }

    /**
     * Tries the camp's sites in their fixed order, from its current one. Returns what happened if the camp was built or
     * found unbuildable; empty if it must wait (chunks not loaded, or its fight is physical right now). {@code operator}
     * waives the chunk-ownership rule (every other check still applies).
     */
    Optional<String> build(final KingdomsSavedData data, final BanditCamp camp, final ServerLevel level, final long gameTime,
        final BanditSettings settings, final boolean operator)
    {
        if (camp.structure() == BanditCamp.Structure.BUILT) return Optional.empty();
        final BanditEncounter encounter = data.bandits().encounter(camp.encounterId()).orElse(null);
        if (encounter == null || !encounter.open() || encounter.representation() != BanditEncounter.Representation.ABSTRACT)
            return Optional.empty();
        String lastReason = "no site";
        for (int step = 0; step < camp.sites().size(); step++)
        {
            final int index = (camp.siteIndex() + step) % camp.sites().size();
            final BlockPos site = camp.sites().get(index);
            if (!CampPlanner.withinRelocation(camp.sites().getFirst(), site)) continue;
            final CampSite.View view = view(data, level, settings, operator);
            final CampSite.Verdict verdict = CampSite.check(site, view);
            if (verdict.retry()) return Optional.empty();
            if (!verdict.valid())
            {
                lastReason = verdict.reason();
                continue;
            }
            // never build around a player standing on the site; try again when they have moved
            final net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(site).inflate(CampSite.CLEARANCE,
                CampSite.MAX_ROAD_HEIGHT_DIFFERENCE + CampSite.HEADROOM + 2, CampSite.CLEARANCE);
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, area).isEmpty()) return Optional.empty();
            if (!CampService.useSite(data, camp, index)) return Optional.empty();
            final List<BanditCamp.PlacedBlock> placed = new ArrayList<>();
            for (final CampSite.Placement placement : CampSite.placements(site, camp.seed(), view))
            {
                if (placed.size() >= BanditCamp.MAX_PLACED) break;
                if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new net.minecraft.world.phys.AABB(placement.position())).isEmpty()) continue; // never inside an animal
                final Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(placement.blockId()));
                if (block == Blocks.AIR) continue;
                if (level.setBlock(placement.position(), block.defaultBlockState(), Block.UPDATE_ALL))
                    placed.add(new BanditCamp.PlacedBlock(placement.position(), placement.blockId()));
            }
            CampService.built(data, camp, placed, gameTime);
            KingdomsMod.LOGGER.info("Bandit camp {} built at {} ({} blocks)", camp.id(), site.toShortString(), placed.size());
            return Optional.of("built camp " + camp.id() + " at " + site.toShortString() + " (" + placed.size() + " blocks)");
        }
        CampService.unbuildable(data, camp, lastReason, gameTime);
        KingdomsMod.LOGGER.info("Bandit camp {} has no buildable site ({}); its bandits still gather there", camp.id(), lastReason);
        return Optional.of("camp " + camp.id() + " unbuildable: " + lastReason);
    }

    /** Takes down the blocks that are still exactly what the camp placed. {@code operator} ignores nearby players. */
    boolean remove(final KingdomsSavedData data, final BanditCamp camp, final ServerLevel level, final long gameTime, final boolean operator)
    {
        if (camp.structure() != BanditCamp.Structure.BUILT) return false;
        for (final BanditCamp.PlacedBlock block : camp.placed())
            if (!level.isPositionEntityTicking(block.position())) return false;
        if (!operator)
        {
            final Vec3 centre = Vec3.atCenterOf(camp.position());
            final double distance = removalDistance(level);
            if (level.players().stream().anyMatch(player -> player.distanceToSqr(centre) < distance * distance))
            {
                extinguish(camp, level);
                return false;
            }
        }
        int taken = 0;
        final List<BanditCamp.PlacedBlock> placed = camp.placed();
        for (final BanditCamp.PlacedBlock block : placed)
        {
            final BlockState state = level.getBlockState(block.position());
            if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block.blockId())
                && level.setBlock(block.position(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) taken++;
        }
        CampService.removed(data, camp, gameTime);
        KingdomsMod.LOGGER.info("Bandit camp {} taken down ({} of {} blocks were still there)", camp.id(), taken, placed.size());
        return true;
    }

    /**
     * How far players must be for the blocks to go: {@link #REMOVAL_PLAYER_DISTANCE}, but always inside the simulation
     * distance (the chunks must tick to be edited), so a small simulation distance never keeps an ended camp forever.
     */
    static double removalDistance(final ServerLevel level)
    {
        final int simulation = level.getServer().getPlayerList().getSimulationDistance();
        return Math.max(24.0D, Math.min(REMOVAL_PLAYER_DISTANCE, simulation * 16.0D - 32.0D));
    }

    /** Puts out the ended camp's campfire (only a campfire it placed and that is still there). */
    private static void extinguish(final BanditCamp camp, final ServerLevel level)
    {
        for (final BanditCamp.PlacedBlock block : camp.placed())
        {
            if (!"minecraft:campfire".equals(block.blockId())) continue;
            final BlockState state = level.getBlockState(block.position());
            if (state.is(Blocks.CAMPFIRE) && state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT))
                level.setBlock(block.position(), state.setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false), Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------------------------------------ world view

    private static CampSite.View view(final KingdomsSavedData data, final ServerLevel level, final BanditSettings settings,
        final boolean operator)
    {
        final long maxInhabited = settings.camps().maxInhabitedTicks();
        final Map<Long, List<BlockPos>> roadPoints = new HashMap<>();
        return new CampSite.View()
        {
            @Override
            public boolean ticking(final int x, final int z)
            {
                return level.isPositionEntityTicking(new BlockPos(x, level.getMinBuildHeight(), z))
                    && level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
            }

            @Override
            public boolean owned(final int chunkX, final int chunkZ)
            {
                // players' land is decided by the time players spent there, also in chunks generated this session (a new
                // world's fresh chunks can hold a new base); only the explicit operator command waives it
                if (operator) return true;
                final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                return chunk != null && chunk.getInhabitedTime() < maxInhabited;
            }

            @Override
            public int surface(final int x, final int z)
            {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                return chunk == null ? level.getMinBuildHeight() : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
            }

            @Override
            public boolean naturalGround(final int x, final int y, final int z)
            {
                final BlockState state = level.getBlockState(new BlockPos(x, y, z));
                return state.getFluidState().isEmpty() && (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                    || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL) || state.is(Blocks.SNOW_BLOCK)
                    || state.is(Blocks.CLAY));
            }

            @Override
            public boolean replaceable(final int x, final int y, final int z)
            {
                final BlockState state = level.getBlockState(new BlockPos(x, y, z));
                return state.getFluidState().isEmpty() && (state.isAir() || state.canBeReplaced() || state.is(BlockTags.SMALL_FLOWERS));
            }

            @Override
            public boolean blockEntity(final int x, final int y, final int z)
            {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                return chunk == null || chunk.getBlockEntities().containsKey(new BlockPos(x, y, z));
            }

            @Override
            public boolean road(final int x, final int z)
            {
                final List<BlockPos> points = roadPoints.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), key -> nearbyRoadPoints(data, x >> 4, z >> 4));
                for (final BlockPos point : points)
                {
                    final int dx = point.getX() - x;
                    final int dz = point.getZ() - z;
                    if (dx * dx + dz * dz <= ROAD_CLEARANCE * ROAD_CLEARANCE) return true;
                }
                return false;
            }
        };
    }

    /** Road geometry points of every road indexed in this chunk or its neighbours, limited to this chunk plus a margin. */
    private static List<BlockPos> nearbyRoadPoints(final KingdomsSavedData data, final int chunkX, final int chunkZ)
    {
        final List<BlockPos> points = new ArrayList<>();
        final int minX = (chunkX << 4) - ROAD_CLEARANCE - 16;
        final int maxX = (chunkX << 4) + 15 + ROAD_CLEARANCE + 16;
        final int minZ = (chunkZ << 4) - ROAD_CLEARANCE - 16;
        final int maxZ = (chunkZ << 4) + 15 + ROAD_CLEARANCE + 16;
        final java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (final RoadRecord road : data.roads().inChunk(new ChunkPos(chunkX + dx, chunkZ + dz)))
                {
                    if (!seen.add(road.id())) continue;
                    for (final RoadGeometryPoint point : road.geometry().points())
                    {
                        final BlockPos position = point.position();
                        if (position.getX() >= minX && position.getX() <= maxX && position.getZ() >= minZ && position.getZ() <= maxZ)
                            points.add(position);
                    }
                }
        return points;
    }
}
