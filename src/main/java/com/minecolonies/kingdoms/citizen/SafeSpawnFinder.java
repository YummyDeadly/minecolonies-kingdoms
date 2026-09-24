package com.minecolonies.kingdoms.citizen;

import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

/**
 * Bounded search for a position a humanoid can stand on: a sturdy, dry floor, two free non-fluid blocks, in a loaded
 * chunk, outside every building footprint (so never inside walls, roofs, or blueprint interiors). The search never
 * loads chunks; if nothing qualifies the caller simply does not spawn this cycle.
 */
public final class SafeSpawnFinder
{
    /** Read-only world view; implementations must not load chunks. */
    public interface BlockView
    {
        boolean loaded(int x, int z);
        /** Block at (x, y, z) is a sturdy, non-fluid, non-hazardous floor. */
        boolean floor(int x, int y, int z);
        /** Block at (x, y, z) has no collision and no fluid. */
        boolean open(int x, int y, int z);
    }

    public static final int MAX_CANDIDATES = 9 * 9 * 9;

    private SafeSpawnFinder() {}

    public static Optional<BlockPos> find(final BlockPos anchor, final int radius, final int vertical, final BlockView view,
        final List<StructureFootprint> excluded)
    {
        int checked = 0;
        for (int ring = 0; ring <= radius; ring++)
            for (int dx = -ring; dx <= ring; dx++)
                for (int dz = -ring; dz <= ring; dz++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    final int x = anchor.getX() + dx;
                    final int z = anchor.getZ() + dz;
                    if (!view.loaded(x, z) || excluded.stream().anyMatch(footprint -> footprint.contains(x, z))) continue;
                    for (int step = 0; step <= vertical * 2; step++)
                    {
                        if (++checked > MAX_CANDIDATES) return Optional.empty();
                        final int dy = (step + 1) / 2 * (step % 2 == 0 ? 1 : -1);
                        final int y = anchor.getY() + dy;
                        if (view.floor(x, y - 1, z) && view.open(x, y, z) && view.open(x, y + 1, z))
                            return Optional.of(new BlockPos(x, y, z));
                    }
                }
        return Optional.empty();
    }
}
