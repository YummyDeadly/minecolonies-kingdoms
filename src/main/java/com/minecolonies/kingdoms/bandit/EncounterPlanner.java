package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryKind;
import com.minecolonies.kingdoms.world.road.geometry.RoadGeometryPoint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Where bandits may appear: only on real roads, at sampled points outside every settlement's exclusion radius and
 * away from bridges. Sampling is bounded (a fixed number of candidates per road), never per block.
 */
public final class EncounterPlanner
{
    /** Candidate fractions along a road (both 10% ends are never used). */
    public static final int CANDIDATES = 9;
    public static final double BRIDGE_CLEARANCE = 12.0D;
    /** An ambush point must lie at least this far ahead of the caravan when it is planned. */
    public static final double MINIMUM_LEAD = 32.0D;

    private EncounterPlanner() {}

    /**
     * Everything bandits keep away from, as horizontal points: every settlement anchor and every tracked colony centre,
     * including a player's own MineColonies colony that happens to lie next to a road.
     */
    public static List<Vec3> exclusionAnchors(final com.minecolonies.kingdoms.persistence.KingdomsSavedData data)
    {
        final List<Vec3> anchors = new ArrayList<>();
        data.settlements().records().forEach(settlement -> anchors.add(Vec3.atCenterOf(settlement.anchor())));
        data.colonies().forEach(colony -> anchors.add(Vec3.atCenterOf(colony.center())));
        return anchors;
    }

    public static boolean outsideSettlements(final Vec3 point, final List<Vec3> settlementAnchors, final int exclusionRadius)
    {
        final double limit = (double) exclusionRadius * exclusionRadius;
        for (final Vec3 anchor : settlementAnchors)
        {
            final double dx = point.x - anchor.x;
            final double dz = point.z - anchor.z;
            if (dx * dx + dz * dz < limit) return false;
        }
        return true;
    }

    /**
     * Ambush point along one road of a shipment's path, as a distance along the whole path, or empty if the road has
     * no eligible remote point ahead of the caravan. The choice among eligible points is fixed by the seed.
     */
    public static OptionalDouble ambushDistance(final RoadShipmentPath path, final RoadShipmentPath.RoadSpan span,
        final double currentDistance, final List<Vec3> settlementAnchors, final int exclusionRadius, final long seed)
    {
        final List<Double> eligible = new ArrayList<>();
        for (int index = 1; index <= CANDIDATES; index++)
        {
            final double distance = span.start() + span.length() * index / (CANDIDATES + 1.0D);
            if (distance < currentDistance + MINIMUM_LEAD) continue;
            final Vec3 point = path.positionAt(path.length() <= 0.0D ? 0.0D : distance / path.length());
            if (!outsideSettlements(point, settlementAnchors, exclusionRadius)) continue;
            if (path.bridgeNear(distance, BRIDGE_CLEARANCE)) continue;
            eligible.add(distance);
        }
        if (eligible.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(eligible.get((int) Math.floor(EncounterRules.unit(seed, EncounterRules.SALT_POINT) * eligible.size())));
    }

    /** A remote, non-bridge point on a road for a roadblock, or empty. */
    public static Optional<BlockPos> roadblockPoint(final RoadRecord road, final List<Vec3> settlementAnchors, final int exclusionRadius,
        final long seed)
    {
        final List<RoadGeometryPoint> points = road.geometry().points();
        if (points.size() < 2) return Optional.empty();
        final List<BlockPos> eligible = new ArrayList<>();
        for (int index = 1; index <= CANDIDATES; index++)
        {
            final int at = (int) Math.round((points.size() - 1) * index / (CANDIDATES + 1.0D));
            final RoadGeometryPoint point = points.get(at);
            if (!outsideSettlements(Vec3.atCenterOf(point.position()), settlementAnchors, exclusionRadius)) continue;
            if (bridgeAround(points, at, 4)) continue;
            eligible.add(point.position());
        }
        if (eligible.isEmpty()) return Optional.empty();
        return Optional.of(eligible.get((int) Math.floor(EncounterRules.unit(seed, EncounterRules.SALT_POINT) * eligible.size())));
    }

    /** Length of a road that lies outside all settlement exclusion zones, sampled at the candidate points. */
    public static double remoteLength(final RoadRecord road, final List<Vec3> settlementAnchors, final int exclusionRadius)
    {
        final List<RoadGeometryPoint> points = road.geometry().points();
        if (points.size() < 2) return 0.0D;
        int remote = 0;
        for (int index = 1; index <= CANDIDATES; index++)
        {
            final int at = (int) Math.round((points.size() - 1) * index / (CANDIDATES + 1.0D));
            if (outsideSettlements(Vec3.atCenterOf(points.get(at).position()), settlementAnchors, exclusionRadius)) remote++;
        }
        return road.length() * remote / CANDIDATES;
    }

    private static boolean bridgeAround(final List<RoadGeometryPoint> points, final int index, final int radius)
    {
        for (int at = Math.max(0, index - radius); at <= Math.min(points.size() - 1, index + radius); at++)
            if (points.get(at).kind() == RoadGeometryKind.BRIDGE) return true;
        return false;
    }
}
