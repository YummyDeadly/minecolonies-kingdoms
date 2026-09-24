package com.minecolonies.kingdoms.bandit;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical rules for a camp's small structure (Phase 8.1), pure so they have unit tests. A site passes only when all
 * of the following hold for its {@value #SIDE}x{@value #SIDE} footprint:
 * <ul>
 *   <li>every column is in an entity-ticking chunk (nothing is ever loaded for a camp; otherwise: try later);</li>
 *   <li>every chunk is owned: players have spent little time there (inhabited time), so a player's base is never
 *       touched — also in chunks generated this session; only the explicit operator command waives this;</li>
 *   <li>the ground is natural (soil, sand, gravel, stone, snow), dry, and level within {@value #MAX_STEP} blocks, near
 *       the road's height;</li>
 *   <li>the four blocks above the ground are air or replaceable plants (no trees, no builds, no water);</li>
 *   <li>no block entity within {@value #CLEARANCE} blocks (chests, spawners, beds, anything a player may own);</li>
 *   <li>no road surface in the footprint.</li>
 * </ul>
 * The layout is about twenty blocks: a lit campfire, two small wool tents, and a few hay/log "crates". Nothing in it
 * holds items, so a camp never hands out loot; rewards come only from contracts.
 */
public final class CampSite
{
    public static final int RADIUS = 4;
    public static final int SIDE = RADIUS * 2 + 1;
    public static final int CLEARANCE = RADIUS + 1;
    public static final int MAX_STEP = 2;
    public static final int MAX_ROAD_HEIGHT_DIFFERENCE = 8;
    static final int HEADROOM = 4;

    private CampSite() {}

    /** Read-only world access; implementations must never load a chunk. */
    public interface View
    {
        /** The column is in an entity-ticking chunk. */
        boolean ticking(int x, int z);

        /** Kingdoms may place blocks in this chunk (see the class comment). */
        boolean owned(int chunkX, int chunkZ);

        /** First free y above the ground at a column (motion-blocking, leaves ignored). */
        int surface(int x, int z);

        /** Natural, dry ground block (not a fluid, not a player-made block). */
        boolean naturalGround(int x, int y, int z);

        /** Air or a replaceable plant/snow layer, without fluid. */
        boolean replaceable(int x, int y, int z);

        boolean blockEntity(int x, int y, int z);

        /** A road surface (any road's geometry) lies at this column. */
        boolean road(int x, int z);
    }

    /** {@code retry}: the site could not be judged yet (chunks not loaded); try again later instead of giving up. */
    public record Verdict(boolean valid, boolean retry, String reason)
    {
        static final Verdict OK = new Verdict(true, false, "");
        static Verdict retry(final String reason) { return new Verdict(false, true, reason); }
        static Verdict invalid(final String reason) { return new Verdict(false, false, reason); }
    }

    /** One block of the layout: offsets from the site centre (x/z) and from the local ground (y). */
    public record Part(int dx, int dy, int dz, String blockId) {}

    /** One placement: absolute position and block id. */
    public record Placement(BlockPos position, String blockId) {}

    public static Verdict check(final BlockPos site, final View view)
    {
        for (int dx = -CLEARANCE; dx <= CLEARANCE; dx++)
            for (int dz = -CLEARANCE; dz <= CLEARANCE; dz++)
                if (!view.ticking(site.getX() + dx, site.getZ() + dz)) return Verdict.retry("chunks not loaded");
        for (int cx = (site.getX() - RADIUS) >> 4; cx <= (site.getX() + RADIUS) >> 4; cx++)
            for (int cz = (site.getZ() - RADIUS) >> 4; cz <= (site.getZ() + RADIUS) >> 4; cz++)
                if (!view.owned(cx, cz)) return Verdict.invalid("existing chunk " + cx + "," + cz + " (players have been here)");
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++)
            {
                final int x = site.getX() + dx;
                final int z = site.getZ() + dz;
                final int y = view.surface(x, z);
                low = Math.min(low, y);
                high = Math.max(high, y);
                if (high - low > MAX_STEP) return Verdict.invalid("uneven ground");
                if (view.road(x, z)) return Verdict.invalid("road surface");
                if (!view.naturalGround(x, y - 1, z)) return Verdict.invalid("not natural dry ground at " + x + "," + (y - 1) + "," + z);
                for (int up = 0; up < HEADROOM; up++)
                    if (!view.replaceable(x, y + up, z)) return Verdict.invalid("obstructed at " + x + "," + (y + up) + "," + z);
            }
        final int centre = view.surface(site.getX(), site.getZ());
        if (Math.abs(centre - site.getY()) > MAX_ROAD_HEIGHT_DIFFERENCE) return Verdict.invalid("far above or below the road");
        for (int dx = -CLEARANCE; dx <= CLEARANCE; dx++)
            for (int dz = -CLEARANCE; dz <= CLEARANCE; dz++)
                for (int dy = -2; dy <= HEADROOM; dy++)
                    if (view.blockEntity(site.getX() + dx, centre + dy, site.getZ() + dz)) return Verdict.invalid("block entity nearby");
        return Verdict.OK;
    }

    /** The camp layout, rotated by the seed (tents east-west or north-south). */
    public static List<Part> layout(final long seed)
    {
        final boolean turned = EncounterRules.unit(seed, 31L) < 0.5D;
        final List<Part> parts = new ArrayList<>();
        parts.add(part(0, 0, 0, "minecraft:campfire", turned));
        for (final int side : new int[] {-1, 1})
        {
            final int centre = 3 * side;
            for (int along = -1; along <= 1; along++)
            {
                parts.add(part(centre - 1, 0, along, "minecraft:brown_wool", turned));
                parts.add(part(centre + 1, 0, along, "minecraft:brown_wool", turned));
                parts.add(part(centre, 1, along, "minecraft:white_wool", turned));
            }
        }
        parts.add(part(1, 0, 3, "minecraft:hay_block", turned));
        parts.add(part(-1, 0, 3, "minecraft:spruce_log", turned));
        parts.add(part(0, 0, -3, "minecraft:hay_block", turned));
        return List.copyOf(parts);
    }

    /** Absolute placements following the local ground; only cells that are still replaceable are used. */
    public static List<Placement> placements(final BlockPos site, final long seed, final View view)
    {
        final List<Placement> placements = new ArrayList<>();
        for (final Part part : layout(seed))
        {
            final int x = site.getX() + part.dx();
            final int z = site.getZ() + part.dz();
            final int y = view.surface(x, z) + part.dy();
            if (view.replaceable(x, y, z)) placements.add(new Placement(new BlockPos(x, y, z), part.blockId()));
        }
        return placements;
    }

    private static Part part(final int dx, final int dy, final int dz, final String block, final boolean turned)
    {
        return turned ? new Part(dz, dy, dx, block) : new Part(dx, dy, dz, block);
    }
}
