package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where a bandit camp may stand: beside a real road, off the carriageway, in its remote part. Pure and bounded: at
 * most {@link EncounterPlanner#CANDIDATES} road points are considered, two sides each, and at most {@value #MAX_SITES}
 * sites are kept. A candidate is rejected if the road point is not plain ground (graded cuttings/embankments and
 * bridges), lies within four points of a bridge, inside a settlement's exclusion zone plus a margin, or near another camp. The physical check (terrain,
 * water, trees, block entities, chunk ownership) happens later, when the site's chunks are loaded; the camp then uses
 * the first site that passes, in this fixed order.
 */
public final class CampPlanner
{
    public static final int MAX_SITES = 6;
    /** Camp centre distance from the road centre line. */
    public static final int OFFSET = 14;
    /** Extra distance beyond a settlement's bandit exclusion radius. */
    public static final int SETTLEMENT_MARGIN = 32;
    /** Minimum distance between two active camps. */
    public static final int CAMP_SPACING = 256;
    /**
     * A camp may settle on another candidate site only within this distance of its first site: contracts and guard
     * reports point at the first site, so a camp never moves out of the area they describe.
     */
    public static final int RELOCATION_RADIUS = 48;

    /** Whether a camp whose first site is {@code first} may settle on {@code candidate}. */
    public static boolean withinRelocation(final BlockPos first, final BlockPos candidate)
    {
        return horizontalDistanceSqr(first, candidate) <= (double) RELOCATION_RADIUS * RELOCATION_RADIUS;
    }

    private CampPlanner() {}

    public static UUID campId(final UUID roadId, final int ordinal)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-camp:" + roadId + ':' + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID encounterId(final UUID campId)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-camp-encounter:" + campId).getBytes(StandardCharsets.UTF_8));
    }

    /** Candidate sites in the fixed order the camp tries them; empty when the road has no remote, plain stretch. */
    public static List<BlockPos> sites(final RoadRecord road, final List<Vec3> exclusionAnchors, final int exclusionRadius,
        final List<BlockPos> otherCamps, final long seed)
    {
        final List<RoadGeometryPoint> points = road.geometry().points();
        final List<BlockPos> sites = new ArrayList<>();
        if (points.size() < 3) return sites;
        final int count = EncounterPlanner.CANDIDATES;
        final int start = (int) Math.floor(EncounterRules.unit(seed, 11L) * count);
        for (int step = 0; step < count && sites.size() < MAX_SITES; step++)
        {
            final int k = 1 + Math.floorMod(start + step, count);
            final int at = (int) Math.round((points.size() - 1) * k / (count + 1.0D));
            if (at <= 0 || at >= points.size() - 1) continue;
            final RoadGeometryPoint point = points.get(at);
            if (point.kind() != RoadGeometryKind.GROUND || kindAround(points, at, 4)) continue;
            final BlockPos before = points.get(at - 1).position();
            final BlockPos after = points.get(at + 1).position();
            final double dx = after.getX() - before.getX();
            final double dz = after.getZ() - before.getZ();
            final double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0E-6D) continue;
            final boolean left = EncounterRules.unit(seed, 20L + k) < 0.5D;
            for (final int side : left ? new int[] {1, -1} : new int[] {-1, 1})
            {
                final BlockPos site = new BlockPos(point.position().getX() + (int) Math.round(-dz / length * OFFSET * side),
                    point.position().getY(), point.position().getZ() + (int) Math.round(dx / length * OFFSET * side));
                if (!EncounterPlanner.outsideSettlements(Vec3.atCenterOf(site), exclusionAnchors, exclusionRadius + SETTLEMENT_MARGIN))
                    continue;
                if (otherCamps.stream().anyMatch(camp -> horizontalDistanceSqr(camp, site) < (double) CAMP_SPACING * CAMP_SPACING)) continue;
                if (nearRoadPoint(points, at, site, OFFSET - 4)) continue;
                sites.add(site);
                if (sites.size() >= MAX_SITES) break;
            }
        }
        return sites;
    }

    /** Whether a point within {@code radius} indices of {@code index} is a bridge or graded (cut/fill) point. */
    static boolean kindAround(final List<RoadGeometryPoint> points, final int index, final int radius)
    {
        for (int at = Math.max(0, index - radius); at <= Math.min(points.size() - 1, index + radius); at++)
            if (points.get(at).kind() == RoadGeometryKind.BRIDGE) return true;
        return false;
    }

    /** Whether the site lies closer than {@code clearance} to the road near {@code index} (a bend can bring it back). */
    static boolean nearRoadPoint(final List<RoadGeometryPoint> points, final int index, final BlockPos site, final int clearance)
    {
        for (int at = Math.max(0, index - 6); at <= Math.min(points.size() - 1, index + 6); at++)
            if (horizontalDistanceSqr(points.get(at).position(), site) < (double) clearance * clearance) return true;
        return false;
    }

    static double horizontalDistanceSqr(final BlockPos a, final BlockPos b)
    {
        final double dx = a.getX() - b.getX();
        final double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
