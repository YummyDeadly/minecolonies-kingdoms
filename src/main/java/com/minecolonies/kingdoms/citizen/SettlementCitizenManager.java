package com.minecolonies.kingdoms.citizen;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.citizen.interaction.CitizenInteractionContext;
import com.minecolonies.kingdoms.citizen.interaction.SettlementCitizenInteractionService;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.contract.ContractText;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.entity.caravan.ModEntities;
import com.minecolonies.kingdoms.entity.citizen.SettlementCitizenEntity;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Physical representation of settlement populations. {@code NPCColonyData.population} stays the only population
 * truth; this manager never changes it. Near an observed settlement it materializes a bounded, gradually growing set
 * of recognisable representatives from the persisted roster, drives a small daily schedule along the local street
 * network, and removes every entity again when players leave. Far settlements cost one distance check per cycle.
 */
public final class SettlementCitizenManager
{
    private static final SettlementCitizenManager INSTANCE = new SettlementCitizenManager();
    public static final long MANUAL_HOLD_TICKS = 1200L;
    static final long FAILED_SPAWN_RETRY_TICKS = 100L;
    static final long STUCK_RESPAWN_TICKS = 200L;
    static final double ARRIVAL_DISTANCE = 1.8D;
    static final int WANDER_RADIUS = 5;

    private final Map<UUID, Physical> physical = new LinkedHashMap<>();
    private final Set<UUID> activeSettlements = new HashSet<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> heldUntil = new HashMap<>();
    private final Map<UUID, Long> suppressedUntil = new HashMap<>();
    private final Map<UUID, Integer> rosterSignatures = new HashMap<>();
    private final Map<UUID, RouterEntry> routers = new HashMap<>();
    private final Map<UUID, Integer> failedSpawns = new HashMap<>();
    private final CitizenProfiler profiler = new CitizenProfiler();
    private MinecraftServer server;
    private long nextUpdate;

    private SettlementCitizenManager() {}
    public static SettlementCitizenManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        clearRuntime();
        server = value;
        profiler.reset();
    }

    public void shutdown()
    {
        clearRuntime();
        server = null;
    }

    private void clearRuntime()
    {
        physical.clear();
        activeSettlements.clear();
        cooldownUntil.clear();
        heldUntil.clear();
        suppressedUntil.clear();
        rosterSignatures.clear();
        routers.clear();
        failedSpawns.clear();
        nextUpdate = 0L;
    }

    public void tick(final MinecraftServer value)
    {
        if (server == null || server != value) return;
        final CitizenSettings settings = settingsFromConfig();
        final long gameTime = value.overworld().getGameTime();
        if (!settings.enabled())
        {
            if (!physical.isEmpty()) List.copyOf(physical.values()).forEach(this::dematerialize);
            activeSettlements.clear();
            return;
        }
        if (gameTime < nextUpdate) return;
        nextUpdate = gameTime + settings.updateIntervalTicks();
        final long started = System.nanoTime();
        try
        {
            update(KingdomsSavedData.get(value.overworld()), settings, gameTime);
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error("Settlement citizen update failed", exception);
        }
        profiler.cycle(System.nanoTime() - started);
    }

    // ------------------------------------------------------------------------------------------------ cycle

    private void update(final KingdomsSavedData data, final CitizenSettings settings, final long gameTime)
    {
        final Map<UUID, SettlementRecord> eligible = new LinkedHashMap<>();
        data.settlements().records().stream().sorted(Comparator.comparing(SettlementRecord::id))
            .filter(settlement -> data.colony(settlement.id()).map(colony -> colony.kind() == ColonyKind.NPC_ABSTRACT).orElse(false))
            .forEach(settlement -> eligible.put(settlement.id(), settlement));
        final Set<UUID> removed = data.citizens().retain(eligible.keySet());
        if (!removed.isEmpty()) data.markChanged();
        for (final Physical value : List.copyOf(physical.values()))
            if (!eligible.containsKey(value.settlementId)) dematerialize(value);
        activeSettlements.retainAll(eligible.keySet());

        final List<Candidate> candidates = new ArrayList<>();
        for (final SettlementRecord settlement : eligible.values())
        {
            final ServerLevel level = level(settlement.dimension());
            final ServerPlayer player = level == null ? null : nearestPlayer(level, Vec3.atCenterOf(settlement.anchor()));
            final double distance = player == null ? Double.POSITIVE_INFINITY
                : Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(settlement.anchor())));
            final boolean held = heldUntil.getOrDefault(settlement.id(), Long.MIN_VALUE) > gameTime;
            final boolean suppressed = suppressedUntil.getOrDefault(settlement.id(), Long.MIN_VALUE) > gameTime;
            final boolean was = activeSettlements.contains(settlement.id());
            final boolean now = level != null && !suppressed
                && level.getChunkSource().getChunkNow(settlement.anchor().getX() >> 4, settlement.anchor().getZ() >> 4) != null
                && (held || CitizenBudget.active(was, distance, settings));
            if (!now)
            {
                if (was || hasPhysical(settlement.id())) dematerializeSettlement(settlement.id());
                activeSettlements.remove(settlement.id());
                continue;
            }
            activeSettlements.add(settlement.id());
            candidates.add(new Candidate(settlement, level, player, distance));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(candidate -> candidate.settlement().id()));
        final Map<UUID, Integer> perPlayer = new HashMap<>();
        physical.values().forEach(value -> { if (value.observer != null) perPlayer.merge(value.observer, 1, Integer::sum); });
        for (final Candidate candidate : candidates)
        {
            final NPCColonyData colony = data.colony(candidate.settlement().id()).orElse(null);
            if (colony == null) continue;
            final List<SettlementRepresentative> roster = reconcile(data, candidate.settlement(), colony, settings);
            final Context context = context(data, candidate.settlement(), candidate.level());
            updatePhysical(candidate, context, roster, gameTime, settings);
            spawn(candidate, context, colony, roster, gameTime, settings, perPlayer);
        }
    }

    private List<SettlementRepresentative> reconcile(final KingdomsSavedData data, final SettlementRecord settlement,
        final NPCColonyData colony, final CitizenSettings settings)
    {
        final List<RosterPlanner.Building> buildings = data.growth().forSettlement(settlement.id()).stream()
            .filter(building -> building.status() == SettlementBuildingStatus.COMPLETED)
            .map(building -> new RosterPlanner.Building(building.id(), building.type(), building.sequence())).toList();
        final int signature = Objects.hash(colony.population(), settings.maxRosterPerSettlement(),
            buildings.stream().map(building -> building.id() + ":" + building.type()).toList());
        final List<SettlementRepresentative> existing = data.citizens().roster(settlement.id());
        if (Integer.valueOf(signature).equals(rosterSignatures.get(settlement.id()))) return existing;
        final List<SettlementRepresentative> planned = RosterPlanner.plan(settlement.id(), settlement.type(),
            colony.population(), buildings, existing, settings.maxRosterPerSettlement());
        if (!planned.equals(existing))
        {
            data.citizens().put(settlement.id(), planned);
            data.markChanged();
        }
        rosterSignatures.put(settlement.id(), signature);
        final Set<UUID> ids = new HashSet<>();
        planned.forEach(value -> ids.add(value.id()));
        for (final Physical value : List.copyOf(physical.values()))
            if (value.settlementId.equals(settlement.id()) && !ids.contains(value.representativeId)) dematerialize(value);
        return planned;
    }

    private void updatePhysical(final Candidate candidate, final Context context, final List<SettlementRepresentative> roster,
        final long gameTime, final CitizenSettings settings)
    {
        final Map<UUID, SettlementRepresentative> byId = new HashMap<>();
        roster.forEach(value -> byId.put(value.id(), value));
        final long dayTime = candidate.level().getDayTime();
        for (final Physical value : List.copyOf(physical.values()))
        {
            if (!value.settlementId.equals(candidate.settlement().id())) continue;
            final Entity raw = candidate.level().getEntity(value.entityId);
            if (!(raw instanceof SettlementCitizenEntity citizen) || citizen.isRemoved())
            {
                forget(value); // technical loss (chunk unload etc.): no population effect, respawn when possible
                continue;
            }
            final SettlementRepresentative representative = byId.get(value.representativeId);
            if (representative == null) { dematerialize(value); continue; }
            final boolean hasHome = representative.homeBuildingId() != null && context.doors().containsKey(representative.homeBuildingId());
            final boolean hasWork = representative.workBuildingId() != null && context.doors().containsKey(representative.workBuildingId());
            final CitizenActivity activity = CitizenSchedule.activity(representative.role(), hasWork, hasHome, dayTime,
                representative.cosmeticSeed());
            if (activity != value.activity)
            {
                value.activity = activity;
                plan(value, citizen, context, destination(context, representative, activity));
            }
            if (activity == CitizenActivity.HOME || activity == CitizenActivity.RETURN_HOME)
            {
                final BlockPos shelter = destination(context, representative, activity); // home door, else the plaza
                if (horizontalDistance(citizen.position(), shelter) < 2.5D)
                {
                    dematerialize(value); // went inside for the night; respawns at the door in the morning
                    continue;
                }
            }
            advance(value, citizen, context, representative, gameTime, settings);
        }
    }

    private void advance(final Physical value, final SettlementCitizenEntity citizen, final Context context,
        final SettlementRepresentative representative, final long gameTime, final CitizenSettings settings)
    {
        if (value.index < value.waypoints.size())
        {
            final BlockPos target = value.waypoints.get(value.index);
            final double distance = horizontalDistance(citizen.position(), target);
            if (distance < ARRIVAL_DISTANCE)
            {
                value.index++;
                value.stuck.arrived();
            }
            else switch (value.stuck.update(distance, gameTime))
            {
                case SKIP_WAYPOINT -> value.index++;
                case GIVE_UP ->
                {
                    profiler.stuckRecovery();
                    cooldownUntil.put(value.representativeId, gameTime + STUCK_RESPAWN_TICKS);
                    dematerialize(value);
                    return;
                }
                case MOVING -> { }
            }
        }
        if (value.index < value.waypoints.size())
        {
            citizen.planTarget(Vec3.atBottomCenterOf(value.waypoints.get(value.index)));
            return;
        }
        if (CitizenSchedule.wandersAtDestination(value.activity) && gameTime >= value.nextWanderAt)
        {
            final BlockPos anchor = Optional.ofNullable(destination(context, representative, value.activity))
                .orElse(citizen.blockPosition());
            final var random = citizen.getRandom();
            final BlockPos probe = anchor.offset(random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS, 0,
                random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS);
            final Optional<BlockPos> spot = SafeSpawnFinder.find(probe, 2, 3, view(context.level()), context.footprints());
            value.nextWanderAt = gameTime + (spot.isPresent() ? 120 + random.nextInt(160) : 100);
            if (spot.isPresent())
            {
                value.waypoints = List.of(spot.get());
                value.index = 0;
                value.stuck.arrived();
                citizen.planTarget(Vec3.atBottomCenterOf(spot.get()));
                return;
            }
        }
        if (value.index >= value.waypoints.size()) citizen.planTarget(null);
    }

    private void spawn(final Candidate candidate, final Context context, final NPCColonyData colony,
        final List<SettlementRepresentative> roster, final long gameTime, final CitizenSettings settings,
        final Map<UUID, Integer> perPlayer)
    {
        final UUID settlementId = candidate.settlement().id();
        final List<Physical> here = physical.values().stream().filter(value -> value.settlementId.equals(settlementId)).toList();
        final int target = CitizenBudget.target(colony.population(), roster.size(), settings.maxPerSettlement());
        for (int index = here.size() - 1; index >= target; index--) dematerialize(here.get(index));
        final UUID observer = candidate.player() == null ? null : candidate.player().getUUID();
        int allowance = CitizenBudget.spawnAllowance(target, Math.min(here.size(), target), physical.size(),
            observer == null ? 0 : perPlayer.getOrDefault(observer, 0), settings);
        final long dayTime = candidate.level().getDayTime();
        for (final SettlementRepresentative representative : roster)
        {
            if (allowance <= 0) break;
            if (physical.containsKey(representative.id())
                || cooldownUntil.getOrDefault(representative.id(), Long.MIN_VALUE) > gameTime) continue;
            final boolean hasHome = representative.homeBuildingId() != null && context.doors().containsKey(representative.homeBuildingId());
            final boolean hasWork = representative.workBuildingId() != null && context.doors().containsKey(representative.workBuildingId());
            final CitizenActivity activity = CitizenSchedule.activity(representative.role(), hasWork, hasHome, dayTime,
                representative.cosmeticSeed());
            // Indoors, or on the way there: materializing them would only make them vanish again at the door.
            if (activity == CitizenActivity.HOME || activity == CitizenActivity.RETURN_HOME) continue;
            final BlockPos origin = spawnOrigin(context, representative, activity, hasHome);
            final Optional<BlockPos> position = SafeSpawnFinder.find(origin, 4, 4, view(context.level()), context.footprints());
            if (position.isEmpty())
            {
                profiler.failedSpawn();
                failedSpawns.merge(settlementId, 1, Integer::sum);
                cooldownUntil.put(representative.id(), gameTime + FAILED_SPAWN_RETRY_TICKS);
                continue;
            }
            final SettlementCitizenEntity entity = ModEntities.SETTLEMENT_CITIZEN.get().create(context.level());
            if (entity == null) continue;
            entity.configure(settlementId, representative.id(), representative.name(),
                CitizenAppearance.textureKey(context.style(), representative.role(), representative.female(), representative.cosmeticSeed()));
            entity.moveTo(position.get().getX() + 0.5D, position.get().getY(), position.get().getZ() + 0.5D,
                (float) Math.floorMod(representative.cosmeticSeed(), 360L), 0.0F);
            if (!context.level().addFreshEntity(entity))
            {
                profiler.failedSpawn();
                failedSpawns.merge(settlementId, 1, Integer::sum);
                continue;
            }
            final Physical value = new Physical(representative.id(), settlementId, entity.getUUID(), observer,
                new StuckTracker(settings.stuckTimeoutTicks()));
            value.activity = activity;
            plan(value, entity, context, destination(context, representative, activity));
            physical.put(representative.id(), value);
            profiler.materialized();
            allowance--;
            if (observer != null) perPlayer.merge(observer, 1, Integer::sum);
        }
    }

    // ------------------------------------------------------------------------------------------------ anchors

    private static BlockPos spawnOrigin(final Context context, final SettlementRepresentative representative,
        final CitizenActivity activity, final boolean hasHome)
    {
        if (representative.role() == CitizenRole.GUARD || representative.role() == CitizenRole.OFFICIAL)
            return Optional.ofNullable(destination(context, representative, CitizenActivity.WORK)).orElse(context.plaza());
        if (hasHome) return context.doors().get(representative.homeBuildingId());
        return Optional.ofNullable(destination(context, representative, activity)).orElse(context.plaza());
    }

    static BlockPos destination(final Context context, final SettlementRepresentative representative, final CitizenActivity activity)
    {
        return switch (CitizenSchedule.destination(activity))
        {
            case HOME -> representative.homeBuildingId() != null && context.doors().containsKey(representative.homeBuildingId())
                ? context.doors().get(representative.homeBuildingId()) : context.plaza();
            case WORK -> representative.role() == CitizenRole.GUARD ? context.gate()
                : representative.workBuildingId() != null && context.doors().containsKey(representative.workBuildingId())
                    ? context.doors().get(representative.workBuildingId()) : context.plaza();
            case PLAZA -> context.plaza();
            case MARKET -> context.market() == null ? context.plaza() : context.market();
            case STAY -> null;
        };
    }

    private void plan(final Physical value, final SettlementCitizenEntity citizen, final Context context, final BlockPos destination)
    {
        value.waypoints = destination == null ? List.of() : context.router().route(citizen.blockPosition(), destination);
        value.index = 0;
        value.nextWanderAt = 0L;
        value.stuck.arrived();
        citizen.planTarget(value.waypoints.isEmpty() ? null : Vec3.atBottomCenterOf(value.waypoints.getFirst()));
    }

    private Context context(final KingdomsSavedData data, final SettlementRecord settlement, final ServerLevel level)
    {
        final SettlementLayoutPlan layout = data.growth().layout(settlement.id()).orElse(null);
        final Map<UUID, BlockPos> doors = new HashMap<>();
        final List<StructureFootprint> footprints = new ArrayList<>();
        BlockPos market = null;
        for (final SettlementBuildingRecord building : data.growth().forSettlement(settlement.id()))
        {
            footprints.add(building.footprint());
            if (building.status() != SettlementBuildingStatus.COMPLETED) continue;
            final BlockPos door = door(layout, building);
            doors.put(building.id(), door);
            if (market == null && building.type() == SettlementBuildingType.MARKET) market = door;
        }
        BlockPos plaza = settlement.anchor();
        if (layout != null)
            for (final var segment : layout.streets().segments())
                if (SettlementLayoutPlanner.PLAZA_PURPOSE.equals(segment.purpose())) plaza = segment.points().getFirst();
        final StreetRouter router = router(settlement.id(), layout);
        return new Context(settlement, level, Map.copyOf(doors), plaza, market, settlement.gate(), List.copyOf(footprints),
            router, layout == null ? "" : layout.styleFamily());
    }

    /** The first street point in front of the building (outside its footprint), else the persisted entrance. */
    private static BlockPos door(final SettlementLayoutPlan layout, final SettlementBuildingRecord building)
    {
        if (layout != null)
        {
            final var lot = layout.lot(building.id()).orElse(null);
            if (lot != null && !lot.streetSegmentIds().isEmpty())
            {
                final var segment = layout.streets().get(lot.streetSegmentIds().getFirst()).orElse(null);
                if (segment != null) return segment.points().getFirst();
            }
        }
        return building.entrance();
    }

    private StreetRouter router(final UUID settlementId, final SettlementLayoutPlan layout)
    {
        final int signature = layout == null ? 0 : layout.streets().segments().size();
        final RouterEntry cached = routers.get(settlementId);
        if (cached != null && cached.signature() == signature) return cached.router();
        final StreetRouter router = layout == null ? new StreetRouter(new SettlementLayoutPlan(settlementId, "",
            new com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetNetwork())) : new StreetRouter(layout);
        routers.put(settlementId, new RouterEntry(signature, router));
        return router;
    }

    // ------------------------------------------------------------------------------------------------ entity callbacks

    public boolean isCurrent(final SettlementCitizenEntity entity)
    {
        if (entity.representativeId() == null) return false;
        final Physical value = physical.get(entity.representativeId());
        return value != null && value.entityId.equals(entity.getUUID());
    }

    /** A representative died; a player killer loses reputation with the settlement's faction. */
    public void onDeath(final SettlementCitizenEntity entity, final ServerPlayer killer)
    {
        if (!isCurrent(entity) || server == null) return;
        final Physical value = physical.get(entity.representativeId());
        forget(value);
        cooldownUntil.put(entity.representativeId(), server.overworld().getGameTime() + settingsFromConfig().respawnCooldownTicks());
        profiler.death(); // representation only: the logical population is untouched
        if (killer == null) return;
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        final NPCColonyData colony = data.colony(value.settlementId).orElse(null);
        final int penalty = KingdomsConfig.SERVER.reputationKillPenalty.get();
        if (colony == null || penalty <= 0) return;
        final ReputationService.Result result = ReputationService.adjust(data, killer.getUUID(), colony.factionId(), -penalty,
            com.minecolonies.kingdoms.diplomacy.ReputationRegistry.Cause.REPRESENTATIVE_KILLED, entity.representativeId(),
            server.overworld().getGameTime());
        if (result.isEmpty()) return;
        killer.sendSystemMessage(Component.literal("The people of " + colony.name() + " will remember this.")
            .withStyle(net.minecraft.ChatFormatting.RED));
        killer.sendSystemMessage(ContractText.reputation(data, result));
    }

    public void interact(final ServerPlayer player, final SettlementCitizenEntity entity)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(player.serverLevel());
        final SettlementRepresentative representative = entity.representativeId() == null ? null
            : data.citizens().find(entity.representativeId()).orElse(null);
        final SettlementRecord settlement = entity.settlementId() == null ? null : data.settlements().get(entity.settlementId()).orElse(null);
        final NPCColonyData colony = settlement == null ? null : data.colony(settlement.id()).orElse(null);
        if (representative == null || settlement == null || colony == null) return;
        final Physical value = physical.get(representative.id());
        final CitizenActivity activity = value == null || value.activity == null ? CitizenActivity.IDLE : value.activity;
        SettlementCitizenInteractionService.getInstance().interact(new CitizenInteractionContext(player, representative,
            settlement, colony, activity, player.serverLevel().getDayTime())).forEach(player::sendSystemMessage);
    }

    // ------------------------------------------------------------------------------------------------ operator API

    public int materializeNow(final MinecraftServer value, final UUID settlementId)
    {
        final long gameTime = value.overworld().getGameTime();
        suppressedUntil.remove(settlementId);
        heldUntil.put(settlementId, gameTime + MANUAL_HOLD_TICKS);
        nextUpdate = 0L;
        tick(value);
        return (int) physical.values().stream().filter(entry -> entry.settlementId.equals(settlementId)).count();
    }

    public int dematerializeNow(final MinecraftServer value, final UUID settlementId)
    {
        heldUntil.remove(settlementId);
        suppressedUntil.put(settlementId, value.overworld().getGameTime() + MANUAL_HOLD_TICKS);
        final int count = (int) physical.values().stream().filter(entry -> entry.settlementId.equals(settlementId)).count();
        dematerializeSettlement(settlementId);
        activeSettlements.remove(settlementId);
        return count;
    }

    public record PhysicalSnapshot(UUID representativeId, UUID settlementId, UUID entityId, UUID observer,
        CitizenActivity activity, int waypointIndex, int waypoints) {}

    public List<PhysicalSnapshot> physical(final UUID settlementId)
    {
        return physical.values().stream().filter(value -> settlementId == null || value.settlementId.equals(settlementId))
            .map(value -> new PhysicalSnapshot(value.representativeId, value.settlementId, value.entityId, value.observer,
                value.activity, value.index, value.waypoints.size())).toList();
    }

    public Optional<PhysicalSnapshot> physicalOf(final UUID representativeId)
    {
        return Optional.ofNullable(physical.get(representativeId)).map(value -> new PhysicalSnapshot(value.representativeId,
            value.settlementId, value.entityId, value.observer, value.activity, value.index, value.waypoints.size()));
    }

    public int activeSettlements() { return activeSettlements.size(); }
    public int failedSpawns(final UUID settlementId) { return failedSpawns.getOrDefault(settlementId, 0); }
    public CitizenProfiler.Stats stats() { return profiler.snapshot(); }

    // ------------------------------------------------------------------------------------------------ internals

    private boolean hasPhysical(final UUID settlementId)
    {
        return physical.values().stream().anyMatch(value -> value.settlementId.equals(settlementId));
    }

    private void dematerializeSettlement(final UUID settlementId)
    {
        for (final Physical value : List.copyOf(physical.values()))
            if (value.settlementId.equals(settlementId)) dematerialize(value);
    }

    private void dematerialize(final Physical value)
    {
        forget(value);
        if (server == null) return;
        for (final ServerLevel level : server.getAllLevels())
        {
            final Entity entity = level.getEntity(value.entityId);
            if (entity instanceof SettlementCitizenEntity citizen)
            {
                citizen.discardByManager();
                break;
            }
        }
        profiler.dematerialized();
    }

    private void forget(final Physical value)
    {
        physical.remove(value.representativeId);
    }

    private ServerLevel level(final net.minecraft.resources.ResourceLocation dimension)
    {
        return server == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private static ServerPlayer nearestPlayer(final ServerLevel level, final Vec3 position)
    {
        return level.players().stream().filter(player -> !player.isSpectator() && player.isAlive())
            .min(Comparator.comparingDouble(player -> player.distanceToSqr(position))).orElse(null);
    }

    private static double horizontalDistance(final Vec3 position, final BlockPos target)
    {
        final double dx = position.x - (target.getX() + 0.5D);
        final double dz = position.z - (target.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Block view that only reads entity-ticking chunks (never loads anything; a representative in a border chunk
     * would stand frozen) and treats hazards as unsafe.
     */
    public static SafeSpawnFinder.BlockView view(final ServerLevel level)
    {
        return new SafeSpawnFinder.BlockView()
        {
            @Override
            public boolean loaded(final int x, final int z)
            {
                return level.isPositionEntityTicking(new BlockPos(x, level.getMinBuildHeight(), z))
                    && level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
            }

            @Override
            public boolean floor(final int x, final int y, final int z)
            {
                final BlockPos pos = new BlockPos(x, y, z);
                final BlockState state = level.getBlockState(pos);
                return state.getFluidState().isEmpty() && state.isFaceSturdy(level, pos, Direction.UP)
                    && !state.is(BlockTags.LEAVES) && !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.CACTUS)
                    && !state.is(BlockTags.CAMPFIRES);
            }

            @Override
            public boolean open(final int x, final int y, final int z)
            {
                final BlockPos pos = new BlockPos(x, y, z);
                final BlockState state = level.getBlockState(pos);
                return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()
                    && !state.is(BlockTags.FIRE) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.POWDER_SNOW)
                    && !state.is(Blocks.COBWEB);
            }
        };
    }

    public static CitizenSettings settingsFromConfig()
    {
        return new CitizenSettings(KingdomsConfig.SERVER.citizensEnabled.get(),
            KingdomsConfig.SERVER.citizensMaterializationRadius.get(),
            Math.max(KingdomsConfig.SERVER.citizensDematerializationRadius.get(), KingdomsConfig.SERVER.citizensMaterializationRadius.get() + 8),
            KingdomsConfig.SERVER.citizensMaxPerSettlement.get(), KingdomsConfig.SERVER.citizensMaxGlobal.get(),
            KingdomsConfig.SERVER.citizensMaxPerPlayer.get(), KingdomsConfig.SERVER.citizensUpdateIntervalTicks.get(),
            KingdomsConfig.SERVER.citizensMaxRosterPerSettlement.get(), KingdomsConfig.SERVER.citizensSpawnsPerCycle.get(),
            KingdomsConfig.SERVER.citizensRespawnCooldownTicks.get(), KingdomsConfig.SERVER.citizensStuckTimeoutTicks.get());
    }

    private record Candidate(SettlementRecord settlement, ServerLevel level, ServerPlayer player, double distance) {}

    record Context(SettlementRecord settlement, ServerLevel level, Map<UUID, BlockPos> doors, BlockPos plaza,
        BlockPos market, BlockPos gate, List<StructureFootprint> footprints, StreetRouter router, String style) {}

    private record RouterEntry(int signature, StreetRouter router) {}

    private static final class Physical
    {
        private final UUID representativeId;
        private final UUID settlementId;
        private final UUID entityId;
        private final UUID observer;
        private final StuckTracker stuck;
        private CitizenActivity activity;
        private List<BlockPos> waypoints = List.of();
        private int index;
        private long nextWanderAt;

        private Physical(final UUID representativeId, final UUID settlementId, final UUID entityId, final UUID observer,
            final StuckTracker stuck)
        {
            this.representativeId = representativeId;
            this.settlementId = settlementId;
            this.entityId = entityId;
            this.observer = observer;
            this.stuck = stuck;
        }
    }
}
