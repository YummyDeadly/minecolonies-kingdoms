package com.minecolonies.kingdoms.military;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.bandit.BanditManager;
import com.minecolonies.kingdoms.citizen.CitizenAppearance;
import com.minecolonies.kingdoms.citizen.CitizenRole;
import com.minecolonies.kingdoms.citizen.SafeSpawnFinder;
import com.minecolonies.kingdoms.citizen.SettlementCitizenManager;
import com.minecolonies.kingdoms.citizen.StreetRouter;
import com.minecolonies.kingdoms.citizen.StuckTracker;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.contract.ContractText;
import com.minecolonies.kingdoms.diplomacy.ReputationRegistry;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.entity.bandit.BanditEntity;
import com.minecolonies.kingdoms.entity.caravan.ModEntities;
import com.minecolonies.kingdoms.entity.guard.SettlementGuardEntity;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingRecord;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingStatus;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetNetwork;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Physical guards (Phase 9): a few soldiers of a garrison, near players only. Every {@value #CYCLE_TICKS} ticks it
 * materializes patrols in settlements a player is close to (hysteresis, per-settlement/global/per-player caps,
 * entity-ticking chunks only, never loading anything), walks them between the gate, the plaza, and buildings along the
 * persisted local streets (the citizens' {@link StreetRouter}; no new path network), and sends a few responders to a
 * physical bandit fight near a settlement. It writes no strategic state: a guard that unloads, is discarded, or dies to
 * a mob is only a technical loss. The one strategic effect of a guard death — a responder killed by the bandits it was
 * sent to — is recorded on that bandit encounter and applied once when the encounter ends ({@link MilitaryService}).
 */
public final class GarrisonManager
{
    private static final GarrisonManager INSTANCE = new GarrisonManager();
    public static final int CYCLE_TICKS = 20;
    public static final long MANUAL_HOLD_TICKS = 1_200L;
    static final long RESPAWN_COOLDOWN_TICKS = 1_200L;
    static final long STUCK_TIMEOUT_TICKS = 160L;
    static final double ARRIVAL_DISTANCE = 2.0D;
    /** Patrol points and footprints are rebuilt at most this often per settlement (buildings change rarely). */
    static final long CONTEXT_TICKS = 600L;
    /** A guard that has chased a target this long without landing or taking a blow gives it up. */
    static final long FUTILE_CHASE_TICKS = 300L;
    static final int FUTILE_CHASE_IDLE_TICKS = 200;

    enum Role { PATROL, RESPONDER }

    private static final class Guard
    {
        private final UUID entityId;
        private final UUID settlementId;
        private final ResourceLocation dimension;
        private final Role role;
        private final UUID encounterId;
        private final UUID observer;
        private final StuckTracker stuck = new StuckTracker(STUCK_TIMEOUT_TICKS);
        private List<BlockPos> waypoints = List.of();
        private int index;
        private int step;
        private long fightingSince = -1L;

        private Guard(final UUID entityId, final UUID settlementId, final ResourceLocation dimension, final Role role, final UUID encounterId,
            final UUID observer, final int step)
        {
            this.entityId = entityId;
            this.settlementId = settlementId;
            this.dimension = dimension;
            this.role = role;
            this.encounterId = encounterId;
            this.observer = observer;
            this.step = step;
        }
    }

    private record RouterEntry(int signature, StreetRouter router) {}
    private record ContextEntry(long builtAt, PatrolContext context) {}

    private final Map<UUID, Guard> guards = new LinkedHashMap<>();
    private final Set<UUID> activeSettlements = new HashSet<>();
    /** Per settlement: when each recently fallen (or stuck) guard's slot opens again; only those slots wait. */
    private final Map<UUID, java.util.Deque<Long>> slotCooldowns = new HashMap<>();
    private final Map<UUID, Long> heldUntil = new HashMap<>();
    private final Map<UUID, Long> suppressedUntil = new HashMap<>();
    private final Map<UUID, Integer> responded = new HashMap<>();
    private final Map<UUID, RouterEntry> routers = new HashMap<>();
    private final Map<UUID, ContextEntry> contexts = new HashMap<>();
    private long cycles;
    private long totalNanos;
    private long maximumNanos;
    private long materialized;
    private long dematerialized;
    private long deaths;
    private long respondersSent;
    private long stuckRecoveries;
    private long failedSpawns;
    private long failures;
    private long spawnSerial;
    private MinecraftServer server;

    private GarrisonManager() {}
    public static GarrisonManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        clearRuntime();
        server = value;
        cycles = totalNanos = maximumNanos = materialized = dematerialized = deaths = respondersSent = stuckRecoveries = failedSpawns = failures = 0L;
    }

    public void shutdown()
    {
        clearRuntime();
        server = null;
    }

    private void clearRuntime()
    {
        guards.clear();
        activeSettlements.clear();
        slotCooldowns.clear();
        heldUntil.clear();
        suppressedUntil.clear();
        responded.clear();
        routers.clear();
        contexts.clear();
    }

    // ------------------------------------------------------------------------------------------------ cycle

    public void tick(final MinecraftServer value)
    {
        if (server == null || server != value) return;
        final long gameTime = value.overworld().getGameTime();
        if (gameTime % CYCLE_TICKS != 0) return;
        final long started = System.nanoTime();
        try
        {
            cycle(KingdomsSavedData.get(value.overworld()), gameTime, settingsFromConfig());
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error("Garrison guard update failed", exception);
        }
        final long elapsed = System.nanoTime() - started;
        cycles++;
        totalNanos += elapsed;
        maximumNanos = Math.max(maximumNanos, elapsed);
    }

    private void cycle(final KingdomsSavedData data, final long gameTime, final SecuritySettings settings)
    {
        if (!settings.enabled() || !settings.guardsEnabled())
        {
            List.copyOf(guards.values()).forEach(this::dematerialize);
            activeSettlements.clear();
            responded.clear();
            return;
        }
        for (final Guard guard : List.copyOf(guards.values()))
            if (!(entity(guard) instanceof SettlementGuardEntity entity) || entity.isRemoved()) guards.remove(guard.entityId); // technical loss
        final Map<UUID, Integer> perPlayer = new HashMap<>();
        guards.values().forEach(guard -> { if (guard.observer != null) perPlayer.merge(guard.observer, 1, Integer::sum); });
        final List<SettlementRecord> eligible = eligible(data);
        final Set<UUID> eligibleIds = new HashSet<>();
        eligible.forEach(settlement -> eligibleIds.add(settlement.id()));
        for (final Guard guard : List.copyOf(guards.values())) if (!eligibleIds.contains(guard.settlementId)) dematerialize(guard);
        activeSettlements.retainAll(eligibleIds);
        routers.keySet().retainAll(eligibleIds);
        contexts.keySet().retainAll(activeSettlements);
        for (final SettlementRecord settlement : eligible)
        {
            try
            {
                patrol(data, settlement, gameTime, settings, perPlayer);
            }
            catch (RuntimeException exception)
            {
                failures++;
                KingdomsMod.LOGGER.error("Guards of settlement {} failed to update", settlement.id(), exception);
            }
        }
        respond(data, eligible, gameTime, settings, perPlayer);
        slotCooldowns.values().forEach(queue -> queue.removeIf(until -> until <= gameTime));
        slotCooldowns.values().removeIf(java.util.Deque::isEmpty);
        heldUntil.values().removeIf(until -> until <= gameTime);
        suppressedUntil.values().removeIf(until -> until <= gameTime);
    }

    /** NPC settlements whose garrison has soldiers at home, in a fixed order. */
    private static List<SettlementRecord> eligible(final KingdomsSavedData data)
    {
        return data.settlements().records().stream()
            .filter(settlement -> data.colony(settlement.id()).map(colony -> colony.kind() == ColonyKind.NPC_ABSTRACT).orElse(false))
            .filter(settlement -> data.military().garrison(settlement.id()).map(garrison -> garrison.strength() > 0).orElse(false))
            .sorted(Comparator.comparing(SettlementRecord::id)).toList();
    }

    private void patrol(final KingdomsSavedData data, final SettlementRecord settlement, final long gameTime, final SecuritySettings settings,
        final Map<UUID, Integer> perPlayer)
    {
        final ServerLevel level = level(settlement.dimension());
        final ServerPlayer player = level == null ? null : nearestPlayer(level, Vec3.atCenterOf(settlement.anchor()));
        final double distance = player == null ? Double.POSITIVE_INFINITY : Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(settlement.anchor())));
        final boolean held = heldUntil.getOrDefault(settlement.id(), Long.MIN_VALUE) > gameTime;
        final boolean suppressed = suppressedUntil.getOrDefault(settlement.id(), Long.MIN_VALUE) > gameTime;
        final boolean was = activeSettlements.contains(settlement.id());
        // appearing needs an entity-ticking anchor; staying only a loaded one (no flicker at the simulation-distance edge)
        final boolean now = level != null && !suppressed
            && (was ? level.hasChunkAt(settlement.anchor()) : level.isPositionEntityTicking(settlement.anchor()))
            && (held || GuardBudget.active(was, distance, settings));
        final List<Guard> here = guards.values().stream().filter(guard -> guard.role == Role.PATROL && guard.settlementId.equals(settlement.id())).toList();
        if (!now)
        {
            here.forEach(this::dematerialize);
            activeSettlements.remove(settlement.id());
            return;
        }
        activeSettlements.add(settlement.id());
        final GarrisonRecord garrison = data.military().garrison(settlement.id()).orElseThrow();
        final int waiting = (int) slotCooldowns.getOrDefault(settlement.id(), new java.util.ArrayDeque<>()).stream().filter(until -> until > gameTime).count();
        final int target = Math.max(0, GuardBudget.patrolTarget(garrison.strength(), settings.maxGuardsPerSettlement()) - waiting);
        for (int index = here.size() - 1; index >= target; index--) dematerialize(here.get(index));
        final ContextEntry cachedContext = contexts.get(settlement.id());
        final PatrolContext context;
        if (cachedContext != null && gameTime - cachedContext.builtAt() < CONTEXT_TICKS) context = cachedContext.context();
        else
        {
            context = context(data, settlement, level);
            contexts.put(settlement.id(), new ContextEntry(gameTime, context));
        }
        for (final Guard guard : guards.values().stream().filter(value -> value.role == Role.PATROL && value.settlementId.equals(settlement.id())).toList())
            advance(guard, context, gameTime);
        final int present = Math.min(here.size(), target);
        if (present >= target) return;
        final UUID observer = player == null ? null : player.getUUID();
        if (GuardBudget.allowance(1, guards.size(), settings.maxGuardsGlobal(), observer == null ? 0 : perPlayer.getOrDefault(observer, 0),
            observer == null ? Integer.MAX_VALUE : settings.maxGuardsPerPlayer()) <= 0) return;
        final BlockPos origin = present % 2 == 0 ? context.gate() : context.plaza();
        if (spawn(level, settlement, context.style(), origin, context.footprints(), Role.PATROL, null, observer, present) != null && observer != null)
            perPlayer.merge(observer, 1, Integer::sum);
    }

    private void respond(final KingdomsSavedData data, final List<SettlementRecord> eligible, final long gameTime, final SecuritySettings settings,
        final Map<UUID, Integer> perPlayer)
    {
        final List<BanditManager.PhysicalSite> sites = BanditManager.getInstance().physicalSites();
        final Set<UUID> live = new HashSet<>();
        sites.forEach(site -> live.add(site.encounterId()));
        final Map<UUID, BlockPos> positions = new HashMap<>();
        sites.forEach(site -> positions.put(site.encounterId(), site.position()));
        for (final Guard guard : List.copyOf(guards.values()))
        {
            if (guard.role != Role.RESPONDER) continue;
            if (!live.contains(guard.encounterId))
            {
                dematerialize(guard);
                continue;
            }
            if (entity(guard) instanceof SettlementGuardEntity entity && !fighting(guard, entity, gameTime))
                entity.planTarget(Vec3.atBottomCenterOf(positions.get(guard.encounterId)));
        }
        responded.keySet().retainAll(live);
        if (settings.maxResponders() <= 0 || settings.responseRadius() <= 0) return;
        final double limit = (double) settings.responseRadius() * settings.responseRadius();
        for (final BanditManager.PhysicalSite site : sites)
        {
            SettlementRecord best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (final SettlementRecord settlement : eligible)
            {
                if (!settlement.dimension().equals(site.dimension())) continue;
                final double distance = settlement.anchor().distSqr(site.position());
                if (distance <= limit && distance < bestDistance)
                {
                    best = settlement;
                    bestDistance = distance;
                }
            }
            if (best == null) continue;
            final GarrisonRecord garrison = data.military().garrison(best.id()).orElse(null);
            if (garrison == null) continue;
            // soldiers already lost to this fight (applied when it ends) are not sent again
            final int pending = data.bandits().encounter(site.encounterId())
                .map(encounter -> encounter.garrisonLosses().getOrDefault(garrison.settlementId(), 0)).orElse(0);
            final int wanted = GuardBudget.responders(garrison.strength() - pending, settings.maxResponders())
                - responded.getOrDefault(site.encounterId(), 0);
            if (wanted <= 0) continue;
            final ServerLevel level = level(site.dimension());
            if (level == null || !level.isPositionEntityTicking(site.position())) continue;
            final ServerPlayer player = nearestPlayer(level, Vec3.atCenterOf(site.position()));
            final UUID observer = player == null ? null : player.getUUID();
            final int allowed = GuardBudget.allowance(wanted, guards.size(), settings.maxGuardsGlobal(),
                observer == null ? 0 : perPlayer.getOrDefault(observer, 0), observer == null ? Integer.MAX_VALUE : settings.maxGuardsPerPlayer());
            // arrive from the settlement's side, 12-16 blocks from the fight
            final Vec3 from = Vec3.atCenterOf(best.anchor()).subtract(Vec3.atCenterOf(site.position()));
            final Vec3 direction = from.lengthSqr() < 1.0E-6D ? new Vec3(1, 0, 0) : new Vec3(from.x, 0, from.z).normalize();
            final String style = data.growth().layout(best.id()).map(SettlementLayoutPlan::styleFamily).orElse("");
            int sent = 0;
            for (int index = 0; index < allowed; index++)
            {
                final BlockPos origin = BlockPos.containing(Vec3.atCenterOf(site.position()).add(direction.scale(12.0D + 2.0D * index)));
                if (spawn(level, best, style, origin, List.of(), Role.RESPONDER, site.encounterId(), observer, index) == null) break;
                sent++;
                if (observer != null) perPlayer.merge(observer, 1, Integer::sum);
            }
            if (sent > 0)
            {
                responded.merge(site.encounterId(), sent, Integer::sum);
                respondersSent += sent;
                final String name = best.name();
                final double radius = 64.0D;
                level.players().stream().filter(value -> value.distanceToSqr(Vec3.atCenterOf(site.position())) <= radius * radius)
                    .forEach(value -> value.sendSystemMessage(Component.literal("Guards from " + name + " come to help!").withStyle(ChatFormatting.AQUA)));
            }
        }
    }

    private SettlementGuardEntity spawn(final ServerLevel level, final SettlementRecord settlement, final String style, final BlockPos origin,
        final List<StructureFootprint> footprints, final Role role, final UUID encounterId, final UUID observer, final int step)
    {
        final Optional<BlockPos> position = SafeSpawnFinder.find(origin, 4, 4, SettlementCitizenManager.view(level), footprints);
        if (position.isEmpty())
        {
            failedSpawns++;
            return null;
        }
        final SettlementGuardEntity entity = ModEntities.SETTLEMENT_GUARD.get().create(level);
        if (entity == null) return null;
        final long seed = settlement.id().getLeastSignificantBits() ^ (++spawnSerial * 0x9E3779B97F4A7C15L);
        final boolean female = Math.floorMod(seed >>> 7, 4L) == 0L;
        entity.configure(settlement.id(), settlement.name() + " Guard", CitizenAppearance.textureKey(style, CitizenRole.GUARD, female, seed),
            encounterId);
        entity.moveTo(position.get().getX() + 0.5D, position.get().getY(), position.get().getZ() + 0.5D, (float) Math.floorMod(seed, 360L), 0.0F);
        if (role == Role.RESPONDER && encounterId != null) entity.planTarget(Vec3.atBottomCenterOf(origin));
        if (!level.addFreshEntity(entity))
        {
            failedSpawns++;
            return null;
        }
        guards.put(entity.getUUID(), new Guard(entity.getUUID(), settlement.id(), settlement.dimension(), role, encounterId, observer, step));
        materialized++;
        return entity;
    }

    // ------------------------------------------------------------------------------------------------ patrol routes

    private record PatrolContext(BlockPos gate, BlockPos plaza, List<BlockPos> points, List<StructureFootprint> footprints, StreetRouter router,
        String style) {}

    private PatrolContext context(final KingdomsSavedData data, final SettlementRecord settlement, final ServerLevel level)
    {
        final SettlementLayoutPlan layout = data.growth().layout(settlement.id()).orElse(null);
        BlockPos plaza = settlement.anchor();
        if (layout != null)
            for (final var segment : layout.streets().segments())
                if (SettlementLayoutPlanner.PLAZA_PURPOSE.equals(segment.purpose())) plaza = segment.points().getFirst();
        final List<BlockPos> points = new ArrayList<>();
        points.add(settlement.gate());
        points.add(plaza);
        final List<StructureFootprint> footprints = new ArrayList<>();
        for (final SettlementBuildingRecord building : data.growth().forSettlement(settlement.id()))
        {
            footprints.add(building.footprint());
            if (building.status() == SettlementBuildingStatus.COMPLETED && points.size() < 6) points.add(building.entrance());
        }
        return new PatrolContext(settlement.gate(), plaza, List.copyOf(points), List.copyOf(footprints), router(settlement.id(), layout),
            layout == null ? "" : layout.styleFamily());
    }

    private StreetRouter router(final UUID settlementId, final SettlementLayoutPlan layout)
    {
        final int signature = layout == null ? 0 : layout.streets().segments().size();
        final RouterEntry cached = routers.get(settlementId);
        if (cached != null && cached.signature() == signature) return cached.router();
        final StreetRouter router = new StreetRouter(layout == null ? new SettlementLayoutPlan(settlementId, "", new SettlementStreetNetwork()) : layout);
        routers.put(settlementId, new RouterEntry(signature, router));
        return router;
    }

    private void advance(final Guard guard, final PatrolContext context, final long gameTime)
    {
        if (!(entity(guard) instanceof SettlementGuardEntity entity)) return;
        if (fighting(guard, entity, gameTime)) return;
        if (guard.index >= guard.waypoints.size())
        {
            final BlockPos next = context.points().get(Math.floorMod(guard.step++, context.points().size()));
            guard.waypoints = context.router().route(entity.blockPosition(), next);
            if (guard.waypoints.isEmpty()) guard.waypoints = List.of(next);
            guard.index = 0;
            guard.stuck.arrived();
        }
        final BlockPos target = guard.waypoints.get(guard.index);
        final double dx = entity.getX() - (target.getX() + 0.5D);
        final double dz = entity.getZ() - (target.getZ() + 0.5D);
        final double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < ARRIVAL_DISTANCE)
        {
            guard.index++;
            guard.stuck.arrived();
        }
        else switch (guard.stuck.update(distance, gameTime))
        {
            case SKIP_WAYPOINT -> guard.index++;
            case GIVE_UP ->
            {
                stuckRecoveries++;
                slotCooldown(guard.settlementId, gameTime + STUCK_TIMEOUT_TICKS);
                dematerialize(guard);
                return;
            }
            case MOVING -> { }
        }
        entity.planTarget(guard.index < guard.waypoints.size() ? Vec3.atBottomCenterOf(guard.waypoints.get(guard.index)) : null);
    }

    /**
     * Whether the guard is busy fighting. A real fight is progress; a chase that has neither landed nor taken a blow for
     * a while (a target it cannot reach) is given up, so the guard goes back to its route.
     */
    private static boolean fighting(final Guard guard, final SettlementGuardEntity entity, final long gameTime)
    {
        if (entity.getTarget() == null)
        {
            guard.fightingSince = -1L;
            return false;
        }
        if (guard.fightingSince < 0L) guard.fightingSince = gameTime;
        final int idle = entity.tickCount - Math.max(entity.getLastHurtMobTimestamp(), entity.getLastHurtByMobTimestamp());
        if (gameTime - guard.fightingSince >= FUTILE_CHASE_TICKS && idle >= FUTILE_CHASE_IDLE_TICKS)
        {
            entity.setTarget(null);
            guard.fightingSince = -1L;
            return false;
        }
        guard.stuck.arrived();
        return true;
    }

    // ------------------------------------------------------------------------------------------------ entity callbacks

    public boolean isCurrent(final SettlementGuardEntity entity)
    {
        final Guard guard = guards.get(entity.getUUID());
        return guard != null && guard.settlementId.equals(entity.settlementId());
    }

    /** Whether guards may have enemies beyond bandits and monsters right now (enemy soldiers at war, Phase 10). */
    public boolean hostilityActive()
    {
        return GuardEvents.current().active();
    }

    /** Whether a guard should attack this entity beyond bandits and monsters (enemy soldiers at war, Phase 10). */
    public boolean hostileTo(final SettlementGuardEntity guard, final LivingEntity target)
    {
        return GuardEvents.current().hostile(guard, target);
    }

    public void onGuardHurt(final SettlementGuardEntity entity)
    {
        final Guard guard = guards.get(entity.getUUID());
        if (guard != null) guard.stuck.arrived();
    }

    /**
     * A guard died. Only a responder killed by the bandits it was sent to is a strategic casualty, recorded on that
     * encounter and applied once when it ends. A player who kills a guard loses reputation with the settlement's faction.
     * Anything else (mobs, falls, lava) is a technical loss: the garrison is unchanged and the guard reappears later.
     */
    public void onGuardDeath(final SettlementGuardEntity entity, final DamageSource source)
    {
        if (!isCurrent(entity) || server == null) return;
        final Guard guard = guards.remove(entity.getUUID());
        final long gameTime = server.overworld().getGameTime();
        if (guard.role == Role.PATROL) slotCooldown(guard.settlementId, gameTime + RESPAWN_COOLDOWN_TICKS);
        deaths++;
        final Entity killer = source.getEntity();
        if (guard.role == Role.RESPONDER && guard.encounterId != null && killer instanceof BanditEntity bandit
            && guard.encounterId.equals(bandit.encounterId()) && BanditManager.getInstance().isCurrent(bandit))
        {
            BanditManager.getInstance().recordGarrisonLoss(guard.encounterId, guard.settlementId);
            return;
        }
        if (GuardEvents.current().guardKilled(entity, killer)) return; // a defender fallen in a siege: judged by the battle
        if (!(killer instanceof ServerPlayer player) || player instanceof FakePlayer) return; // machines are not people
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        final NPCColonyData colony = data.colony(guard.settlementId).orElse(null);
        final int penalty = KingdomsConfig.SERVER.reputationKillPenalty.get();
        if (colony == null || penalty <= 0) return;
        final ReputationService.Result result = ReputationService.adjust(data, player.getUUID(), colony.factionId(), -penalty,
            ReputationRegistry.Cause.GUARD_KILLED, entity.getUUID(), gameTime);
        if (result.isEmpty()) return;
        player.sendSystemMessage(Component.literal("The guards of " + colony.name() + " will remember this.").withStyle(ChatFormatting.RED));
        player.sendSystemMessage(ContractText.reputation(data, result));
    }

    // ------------------------------------------------------------------------------------------------ operator API

    public int materializeNow(final MinecraftServer value, final UUID settlementId)
    {
        suppressedUntil.remove(settlementId);
        slotCooldowns.remove(settlementId);
        heldUntil.put(settlementId, value.overworld().getGameTime() + MANUAL_HOLD_TICKS);
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final SecuritySettings settings = settingsFromConfig();
        final SettlementRecord settlement = eligible(data).stream().filter(record -> record.id().equals(settlementId)).findFirst().orElse(null);
        if (settlement == null || !settings.enabled() || !settings.guardsEnabled()) return guardsOf(settlementId);
        final Map<UUID, Integer> perPlayer = new HashMap<>();
        guards.values().forEach(guard -> { if (guard.observer != null) perPlayer.merge(guard.observer, 1, Integer::sum); });
        for (int attempt = 0; attempt < settings.maxGuardsPerSettlement(); attempt++)
            patrol(data, settlement, value.overworld().getGameTime(), settings, perPlayer);
        return guardsOf(settlementId);
    }

    public int dematerializeNow(final MinecraftServer value, final UUID settlementId)
    {
        heldUntil.remove(settlementId);
        suppressedUntil.put(settlementId, value.overworld().getGameTime() + MANUAL_HOLD_TICKS);
        final int count = guardsOf(settlementId);
        List.copyOf(guards.values()).stream().filter(guard -> guard.settlementId.equals(settlementId)).forEach(this::dematerialize);
        activeSettlements.remove(settlementId);
        return count;
    }

    public int guardsOf(final UUID settlementId)
    {
        return (int) guards.values().stream().filter(guard -> guard.settlementId.equals(settlementId)).count();
    }

    public int respondersOf(final UUID settlementId)
    {
        return (int) guards.values().stream().filter(guard -> guard.role == Role.RESPONDER && guard.settlementId.equals(settlementId)).count();
    }

    public int physicalGuards() { return guards.size(); }
    public int activeSettlements() { return activeSettlements.size(); }

    public record Stats(long cycles, double averageNanos, long maximumNanos, long materialized, long dematerialized, long deaths,
        long respondersSent, long stuckRecoveries, long failedSpawns, long failures) {}

    public Stats stats()
    {
        return new Stats(cycles, cycles == 0 ? 0.0D : (double) totalNanos / cycles, maximumNanos, materialized, dematerialized, deaths,
            respondersSent, stuckRecoveries, failedSpawns, failures);
    }

    // ------------------------------------------------------------------------------------------------ internals

    private void slotCooldown(final UUID settlementId, final long until)
    {
        final java.util.Deque<Long> queue = slotCooldowns.computeIfAbsent(settlementId, key -> new java.util.ArrayDeque<>());
        if (queue.size() < 16) queue.addLast(until);
    }

    private void dematerialize(final Guard guard)
    {
        guards.remove(guard.entityId);
        if (entity(guard) instanceof SettlementGuardEntity entity) entity.discardByManager();
        dematerialized++;
    }

    private Entity entity(final Guard guard)
    {
        final ServerLevel level = level(guard.dimension);
        return level == null ? null : level.getEntity(guard.entityId);
    }

    private ServerLevel level(final ResourceLocation dimension)
    {
        return server == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private static ServerPlayer nearestPlayer(final ServerLevel level, final Vec3 position)
    {
        return level.players().stream().filter(player -> !player.isSpectator() && player.isAlive())
            .min(Comparator.comparingDouble(player -> player.distanceToSqr(position))).orElse(null);
    }

    public static SecuritySettings settingsFromConfig()
    {
        final var config = KingdomsConfig.SERVER;
        return new SecuritySettings(config.securityEnabled.get(), config.securityEvaluationIntervalTicks.get(), config.guardsEnabled.get(),
            config.guardsMaterializationRadius.get(), Math.max(config.guardsDematerializationRadius.get(), config.guardsMaterializationRadius.get() + 16),
            config.guardsMaxPerSettlement.get(), config.guardsMaxGlobal.get(), config.guardsMaxPerPlayer.get(), config.guardsResponseRadius.get(),
            config.guardsMaxResponders.get(), config.securityPatrolRadius.get(), config.securitySortieCooldownTicks.get(),
            config.securitySortieMinimumStrength.get(), config.securityRecruitmentMultiplier.get());
    }
}
