package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SettlementPlotPlanner
{
    public static final int GENERATION_VERSION = 1;
    private static final int MAX_CANDIDATES = 48;

    public Optional<SettlementBuildingRecord> plan(final SettlementRecord settlement,
        final SettlementBuildingType type, final int sequence, final int maximumExpansionRadius,
        final TerrainSampler terrain, final List<SettlementBuildingRecord> existing, final RoadNetwork roads,
        final long gameTime)
    {
        final int plotRadius = Math.max(type.halfWidth(), type.halfDepth());
        final int minimumRadius = settlement.type().footprintRadius() + plotRadius + 8;
        if (maximumExpansionRadius < minimumRadius) return Optional.empty();
        final long salt = mix(settlement.id().getMostSignificantBits() ^ settlement.id().getLeastSignificantBits()
            ^ ((long) sequence << 32) ^ type.ordinal());
        final int angleOffset = Math.floorMod((int) salt, 16);
        final List<Candidate> candidates = new ArrayList<>();
        int sampled = 0;
        for (int radius = minimumRadius; radius <= maximumExpansionRadius && sampled < MAX_CANDIDATES; radius += 12)
        {
            for (int step = 0; step < 16 && sampled < MAX_CANDIDATES; step++, sampled++)
            {
                final double angle = (Math.floorMod(step + angleOffset, 16) * Math.PI * 2.0D) / 16.0D;
                final int x = settlement.anchor().getX() + (int) Math.round(Math.cos(angle) * radius);
                final int z = settlement.anchor().getZ() + (int) Math.round(Math.sin(angle) * radius);
                final TerrainSample center = terrain.sample(x, z);
                if (!center.allowedBiome() || center.water()) continue;
                final int[][] samples = {{-type.halfWidth(),-type.halfDepth()},{type.halfWidth(),-type.halfDepth()},
                    {-type.halfWidth(),type.halfDepth()},{type.halfWidth(),type.halfDepth()}};
                int roughness = 0;
                boolean invalid = false;
                for (final int[] offset : samples)
                {
                    final TerrainSample point = terrain.sample(x + offset[0], z + offset[1]);
                    if (!point.allowedBiome() || point.water()) { invalid = true; break; }
                    roughness = Math.max(roughness, Math.abs(point.height() - center.height()));
                }
                if (invalid || roughness > 6) continue;
                final BlockPos anchor = new BlockPos(x, center.height(), z);
                if (reserved(settlement, anchor, plotRadius, existing, roads)) continue;
                candidates.add(new Candidate(anchor, roughness, mix(salt ^ BlockPos.asLong(x, center.height(), z))));
            }
        }
        return candidates.stream().min(Candidate.ORDER).map(candidate -> new SettlementBuildingRecord(
            stableBuildingId(settlement.id(), sequence, type, GENERATION_VERSION), settlement.id(), type,
            candidate.anchor(), Math.floorMod((int) (candidate.tieBreaker() >>> 32), 4) * 90,
            sequence, GENERATION_VERSION, gameTime, SettlementBuildingStatus.PLANNED));
    }

    public static UUID stableBuildingId(final UUID settlementId, final int sequence,
        final SettlementBuildingType type, final int generationVersion)
    {
        return UUID.nameUUIDFromBytes(("building:" + settlementId + ':' + sequence + ':' + type.name() + ':' + generationVersion)
            .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean reserved(final SettlementRecord settlement, final BlockPos candidate, final int radius,
        final List<SettlementBuildingRecord> existing, final RoadNetwork roads)
    {
        if (horizontalDistance(candidate, settlement.anchor()) <= settlement.type().footprintRadius() + radius + 4.0D) return true;
        if (horizontalDistance(candidate, settlement.gate()) <= radius + 10.0D) return true;
        if (distanceToSegment(candidate, settlement.anchor(), settlement.gate()) <= radius + 4.0D) return true;
        for (final SettlementBuildingRecord building : existing.stream().sorted(Comparator.comparing(SettlementBuildingRecord::id)).toList())
        {
            final int otherRadius = Math.max(building.type().halfWidth(), building.type().halfDepth());
            if (horizontalDistance(candidate, building.anchor()) <= radius + otherRadius + 5.0D) return true;
        }
        for (final RoadRecord road : roads.roads().stream().sorted(Comparator.comparing(RoadRecord::id)).toList())
        {
            if (!road.dimension().equals(settlement.dimension())) continue;
            if (horizontalDistance(candidate, road.polyline().getFirst()) <= radius + road.type().width() + 4.0D
                || horizontalDistance(candidate, road.polyline().getLast()) <= radius + road.type().width() + 4.0D) return true;
            for (int index = 1; index < road.polyline().size(); index++)
                if (distanceToSegment(candidate, road.polyline().get(index - 1), road.polyline().get(index))
                    <= radius + road.type().width() + 3.0D) return true;
        }
        return false;
    }

    static double distanceToSegment(final BlockPos point, final BlockPos start, final BlockPos end)
    {
        final double dx = end.getX() - start.getX();
        final double dz = end.getZ() - start.getZ();
        final double lengthSquared = dx * dx + dz * dz;
        final double t = lengthSquared == 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D,
            ((point.getX() - start.getX()) * dx + (point.getZ() - start.getZ()) * dz) / lengthSquared));
        final double x = start.getX() + dx * t;
        final double z = start.getZ() + dz * t;
        final double px = point.getX() - x;
        final double pz = point.getZ() - z;
        return Math.sqrt(px * px + pz * pz);
    }

    private static double horizontalDistance(final BlockPos left, final BlockPos right)
    {
        final double dx = left.getX() - right.getX();
        final double dz = left.getZ() - right.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static long mix(long value)
    {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record Candidate(BlockPos anchor, int roughness, long tieBreaker)
    {
        private static final Comparator<Candidate> ORDER = Comparator.comparingInt(Candidate::roughness)
            .thenComparingLong(Candidate::tieBreaker).thenComparingInt(value -> value.anchor().getX())
            .thenComparingInt(value -> value.anchor().getZ());
    }
}
