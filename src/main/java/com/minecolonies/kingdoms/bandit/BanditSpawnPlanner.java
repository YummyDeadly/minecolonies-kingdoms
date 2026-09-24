package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.citizen.SafeSpawnFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where the bandits of one materialization stand. The search is pure and bounded: at most
 * {@value #ATTEMPTS_PER_BANDIT} seeded probes per bandit on a ring 5-10 blocks around the encounter, each a bounded
 * {@link SafeSpawnFinder} search (dry sturdy floor, two open blocks, loaded chunk only; it never loads a chunk). A
 * probe is rejected if it is more than {@value #MAX_VERTICAL_OFFSET} blocks above or below the road, indoors (the
 * {@code outdoors} test, sky light at least {@value #MIN_SKY_LIGHT} in the game), inside a settlement's exclusion zone,
 * or next to a player. Fewer placements than requested is normal; none at all means the
 * encounter stays abstract.
 */
final class BanditSpawnPlanner
{
    static final int ATTEMPTS_PER_BANDIT = 3;
    static final int MAX_VERTICAL_OFFSET = 8;
    static final double PLAYER_CLEARANCE = 5.0D;
    static final double MIN_RING = 5.0D;
    static final double RING_WIDTH = 5.0D;
    /** Outdoors: open to the sky, or under light cover such as a tree canopy; never inside a roofed building or a cave. */
    static final int MIN_SKY_LIGHT = 10;

    record Placement(BlockPos position, float yaw) {}

    private BanditSpawnPlanner() {}

    static List<Placement> place(final long seed, final BlockPos base, final int count, final int spawnedEver,
        final SafeSpawnFinder.BlockView view, final java.util.function.Predicate<BlockPos> outdoors, final List<Vec3> settlementAnchors,
        final int exclusionRadius, final List<Vec3> players)
    {
        final List<Placement> placements = new ArrayList<>();
        if (count <= 0) return placements;
        final Set<BlockPos> used = new HashSet<>();
        for (int attempt = 0; attempt < count * ATTEMPTS_PER_BANDIT && placements.size() < count; attempt++)
        {
            final double angle = EncounterRules.unit(seed, 100L + attempt + spawnedEver * 7L) * Math.PI * 2.0D;
            final double radius = MIN_RING + EncounterRules.unit(seed, 200L + attempt) * RING_WIDTH;
            final BlockPos probe = base.offset((int) Math.round(Math.cos(angle) * radius), 0, (int) Math.round(Math.sin(angle) * radius));
            final Optional<BlockPos> found = SafeSpawnFinder.find(probe, 2, 6, view, List.of());
            if (found.isEmpty()) continue;
            final BlockPos position = found.get();
            if (Math.abs(position.getY() - base.getY()) > MAX_VERTICAL_OFFSET || !used.add(position) || !outdoors.test(position)) continue;
            final Vec3 center = Vec3.atBottomCenterOf(position);
            if (!EncounterPlanner.outsideSettlements(center, settlementAnchors, exclusionRadius)) continue;
            if (players.stream().anyMatch(player -> player.distanceToSqr(center) < PLAYER_CLEARANCE * PLAYER_CLEARANCE)) continue;
            placements.add(new Placement(position, (float) (angle * 180.0D / Math.PI)));
        }
        return placements;
    }
}
