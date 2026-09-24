package com.minecolonies.kingdoms.caravan;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.entity.caravan.CaravanMemberEntity;
import com.minecolonies.kingdoms.entity.caravan.ModEntities;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.ShipmentRepresentation;
import com.minecolonies.kingdoms.trade.TradeManager;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import com.minecolonies.kingdoms.world.road.ShipmentPathResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CaravanManager
{
    private final ShipmentPathResolver shipmentPaths = new ShipmentPathResolver();
    private static final CaravanManager INSTANCE = new CaravanManager();
    private static final long MANUAL_HOLD_TICKS = 600L;
    private final Map<UUID, UUID> entityToCaravan = new HashMap<>();
    private final CaravanProfiler profiler = new CaravanProfiler();
    private MinecraftServer server;
    private long nextUpdate;

    private CaravanManager()
    {
    }

    public static CaravanManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer minecraftServer)
    {
        server = minecraftServer;
        entityToCaravan.clear();
        profiler.reset();
        final KingdomsSavedData data = KingdomsSavedData.get(minecraftServer.overworld());
        final long gameTime = minecraftServer.overworld().getGameTime();
        final int normalized = normalizeAfterRestart(data, gameTime);
        if (!data.caravanLedger().instances().isEmpty() || normalized > 0)
        {
            data.caravanLedger().clear();
            data.markChanged();
            KingdomsMod.LOGGER.info("Normalized {} physical shipments after server restart", normalized);
        }
        nextUpdate = gameTime;
    }

    public static int normalizeAfterRestart(final KingdomsSavedData data, final long gameTime)
    {
        final boolean hadInstances = !data.caravanLedger().instances().isEmpty();
        int normalized = 0;
        for (final TradeShipment shipment : data.tradeLedger().shipments())
        {
            if (shipment.status() == TradeShipmentStatus.IN_TRANSIT
                && shipment.representation() == ShipmentRepresentation.PHYSICAL)
            {
                shipment.dematerialize(gameTime);
                normalized++;
            }
        }
        if (!data.caravanLedger().instances().isEmpty()) data.caravanLedger().clear();
        if (normalized > 0 || hadInstances) data.markChanged();
        return normalized;
    }

    public void shutdown()
    {
        server = null;
        entityToCaravan.clear();
        nextUpdate = 0L;
        profiler.reset();
    }

    public void tick(final MinecraftServer minecraftServer)
    {
        if (!KingdomsConfig.SERVER.caravanEnabled.get()) return;
        final long gameTime = minecraftServer.overworld().getGameTime();
        if (gameTime < nextUpdate) return;
        final CaravanSettings settings = settingsFromConfig();
        final KingdomsSavedData data = KingdomsSavedData.get(minecraftServer.overworld());
        updatePhysical(data, gameTime, settings);
        materializeNearby(data, gameTime, settings);
        nextUpdate = gameTime + settings.updateIntervalTicks();
    }

    public CaravanStats stats(final KingdomsSavedData data)
    {
        return profiler.snapshot(data.caravanLedger().instances().size());
    }

    public boolean isRegistered(final UUID caravanId, final UUID shipmentId, final UUID entityId)
    {
        if (server == null || caravanId == null || shipmentId == null) return false;
        final CaravanInstance instance = KingdomsSavedData.get(server.overworld()).caravanLedger()
            .forShipment(shipmentId).orElse(null);
        return instance != null && instance.id().equals(caravanId) && instance.entityIds().contains(entityId);
    }

    public void onCriticalMemberDestroyed(
        final ServerLevel level,
        final UUID caravanId,
        final UUID shipmentId)
    {
        if (caravanId == null || shipmentId == null) return;
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final CaravanInstance instance = data.caravanLedger().forShipment(shipmentId).orElse(null);
        if (instance == null || !instance.id().equals(caravanId)) return;
        if (com.minecolonies.kingdoms.bandit.BanditManager.getInstance().onCaravanOverrun(shipmentId))
        {
            // bandits hold this caravan: the encounter decided the cargo (partial loss); the rest travels on abstractly
            final TradeShipment shipment = data.tradeLedger().shipment(shipmentId).orElse(null);
            if (shipment != null && shipment.status() == TradeShipmentStatus.IN_TRANSIT)
            {
                dematerialize(data, instance, shipment, level.getGameTime(), false);
                return;
            }
            removePhysicalEntities(instance);
            data.caravanLedger().removeForShipment(shipmentId);
            data.markChanged();
            return;
        }
        instance.state(CaravanState.DESTROYED);
        TradeManager.getInstance().failDestroyedCaravan(data, shipmentId);
        removePhysicalEntities(instance);
        data.caravanLedger().removeForShipment(shipmentId);
        profiler.recordLifetime(Math.max(0L, level.getGameTime() - instance.createdAt()));
        data.markChanged();
    }

    public void describeTo(final net.minecraft.world.entity.player.Player player, final UUID shipmentId)
    {
        if (!(player.level() instanceof ServerLevel level) || shipmentId == null) return;
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final TradeShipment shipment = data.tradeLedger().shipment(shipmentId).orElse(null);
        if (shipment == null)
        {
            player.sendSystemMessage(Component.literal("This caravan is orphaned."));
            return;
        }
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
            "Caravan %s: %d %s, %s -> %s, %.1f%%",
            shipment.id(), shipment.amount(), shipment.resource(),
            colonyName(data, shipment.originColonyId()), colonyName(data, shipment.destinationColonyId()),
            shipment.progressAt(level.getGameTime()) * 100.0D)));
    }

    public boolean materializeNow(final MinecraftServer minecraftServer, final UUID shipmentId)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(minecraftServer.overworld());
        final TradeShipment shipment = data.tradeLedger().shipment(shipmentId).orElse(null);
        if (shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT
            || shipment.representation() != ShipmentRepresentation.ABSTRACT
            || data.caravanLedger().instances().size() >= KingdomsConfig.SERVER.caravanMaxPhysical.get())
        {
            return false;
        }
        return materialize(data, shipment, minecraftServer.overworld().getGameTime(), null,
            minecraftServer.overworld().getGameTime() + MANUAL_HOLD_TICKS, settingsFromConfig());
    }

    public boolean dematerializeNow(final MinecraftServer minecraftServer, final UUID shipmentId)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(minecraftServer.overworld());
        final CaravanInstance instance = data.caravanLedger().forShipment(shipmentId).orElse(null);
        final TradeShipment shipment = data.tradeLedger().shipment(shipmentId).orElse(null);
        if (instance == null || shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT) return false;
        dematerialize(data, instance, shipment, minecraftServer.overworld().getGameTime(), false);
        return true;
    }

    public boolean teleportNear(final ServerLevel level, final UUID shipmentId, final Vec3 target)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final CaravanInstance instance = data.caravanLedger().forShipment(shipmentId).orElse(null);
        final TradeShipment shipment = data.tradeLedger().shipment(shipmentId).orElse(null);
        if (instance == null || shipment == null || !instance.dimension().equals(level.dimension().location())) return false;
        final Vec3 grounded = ground(level, target);
        int index = 0;
        for (final UUID entityId : instance.entityIds())
        {
            final Entity entity = level.getEntity(entityId);
            if (entity != null)
            {
                entity.teleportTo(grounded.x + index % 2, grounded.y, grounded.z + index / 2);
                index++;
            }
        }
        final ShipmentPath path = path(data, shipment);
        if (path == null) return false;
        final double progress = path.projectProgress(grounded);
        shipment.updatePhysicalProgress(progress, level.getGameTime());
        instance.update(grounded, progress, level.getGameTime());
        instance.forcedUntil(level.getGameTime() + MANUAL_HOLD_TICKS);
        data.markChanged();
        return true;
    }

    private void updatePhysical(final KingdomsSavedData data, final long gameTime, final CaravanSettings settings)
    {
        for (final CaravanInstance instance : List.copyOf(data.caravanLedger().instances()))
        {
            final TradeShipment shipment = data.tradeLedger().shipment(instance.shipmentId()).orElse(null);
            final ServerLevel level = level(instance.dimension());
            if (shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT
                || shipment.representation() != ShipmentRepresentation.PHYSICAL || level == null)
            {
                removePhysicalEntities(instance);
                data.caravanLedger().removeForShipment(instance.shipmentId());
                data.markChanged();
                continue;
            }
            final CaravanMemberEntity leader = criticalMember(level, instance, CaravanMemberRole.LEADER);
            final CaravanMemberEntity carrier = criticalMember(level, instance, CaravanMemberRole.CARRIER);
            if (leader == null || carrier == null)
            {
                dematerialize(data, instance, shipment, gameTime, true);
                continue;
            }
            final double nearest = nearestPlayerDistance(level, leader.position());
            if (gameTime > instance.forcedUntil()
                && CaravanEligibility.shouldDematerialize(shipment.representation(), nearest, settings))
            {
                dematerialize(data, instance, shipment, gameTime, false);
                continue;
            }
            if (com.minecolonies.kingdoms.bandit.BanditManager.getInstance().holds(shipment.id()))
            {
                // an active bandit encounter holds the caravan: it stops, its guards fight, progress waits
                leader.getNavigation().stop();
                instance.hold(gameTime);
                defendCaravan(level, instance);
                data.markChanged();
                continue;
            }
            final ShipmentPath path = path(data, shipment);
            if (path == null)
            {
                dematerialize(data, instance, shipment, gameTime, true);
                continue;
            }
            final double progress = path.projectProgress(leader.position());
            shipment.updatePhysicalProgress(progress, gameTime);
            instance.update(leader.position(), progress, gameTime);
            if (progress >= 0.995D || horizontalDistanceSqr(leader.position(), instance.destination()) <= 36.0D)
            {
                TradeManager.getInstance().completePhysicalShipment(data, shipment.id(), gameTime);
                removePhysicalEntities(instance);
                data.caravanLedger().removeForShipment(shipment.id());
                profiler.recordLifetime(gameTime - instance.createdAt());
                data.markChanged();
                continue;
            }
            if (gameTime - instance.lastMovementAt() >= settings.stuckTimeoutTicks())
            {
                profiler.stuckRecovery();
                dematerialize(data, instance, shipment, gameTime, true);
                continue;
            }
            if (gameTime - instance.lastPathAttemptAt() >= settings.pathRetryIntervalTicks()
                || leader.getNavigation().isDone())
            {
                final Vec3 rawWaypoint = path.localWaypoint(progress, settings.localWaypointDistance());
                if (!level.hasChunkAt(BlockPos.containing(rawWaypoint)))
                {
                    dematerialize(data, instance, shipment, gameTime, true);
                    continue;
                }
                final Vec3 waypoint = ground(level, rawWaypoint);
                if (!leader.getNavigation().moveTo(waypoint.x, waypoint.y, waypoint.z, 1.0D)) profiler.pathFailure();
                instance.markPathAttempt(gameTime);
            }
            followLeader(level, instance, leader);
            defendCaravan(level, instance);
            data.markChanged();
        }
    }

    private void materializeNearby(final KingdomsSavedData data, final long gameTime, final CaravanSettings settings)
    {
        final List<Candidate> candidates = new ArrayList<>();
        for (final TradeShipment shipment : data.tradeLedger().shipments())
        {
            if (shipment.status() != TradeShipmentStatus.IN_TRANSIT
                || shipment.representation() != ShipmentRepresentation.ABSTRACT) continue;
            final ShipmentPath path = path(data, shipment);
            if (path == null) continue;
            final Vec3 position = path.positionAt(shipment.progressAt(gameTime));
            final ServerLevel level = level(path.dimension());
            if (level == null) continue;
            final ServerPlayer player = nearestPlayer(level, position);
            if (player != null) candidates.add(new Candidate(shipment, player, player.distanceToSqr(position)));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared).thenComparing(c -> c.shipment().id()));
        final Map<UUID, Integer> perPlayer = new HashMap<>();
        data.caravanLedger().instances().forEach(instance -> instance.observerPlayerId()
            .ifPresent(id -> perPlayer.merge(id, 1, Integer::sum)));
        int global = data.caravanLedger().instances().size();
        for (final Candidate candidate : candidates)
        {
            final int playerCount = perPlayer.getOrDefault(candidate.player().getUUID(), 0);
            if (!CaravanEligibility.shouldMaterialize(candidate.shipment().representation(),
                Math.sqrt(candidate.distanceSquared()), global, playerCount, settings)) continue;
            if (materialize(data, candidate.shipment(), gameTime, candidate.player().getUUID(), 0L, settings))
            {
                global++;
                perPlayer.merge(candidate.player().getUUID(), 1, Integer::sum);
            }
        }
    }

    private boolean materialize(
        final KingdomsSavedData data,
        final TradeShipment shipment,
        final long gameTime,
        final UUID observer,
        final long forcedUntil,
        final CaravanSettings settings)
    {
        final ShipmentPath path = path(data, shipment);
        if (path == null) return false;
        final ServerLevel level = level(path.dimension());
        if (level == null) return false;
        final double progress = shipment.progressAt(gameTime);
        final BlockPos desired = BlockPos.containing(path.positionAt(progress));
        if (!level.hasChunkAt(desired)) return false;
        final Vec3 position = ground(level, path.positionAt(progress));
        shipment.materialize(gameTime);
        final CaravanInstance instance = new CaravanInstance(UUID.randomUUID(), shipment.id(), path.dimension(),
            path.positionAt(0.0D), path.positionAt(1.0D), position, progress, gameTime, observer, forcedUntil);
        data.caravanLedger().put(instance);
        final Set<UUID> entities = spawnMembers(level, instance, settings.debugNames());
        if (entities.size() != 4)
        {
            entities.forEach(id -> discard(level.getEntity(id)));
            data.caravanLedger().removeForShipment(shipment.id());
            shipment.dematerialize(gameTime);
            profiler.pathFailure();
            data.markChanged();
            return false;
        }
        instance.setEntities(entities);
        instance.state(CaravanState.TRAVELLING);
        entities.forEach(entityId -> entityToCaravan.put(entityId, instance.id()));
        profiler.materialized();
        data.markChanged();
        return true;
    }

    private void dematerialize(
        final KingdomsSavedData data,
        final CaravanInstance instance,
        final TradeShipment shipment,
        final long gameTime,
        final boolean recovery)
    {
        instance.state(CaravanState.DEMATERIALIZING);
        if (shipment.status() == TradeShipmentStatus.IN_TRANSIT
            && shipment.representation() == ShipmentRepresentation.PHYSICAL)
        {
            shipment.dematerialize(gameTime);
        }
        removePhysicalEntities(instance);
        data.caravanLedger().removeForShipment(shipment.id());
        profiler.dematerialized(gameTime - instance.createdAt());
        if (recovery) profiler.pathFailure();
        data.markChanged();
    }

    private Set<UUID> spawnMembers(final ServerLevel level, final CaravanInstance instance, final boolean debugNames)
    {
        final Set<UUID> ids = new LinkedHashSet<>();
        final CaravanMemberRole[] roles = {
            CaravanMemberRole.LEADER, CaravanMemberRole.CARRIER,
            CaravanMemberRole.GUARD, CaravanMemberRole.GUARD
        };
        for (int index = 0; index < roles.length; index++)
        {
            final CaravanMemberEntity entity = ModEntities.CARAVAN_MEMBER.get().create(level);
            if (entity == null) break;
            entity.configure(instance.id(), instance.shipmentId(), roles[index], debugNames);
            entity.moveTo(instance.position().x + index % 2, instance.position().y,
                instance.position().z + index / 2, 0.0F, 0.0F);
            if (!level.addFreshEntity(entity)) break;
            ids.add(entity.getUUID());
        }
        return ids;
    }

    private void followLeader(final ServerLevel level, final CaravanInstance instance, final CaravanMemberEntity leader)
    {
        int follower = 0;
        for (final UUID entityId : instance.entityIds())
        {
            final Entity raw = level.getEntity(entityId);
            if (!(raw instanceof CaravanMemberEntity member) || member == leader) continue;
            follower++;
            final double x = leader.getX() - 1.5D * follower;
            final double z = leader.getZ() + (follower % 2 == 0 ? 1.5D : -1.5D);
            if (member.distanceToSqr(x, leader.getY(), z) > 9.0D)
            {
                member.getNavigation().moveTo(x, leader.getY(), z, 1.05D);
            }
        }
    }

    private void defendCaravan(final ServerLevel level, final CaravanInstance instance)
    {
        final List<CaravanMemberEntity> members = instance.entityIds().stream()
            .map(level::getEntity)
            .filter(CaravanMemberEntity.class::isInstance)
            .map(CaravanMemberEntity.class::cast)
            .filter(Entity::isAlive)
            .toList();
        final Monster attacker = members.stream()
            .flatMap(member -> level.getEntitiesOfClass(Monster.class, member.getBoundingBox().inflate(12.0D)).stream())
            .filter(monster -> monster.getTarget() instanceof CaravanMemberEntity target
                && instance.id().equals(target.caravanId()))
            .findFirst().orElse(null);
        if (attacker == null) return;
        members.stream().filter(member -> member.role() == CaravanMemberRole.GUARD)
            .forEach(guard -> guard.setTarget(attacker));
    }

    private CaravanMemberEntity criticalMember(
        final ServerLevel level,
        final CaravanInstance instance,
        final CaravanMemberRole role)
    {
        for (final UUID entityId : instance.entityIds())
        {
            if (level.getEntity(entityId) instanceof CaravanMemberEntity member
                && member.role() == role && member.isAlive()) return member;
        }
        return null;
    }

    private void removePhysicalEntities(final CaravanInstance instance)
    {
        final ServerLevel level = level(instance.dimension());
        for (final UUID entityId : instance.entityIds())
        {
            entityToCaravan.remove(entityId);
            if (level != null) discard(level.getEntity(entityId));
        }
    }

    private static void discard(final Entity entity)
    {
        if (entity instanceof CaravanMemberEntity member) member.discardByManager();
        else if (entity != null) entity.discard();
    }

    private ShipmentPath path(final KingdomsSavedData data, final TradeShipment shipment)
    {
        return shipmentPaths.resolve(data, shipment);
    }

    private ServerLevel level(final ResourceLocation dimension)
    {
        return server == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private static Vec3 ground(final ServerLevel level, final Vec3 position)
    {
        final int x = (int) Math.floor(position.x);
        final int z = (int) Math.floor(position.z);
        return new Vec3(x + 0.5D, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5D);
    }

    private static ServerPlayer nearestPlayer(final ServerLevel level, final Vec3 position)
    {
        return level.players().stream().filter(player -> !player.isSpectator())
            .min(Comparator.comparingDouble(player -> player.distanceToSqr(position))).orElse(null);
    }

    private static double nearestPlayerDistance(final ServerLevel level, final Vec3 position)
    {
        final ServerPlayer player = nearestPlayer(level, position);
        return player == null ? Double.POSITIVE_INFINITY : Math.sqrt(player.distanceToSqr(position));
    }

    private static double horizontalDistanceSqr(final Vec3 left, final Vec3 right)
    {
        final double x = left.x - right.x;
        final double z = left.z - right.z;
        return x * x + z * z;
    }

    private static CaravanSettings settingsFromConfig()
    {
        return new CaravanSettings(
            KingdomsConfig.SERVER.caravanEnabled.get(),
            KingdomsConfig.SERVER.caravanMaterializationRadius.get(),
            KingdomsConfig.SERVER.effectiveCaravanDematerializationRadius(),
            KingdomsConfig.SERVER.caravanMaxPhysical.get(),
            KingdomsConfig.SERVER.caravanMaxPhysicalPerPlayer.get(),
            KingdomsConfig.SERVER.caravanUpdateIntervalTicks.get(),
            KingdomsConfig.SERVER.caravanLocalWaypointDistance.get(),
            KingdomsConfig.SERVER.caravanStuckTimeoutTicks.get(),
            KingdomsConfig.SERVER.caravanPathRetryIntervalTicks.get(),
            KingdomsConfig.SERVER.caravanDebugNames.get());
    }

    private static String colonyName(final KingdomsSavedData data, final UUID id)
    {
        return data.colony(id).map(NPCColonyData::name).orElse("<removed>");
    }

    private record Candidate(TradeShipment shipment, ServerPlayer player, double distanceSquared) {}
}
