package com.minecolonies.kingdoms.world.settlement.layout;

import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.TerrainSample;
import com.minecolonies.kingdoms.world.settlement.TerrainSampler;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureDescriptor;
import com.minecolonies.kingdoms.world.settlement.structure.SettlementStructureSelection;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import com.minecolonies.kingdoms.world.settlement.structure.StructureTransform;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingMode;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingPlan;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingPlanner;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingSettings;
import com.minecolonies.kingdoms.world.settlement.terrain.TerrainShapingRejection;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Hierarchical, hard-bounded lot search:
 * <ol>
 *     <li>coarse ring positions around the settlement ({@link #MAX_POSITIONS});</li>
 *     <li>a five-sample prefilter per position and blueprint size;</li>
 *     <li>full-footprint terrain-shaping plans for at most {@link #MAX_CANDIDATE_PADS} pads;</li>
 *     <li>bounded local street extensions for the best {@link #MAX_STREET_CANDIDATES} pads only.</li>
 * </ol>
 * The planner never reads blocks or loads chunks; it only consumes a {@link TerrainSampler}.
 */
public final class SettlementLayoutPlanner
{
    public static final int MAX_POSITIONS = 96;
    public static final int MAX_CANDIDATE_PADS = 64;
    public static final int MAX_STREET_CANDIDATES = 8;
    public static final int TRANSFORMS_PER_POSITION = 2;
    public static final int ENOUGH_PADS = MAX_STREET_CANDIDATES;
    public static final int RING_STEP = 6;
    public static final int PLAZA_RADIUS = 3;
    public static final String PLAZA_PURPOSE = "plaza";
    public static final String ROOT_PURPOSE = "gate-to-plaza";
    /** Outward corridor kept free in front of the gate so growth never walls off the settlement entrance. */
    public static final int GATE_APPROACH_LENGTH = 32;
    public static final int GATE_APPROACH_HALF_WIDTH = 5;
    private final TerrainShapingPlanner terrainPlanner = new TerrainShapingPlanner();
    private final LocalStreetGeometryPlanner streetPlanner = new LocalStreetGeometryPlanner();
    private final Supplier<TerrainShapingSettings> shapingSettings;
    private final Supplier<LocalStreetSettings> streetSettings;

    public SettlementLayoutPlanner()
    {
        this(TerrainShapingSettings::defaults, LocalStreetSettings::defaults);
    }

    public SettlementLayoutPlanner(final Supplier<TerrainShapingSettings> shapingSettings,
        final Supplier<LocalStreetSettings> streetSettings)
    {
        this.shapingSettings = shapingSettings;
        this.streetSettings = streetSettings;
    }

    public static SettlementLayoutPlanner configured()
    {
        return new SettlementLayoutPlanner(() -> new TerrainShapingSettings(
            KingdomsConfig.SERVER.settlementMaximumCutDepth.get(),
            KingdomsConfig.SERVER.settlementMaximumFillDepth.get(),
            KingdomsConfig.SERVER.settlementMaximumPadHeightVariance.get(),
            KingdomsConfig.SERVER.settlementMaximumRetainingWallHeight.get(),
            KingdomsConfig.SERVER.settlementMaximumTerrainWorkVolume.get(), 2),
            () -> new LocalStreetSettings(10, KingdomsConfig.SERVER.localStreetMaximumSearchNodes.get(),
                KingdomsConfig.SERVER.localStreetMaximumTerrainAdjustment.get(),
                KingdomsConfig.SERVER.localStreetMaximumBridgeSpan.get(),
                KingdomsConfig.SERVER.localStreetMaximumLength.get(), 8));
    }

    /** Legacy straight-line network, used only when an old settlement without a starter district begins to grow. */
    public SettlementLayoutPlan createPlan(final SettlementRecord settlement, final String styleFamily)
    {
        final SettlementStreetNetwork streets = new SettlementStreetNetwork();
        final UUID id = stableId("street-root", settlement.id(), 0);
        streets.put(new SettlementStreetSegment(id, axisPath(settlement.gate(), settlement.anchor()), 2, ROOT_PURPOSE));
        return new SettlementLayoutPlan(settlement.id(), styleFamily, streets);
    }

    /** Plaza at the anchor plus a terrain-aware gate street. Heights are resampled from the supplied terrain. */
    public Optional<SettlementLayoutPlan> createPlan(final SettlementRecord settlement, final String styleFamily,
        final TerrainSampler terrain)
    {
        final SettlementStreetNetwork streets = new SettlementStreetNetwork();
        final BlockPos anchor = surface(settlement.anchor(), terrain);
        final BlockPos gate = surface(settlement.gate(), terrain);
        final int dx = Integer.signum(gate.getX() - anchor.getX());
        final int dz = Integer.signum(gate.getZ() - anchor.getZ());
        final boolean alongX = Math.abs(gate.getX() - anchor.getX()) >= Math.abs(gate.getZ() - anchor.getZ());
        final BlockPos plazaEdge = anchor.offset(alongX ? dx * PLAZA_RADIUS : 0, 0, alongX ? 0 : dz * PLAZA_RADIUS);
        streets.put(SettlementStreetSegment.withGeometry(stableId("street-plaza", settlement.id(), 0), List.of(
            new LocalStreetPoint(anchor, LocalStreetKind.GROUND),
            new LocalStreetPoint(anchor.offset(alongX ? dx : 0, 0, alongX ? 0 : dz), LocalStreetKind.GROUND)),
            PLAZA_RADIUS, PLAZA_PURPOSE));
        final UUID id = stableId("street-root", settlement.id(), 0);
        final var root = streetPlanner.connection(id, gate, List.of(plazaEdge), List.of(),
            null, terrain, streetSettings.get(), 2, ROOT_PURPOSE);
        if (root.isEmpty()) return Optional.empty();
        streets.put(root.orElseThrow());
        return Optional.of(new SettlementLayoutPlan(settlement.id(), styleFamily, streets));
    }

    public Optional<LayoutPlacement> plan(final SettlementRecord settlement, final UUID buildingId,
        final SettlementStructureSelection seededSelection, final SettlementLayoutPlan layout,
        final int maximumExpansionRadius, final TerrainSampler terrain, final RoadNetwork globalRoads)
    {
        return plan(settlement, buildingId, List.of(seededSelection), layout, maximumExpansionRadius, terrain, globalRoads);
    }

    public Optional<LayoutPlacement> plan(final SettlementRecord settlement, final UUID buildingId,
        final List<SettlementStructureSelection> seededSelections, final SettlementLayoutPlan layout,
        final int maximumExpansionRadius, final TerrainSampler terrain, final RoadNetwork globalRoads)
    {
        final int largestHalfSize = seededSelections.stream().map(SettlementStructureSelection::descriptor)
            .mapToInt(value -> Math.max(value.width(), value.depth()) / 2).max().orElse(0);
        final int startRadius = settlement.type().footprintRadius() + largestHalfSize + 6;
        return planInZone(settlement, buildingId, seededSelections, layout, startRadius,
            maximumExpansionRadius, true, terrain, globalRoads);
    }

    public Optional<LayoutPlacement> planStarter(final SettlementRecord settlement, final UUID buildingId,
        final List<SettlementStructureSelection> seededSelections, final SettlementLayoutPlan layout,
        final int minimumRadius, final int maximumRadius, final TerrainSampler terrain,
        final RoadNetwork globalRoads)
    {
        return planInZone(settlement, buildingId, seededSelections, layout, minimumRadius,
            maximumRadius, false, terrain, globalRoads);
    }

    private Optional<LayoutPlacement> planInZone(final SettlementRecord settlement, final UUID buildingId,
        final List<SettlementStructureSelection> seededSelections, final SettlementLayoutPlan layout,
        final int startRadius, final int maximumRadius, final boolean excludeStarter,
        final TerrainSampler terrain, final RoadNetwork globalRoads)
    {
        final long started = System.nanoTime();
        final LayoutPlanningDiagnostics.Builder diagnostics = new LayoutPlanningDiagnostics.Builder();
        diagnostics.structures = seededSelections.size();
        final Optional<LayoutPlacement> result = search(settlement, buildingId, seededSelections, layout, startRadius,
            maximumRadius, excludeStarter, terrain, globalRoads, diagnostics);
        layout.recordDiagnostics(diagnostics.build(System.nanoTime() - started));
        return result;
    }

    private Optional<LayoutPlacement> search(final SettlementRecord settlement, final UUID buildingId,
        final List<SettlementStructureSelection> seededSelections, final SettlementLayoutPlan layout,
        final int startRadius, final int maximumRadius, final boolean excludeStarter,
        final TerrainSampler terrain, final RoadNetwork globalRoads, final LayoutPlanningDiagnostics.Builder diagnostics)
    {
        if (seededSelections.isEmpty()) { diagnostics.reject(LayoutRejectionReason.NO_STRUCTURE); return Optional.empty(); }
        if (startRadius > maximumRadius) { diagnostics.reject(LayoutRejectionReason.OUTSIDE_RADIUS); return Optional.empty(); }
        final List<BlockPos> streetPoints = layout.streets().points();
        if (streetPoints.isEmpty()) { diagnostics.reject(LayoutRejectionReason.STREET_UNREACHABLE); return Optional.empty(); }
        final List<int[]> roadPoints = nearbyRoadPoints(settlement, globalRoads, maximumRadius + 80);
        final int plazaY = layout.streets().segments().stream().filter(value -> PLAZA_PURPOSE.equals(value.purpose()))
            .map(value -> value.points().getFirst().getY()).findFirst().orElse(settlement.anchor().getY());
        final TerrainShapingSettings configured = shapingSettings.get();
        final List<PadCandidate> pads = new ArrayList<>();
        final int offset = stableOffset(buildingId);
        search:
        for (int radius = startRadius; radius <= maximumRadius; radius += RING_STEP)
        {
            final int angles = Math.max(8, Math.min(24, (int) Math.ceil(Math.PI * 2.0D * radius / 12.0D)));
            for (int step = 0; step < angles; step++)
            {
                if (diagnostics.positions >= MAX_POSITIONS || distinct(pads) >= ENOUGH_PADS) break search;
                diagnostics.positions++;
                final double angle = Math.PI * 2.0D * Math.floorMod(step + offset, angles) / angles;
                final int x = settlement.anchor().getX() + (int) Math.round(Math.cos(angle) * radius);
                final int z = settlement.anchor().getZ() + (int) Math.round(Math.sin(angle) * radius);
                final TerrainSample center = terrain.sample(x, z);
                for (final SettlementStructureSelection seeded : seededSelections)
                {
                    final SettlementStructureDescriptor descriptor = seeded.descriptor();
                    final TerrainShapingSettings limits = limits(configured, descriptor);
                    final LayoutRejectionReason coarse = prefilter(x, z, center, descriptor, limits, terrain);
                    if (coarse != null) { diagnostics.reject(coarse); continue; }
                    final BlockPos tentative = new BlockPos(x, center.height(), z);
                    final List<StructureTransform> transforms = orderedTransforms(descriptor, seeded.transform(),
                        tentative, streetPoints);
                    diagnostics.rotations += transforms.size();
                    int evaluated = 0;
                    for (final StructureTransform transform : transforms)
                    {
                        if (evaluated >= TRANSFORMS_PER_POSITION) break;
                        final StructureFootprint footprint = descriptor.footprint(tentative, transform);
                        final LayoutRejectionReason reservation = reserved(settlement, footprint, layout, roadPoints, excludeStarter);
                        if (reservation != null) { diagnostics.reject(reservation); continue; }
                        if (diagnostics.pads >= MAX_CANDIDATE_PADS)
                        {
                            diagnostics.reject(LayoutRejectionReason.SEARCH_BUDGET);
                            break search;
                        }
                        evaluated++;
                        diagnostics.pads++;
                        final var terrainEvaluation = terrainPlanner.plan(footprint, terrain,
                            new TerrainShapingSettings(limits.maximumCutDepth(), limits.maximumFillDepth(),
                                limits.maximumPadHeightVariance(), limits.maximumRetainingWallHeight(),
                                Math.min(limits.maximumTerrainWorkVolume(), Math.max(512, footprint.width() * footprint.depth() * 3)),
                                limits.clearancePadding()));
                        if (!terrainEvaluation.accepted())
                        {
                            diagnostics.reject(terrainRejection(terrainEvaluation.rejection()));
                            continue;
                        }
                        TerrainShapingPlan shaping = terrainEvaluation.plan();
                        if (shaping.pad().mode() != TerrainShapingMode.NATURAL
                            && Math.abs(shaping.pad().targetHeight() - plazaY) >= 3)
                            shaping = shaping.withPad(shaping.pad().withMode(TerrainShapingMode.TERRACE));
                        final int targetY = shaping.pad().targetHeight();
                        final BlockPos anchor = tentative.atY(targetY);
                        final BlockPos entrance = descriptor.entrance(anchor, transform).atY(targetY);
                        final long preliminaryScore = nearestDistanceSquared(entrance, streetPoints) * 10L
                            + shaping.pad().terrainWorkVolume() * 100L
                            + Math.floorMod(stableHash(buildingId + ":" + x + ':' + z + ':' + descriptor.stableId() + ':' + transform), 97);
                        pads.add(new PadCandidate(anchor, new SettlementStructureSelection(descriptor, transform),
                            footprint, entrance, shaping, preliminaryScore));
                    }
                }
            }
        }
        final List<Candidate> candidates = new ArrayList<>();
        final List<PadCandidate> orderedPads = pads.stream().sorted(PadCandidate.ORDER).toList();
        final List<PadCandidate> accepted = new ArrayList<>();
        for (final PadCandidate pad : orderedPads)
        {
            if (accepted.size() >= MAX_STREET_CANDIDATES) break;
            // Two pads chosen from overlapping positions add no information to the street search.
            if (accepted.stream().anyMatch(value -> value.footprint().intersects(pad.footprint()))) continue;
            accepted.add(pad);
        }
        for (int index = 0; index < accepted.size(); index++)
        {
            final PadCandidate pad = accepted.get(index);
            final UUID streetId = stableId("street-lot", buildingId, index);
            final var extension = streetPlanner.plan(streetId, pad.entrance(),
                pad.structure().descriptor().entranceFacing(pad.structure().transform()), pad.footprint(),
                streetPoints, layout.lots(), terrain, streetSettings.get());
            if (extension.isEmpty()) { diagnostics.reject(LayoutRejectionReason.STREET_UNREACHABLE); continue; }
            final SettlementStreetSegment street = extension.orElseThrow();
            final BlockPos join = street.points().getLast();
            final SettlementLot lot = new SettlementLot(buildingId, pad.footprint(), pad.entrance(),
                pad.structure().descriptor().entranceFacing(pad.structure().transform()), join, List.of(streetId));
            final long score = pad.preliminaryScore() + (long) (street.points().size() - 1) * 1_000L;
            candidates.add(new Candidate(pad.anchor(), pad.structure(), lot, street, pad.terrain(), score));
            diagnostics.accepted++;
        }
        final Optional<Candidate> selected = candidates.stream().min(Comparator.comparingLong(Candidate::score)
                .thenComparingInt(value -> value.anchor().getX()).thenComparingInt(value -> value.anchor().getZ())
                .thenComparing(value -> value.structure().descriptor().stableId())
                .thenComparingInt(value -> value.structure().transform().rotation()));
        selected.ifPresent(value -> {
            diagnostics.terrainWork += value.terrain().pad().terrainWorkVolume();
            diagnostics.streets++;
        });
        if (selected.isEmpty() && pads.isEmpty()) diagnostics.reject(LayoutRejectionReason.NO_VALID_ROTATION);
        return selected.map(value -> new LayoutPlacement(value.anchor(), value.structure(), value.lot(), value.street(), value.terrain()));
    }

    /** Number of mutually non-overlapping pads; overlapping pads add nothing to the street search. */
    private static int distinct(final List<PadCandidate> pads)
    {
        final List<StructureFootprint> kept = new ArrayList<>();
        for (final PadCandidate pad : pads)
            if (kept.stream().noneMatch(value -> value.intersects(pad.footprint()))) kept.add(pad.footprint());
        return kept.size();
    }

    private static TerrainShapingSettings limits(final TerrainShapingSettings configured,
        final SettlementStructureDescriptor descriptor)
    {
        final var limits = descriptor.terrainConstraints();
        return new TerrainShapingSettings(
            Math.min(configured.maximumCutDepth(), Math.max(2, limits.maximumCutFill() * 2)),
            Math.min(configured.maximumFillDepth(), Math.max(2, limits.maximumCutFill() * 2)),
            Math.min(configured.maximumPadHeightVariance(), Math.max(4, limits.maximumHeightVariation() * 2)),
            configured.maximumRetainingWallHeight(), configured.maximumTerrainWorkVolume(),
            Math.min(8, limits.perimeterPadding()));
    }

    /** Five samples (center and the four corners of the largest possible footprint) reject hopeless positions cheaply. */
    static LayoutRejectionReason prefilter(final int x, final int z, final TerrainSample center,
        final SettlementStructureDescriptor descriptor, final TerrainShapingSettings limits, final TerrainSampler terrain)
    {
        final int half = Math.max(descriptor.width(), descriptor.depth()) / 2;
        final TerrainSample[] samples = {center, terrain.sample(x - half, z - half), terrain.sample(x + half, z - half),
            terrain.sample(x - half, z + half), terrain.sample(x + half, z + half)};
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (final TerrainSample sample : samples)
        {
            if (!sample.known()) return LayoutRejectionReason.UNLOADED_TERRAIN;
            if (!sample.allowedBiome()) return LayoutRejectionReason.DISALLOWED_BIOME;
            if (sample.water()) return LayoutRejectionReason.WATER;
            minimum = Math.min(minimum, sample.height());
            maximum = Math.max(maximum, sample.height());
        }
        final int span = maximum - minimum;
        if (span > limits.maximumPadHeightVariance() * 2) return LayoutRejectionReason.CLIFF;
        if (span > limits.maximumPadHeightVariance()) return LayoutRejectionReason.SLOPE;
        return null;
    }

    private static List<StructureTransform> orderedTransforms(final SettlementStructureDescriptor descriptor,
        final StructureTransform seeded, final BlockPos tentative, final List<BlockPos> streetPoints)
    {
        return descriptor.supportedTransforms().stream().sorted(Comparator
            .comparingLong((StructureTransform value) -> nearestDistanceSquared(descriptor.entrance(tentative, value), streetPoints))
            .thenComparing(value -> !value.equals(seeded))
            .thenComparingInt(StructureTransform::rotation).thenComparing(StructureTransform::mirrored)).toList();
    }

    static LayoutRejectionReason reserved(final SettlementRecord settlement, final StructureFootprint footprint,
        final SettlementLayoutPlan layout, final List<int[]> roadPoints, final boolean excludeStarter)
    {
        final BlockPos anchor = settlement.anchor();
        final StructureFootprint plaza = new StructureFootprint(anchor.getX() - PLAZA_RADIUS - 2, anchor.getZ() - PLAZA_RADIUS - 2,
            anchor.getX() + PLAZA_RADIUS + 2, anchor.getZ() + PLAZA_RADIUS + 2);
        if (footprint.intersects(plaza) || footprint.expand(6).contains(settlement.gate().getX(), settlement.gate().getZ()))
            return LayoutRejectionReason.STARTER_EXCLUSION;
        if (footprint.intersects(gateApproach(settlement))) return LayoutRejectionReason.GATE_APPROACH;
        final int starter = settlement.type().footprintRadius();
        if (excludeStarter && footprint.minX() <= anchor.getX() + starter && footprint.maxX() >= anchor.getX() - starter
            && footprint.minZ() <= anchor.getZ() + starter && footprint.maxZ() >= anchor.getZ() - starter)
            return LayoutRejectionReason.STARTER_EXCLUSION;
        if (layout.lots().stream().anyMatch(lot -> lot.footprint().expand(3).intersects(footprint)))
            return LayoutRejectionReason.BUILDING_COLLISION;
        for (final SettlementStreetSegment street : layout.streets().segments())
            if (street.intersects(footprint, street.width() + 1)) return LayoutRejectionReason.LOCAL_STREET_COLLISION;
        for (final int[] point : roadPoints)
            if (footprint.expand(point[2]).contains(point[0], point[1])) return LayoutRejectionReason.GLOBAL_ROAD;
        return null;
    }

    static StructureFootprint gateApproach(final SettlementRecord settlement)
    {
        final BlockPos gate = settlement.gate();
        final BlockPos anchor = settlement.anchor();
        final boolean alongX = Math.abs(gate.getX() - anchor.getX()) >= Math.abs(gate.getZ() - anchor.getZ());
        final int direction = alongX ? Integer.signum(gate.getX() - anchor.getX()) : Integer.signum(gate.getZ() - anchor.getZ());
        final int outward = direction == 0 ? 1 : direction;
        final int farX = alongX ? gate.getX() + outward * GATE_APPROACH_LENGTH : gate.getX();
        final int farZ = alongX ? gate.getZ() : gate.getZ() + outward * GATE_APPROACH_LENGTH;
        final int side = GATE_APPROACH_HALF_WIDTH;
        return alongX
            ? new StructureFootprint(Math.min(gate.getX(), farX), gate.getZ() - side, Math.max(gate.getX(), farX), gate.getZ() + side)
            : new StructureFootprint(gate.getX() - side, Math.min(gate.getZ(), farZ), gate.getX() + side, Math.max(gate.getZ(), farZ));
    }

    /** Global-road polyline points near the settlement, with their clearance, computed once per search. */
    static List<int[]> nearbyRoadPoints(final SettlementRecord settlement, final RoadNetwork roads, final int radius)
    {
        final List<int[]> result = new ArrayList<>();
        for (final RoadRecord road : roads.roads())
        {
            if (!road.dimension().equals(settlement.dimension())) continue;
            for (final BlockPos point : road.polyline())
                if (Math.abs(point.getX() - settlement.anchor().getX()) <= radius
                    && Math.abs(point.getZ() - settlement.anchor().getZ()) <= radius)
                    result.add(new int[] {point.getX(), point.getZ(), road.type().width() + 3});
        }
        return result;
    }

    private static LayoutRejectionReason terrainRejection(final TerrainShapingRejection rejection)
    {
        return switch (rejection)
        {
            case WATER -> LayoutRejectionReason.WATER;
            case DISALLOWED_BIOME -> LayoutRejectionReason.DISALLOWED_BIOME;
            case EXCESSIVE_HEIGHT_VARIANCE -> LayoutRejectionReason.ROUGHNESS;
            case EXCESSIVE_CUT -> LayoutRejectionReason.EXCESSIVE_CUT;
            case EXCESSIVE_FILL -> LayoutRejectionReason.EXCESSIVE_FILL;
            case EXCESSIVE_TERRAIN_VOLUME -> LayoutRejectionReason.EXCESSIVE_TERRAIN_VOLUME;
            case RETAINING_WALL_TOO_HIGH -> LayoutRejectionReason.RETAINING_WALL;
            case SAMPLE_LIMIT -> LayoutRejectionReason.SAMPLE_LIMIT;
            case UNSUPPORTED_FOOTPRINT -> LayoutRejectionReason.UNSUPPORTED_FOOTPRINT;
            case UNLOADED_TERRAIN -> LayoutRejectionReason.UNLOADED_TERRAIN;
            case NONE -> LayoutRejectionReason.NO_VALID_ROTATION;
        };
    }

    private static BlockPos surface(final BlockPos position, final TerrainSampler terrain)
    {
        return position.atY(terrain.sample(position.getX(), position.getZ()).height());
    }

    public static List<BlockPos> axisPath(final BlockPos from, final BlockPos to)
    {
        final List<BlockPos> result = new ArrayList<>(); result.add(from.immutable());
        BlockPos cursor = from;
        while (cursor.getX() != to.getX()) { cursor = cursor.offset(Integer.signum(to.getX() - cursor.getX()), 0, 0); result.add(cursor); }
        while (cursor.getZ() != to.getZ()) { cursor = cursor.offset(0, 0, Integer.signum(to.getZ() - cursor.getZ())); result.add(cursor); }
        return List.copyOf(result);
    }
    private static long nearestDistanceSquared(final BlockPos point, final List<BlockPos> candidates)
    {
        long result = Long.MAX_VALUE;
        for (final BlockPos candidate : candidates)
        {
            final long dx = point.getX() - candidate.getX();
            final long dz = point.getZ() - candidate.getZ();
            result = Math.min(result, dx * dx + dz * dz);
        }
        return result;
    }
    private static int stableOffset(final UUID id) { return Math.floorMod((int) (id.getMostSignificantBits() ^ id.getLeastSignificantBits()), 24); }
    private static long stableHash(final String value)
    { final UUID id = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); return id.getMostSignificantBits() ^ id.getLeastSignificantBits(); }
    private static UUID stableId(final String prefix, final UUID id, final int sequence)
    { return UUID.nameUUIDFromBytes((prefix + ':' + id + ':' + sequence).getBytes(StandardCharsets.UTF_8)); }

    private record Candidate(BlockPos anchor, SettlementStructureSelection structure, SettlementLot lot,
        SettlementStreetSegment street, TerrainShapingPlan terrain, long score) {}

    private record PadCandidate(BlockPos anchor, SettlementStructureSelection structure,
        StructureFootprint footprint, BlockPos entrance, TerrainShapingPlan terrain, long preliminaryScore)
    {
        private static final Comparator<PadCandidate> ORDER = Comparator.comparingLong(PadCandidate::preliminaryScore)
            .thenComparingInt(value -> value.anchor().getX()).thenComparingInt(value -> value.anchor().getZ())
            .thenComparing(value -> value.structure().descriptor().stableId())
            .thenComparingInt(value -> value.structure().transform().rotation())
            .thenComparing(value -> value.structure().transform().mirrored());
    }
}
