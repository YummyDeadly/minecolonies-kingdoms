package com.minecolonies.kingdoms.war;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.citizen.CitizenAppearance;
import com.minecolonies.kingdoms.citizen.CitizenRole;
import com.minecolonies.kingdoms.citizen.SafeSpawnFinder;
import com.minecolonies.kingdoms.citizen.SettlementCitizenManager;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.contract.ContractText;
import com.minecolonies.kingdoms.diplomacy.ReputationRegistry;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.entity.caravan.ModEntities;
import com.minecolonies.kingdoms.entity.guard.SettlementGuardEntity;
import com.minecolonies.kingdoms.entity.soldier.SoldierEntity;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.military.GuardEvents;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRoute;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
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
 * Physical soldiers of field armies (Phase 10), near players only. Every {@value #CYCLE_TICKS} ticks it materializes a
 * bounded squad for an army a player is close to (hysteresis; per-army, global, and per-player caps; entity-ticking
 * chunks only, never loading anything), walks a marching squad along the army's road route, and gathers a besieging
 * squad at the besieged settlement's gate. It writes no strategic state itself: a soldier that unloads, is discarded,
 * or dies to a mob, a fall, or lava is a technical loss. Deaths in a fight (a player or an enemy guard) and a marching
 * squad's progress go through {@link CampaignService}, the single writer of armies and battles.
 */
public final class ArmyManager implements GuardEvents
{
    private static final ArmyManager INSTANCE = new ArmyManager();
    public static final int CYCLE_TICKS = 20;
    public static final long MANUAL_HOLD_TICKS = 1_200L;
    static final long STUCK_TICKS = 200L;
    static final long STUCK_SUPPRESSION_TICKS = 600L;
    static final int SPAWNS_PER_CYCLE = 2;
    static final double LOOKAHEAD_BLOCKS = 10.0D;

    private static final class Squad
    {
        private final UUID armyId;
        private final ResourceLocation dimension;
        private final UUID observer;
        private final Set<UUID> soldiers = new HashSet<>();
        private double lastProgress = Double.NaN;
        private long lastProgressAt;

        private Squad(final UUID armyId, final ResourceLocation dimension, final UUID observer, final long gameTime)
        {
            this.armyId = armyId;
            this.dimension = dimension;
            this.observer = observer;
            this.lastProgressAt = gameTime;
        }
    }

    private record PathEntry(List<UUID> roads, RoadShipmentPath path) {}

    private final Map<UUID, Squad> squads = new LinkedHashMap<>();
    private final Map<UUID, UUID> soldierArmies = new HashMap<>();
    private final Map<UUID, Long> heldUntil = new HashMap<>();
    private final Map<UUID, Long> suppressedUntil = new HashMap<>();
    private final Map<UUID, PathEntry> paths = new HashMap<>();
    /** Unordered faction pairs at war right now, and each NPC settlement's faction (refreshed every cycle). */
    private final Set<String> warPairs = new HashSet<>();
    private final Map<UUID, UUID> settlementFactions = new HashMap<>();
    private long cycles;
    private long totalNanos;
    private long maximumNanos;
    private long materialized;
    private long dematerialized;
    private long deaths;
    private long stuckRecoveries;
    private long failedSpawns;
    private long spawnSerial;
    private MinecraftServer server;

    private ArmyManager() {}
    public static ArmyManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        clearRuntime();
        server = value;
        cycles = totalNanos = maximumNanos = materialized = dematerialized = deaths = stuckRecoveries = failedSpawns = 0L;
        GuardEvents.register(this);
    }

    public void shutdown()
    {
        clearRuntime();
        server = null;
        GuardEvents.register(null);
    }

    private void clearRuntime()
    {
        squads.clear();
        soldierArmies.clear();
        heldUntil.clear();
        suppressedUntil.clear();
        paths.clear();
        warPairs.clear();
        settlementFactions.clear();
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
            KingdomsMod.LOGGER.error("Army soldier update failed", exception);
        }
        final long elapsed = System.nanoTime() - started;
        cycles++;
        totalNanos += elapsed;
        maximumNanos = Math.max(maximumNanos, elapsed);
    }

    private void cycle(final KingdomsSavedData data, final long gameTime, final WarSettings settings)
    {
        refreshWarCache(data);
        for (final Squad squad : List.copyOf(squads.values()))
            squad.soldiers.removeIf(id -> {
                if (entity(squad.dimension, id) instanceof SoldierEntity soldier && !soldier.isRemoved()) return false;
                soldierArmies.remove(id); // technical loss
                return true;
            });
        final List<ArmyRecord> open = data.war().armies().stream().filter(ArmyRecord::open).sorted(CampaignService.byRaisedAt()).toList();
        final Set<UUID> openIds = new HashSet<>();
        open.forEach(army -> openIds.add(army.id()));
        for (final Squad squad : List.copyOf(squads.values())) if (!openIds.contains(squad.armyId)) dematerialize(squad);
        paths.keySet().retainAll(openIds);
        if (!settings.enabled())
        {
            List.copyOf(squads.values()).forEach(this::dematerialize);
            return;
        }
        final Map<UUID, Integer> perPlayer = new HashMap<>();
        squads.values().forEach(squad -> { if (squad.observer != null) perPlayer.merge(squad.observer, squad.soldiers.size(), Integer::sum); });
        for (final ArmyRecord army : open) update(data, army, gameTime, settings, perPlayer);
        heldUntil.values().removeIf(until -> until <= gameTime);
        suppressedUntil.values().removeIf(until -> until <= gameTime);
    }

    private void refreshWarCache(final KingdomsSavedData data)
    {
        warPairs.clear();
        settlementFactions.clear();
        for (final WarRecord war : data.war().wars())
            if (war.status().fighting()) warPairs.add(WarState.key(war.attacker(), war.defender()));
        if (warPairs.isEmpty()) return;
        for (final WarRecord war : data.war().wars())
            if (war.status().fighting())
                for (final UUID factionId : List.of(war.attacker(), war.defender()))
                    data.faction(factionId).ifPresent(faction -> faction.settlementIds().forEach(id -> settlementFactions.put(id, factionId)));
    }

    private void update(final KingdomsSavedData data, final ArmyRecord army, final long gameTime, final WarSettings settings,
        final Map<UUID, Integer> perPlayer)
    {
        final ServerLevel level = level(army.dimension());
        final Squad existing = squads.get(army.id());
        final BattleRecord battle = army.status() == ArmyRecord.Status.BESIEGING && army.battleId() != null
            ? data.war().battle(army.battleId()).filter(BattleRecord::open).orElse(null) : null;
        final RoadShipmentPath path = army.status() == ArmyRecord.Status.BESIEGING ? null : path(data, army);
        final double progress = army.progressAt(gameTime);
        final Vec3 position = battle != null ? Vec3.atBottomCenterOf(battle.position())
            : path != null ? path.positionAt(progress) : null;
        final boolean held = heldUntil.getOrDefault(army.id(), Long.MIN_VALUE) > gameTime;
        final boolean suppressed = suppressedUntil.getOrDefault(army.id(), Long.MIN_VALUE) > gameTime;
        final ServerPlayer player = level == null || position == null ? null : nearestPlayer(level, position);
        final double distance = player == null ? Double.POSITIVE_INFINITY : Math.sqrt(player.distanceToSqr(position));
        final boolean was = existing != null;
        final boolean active = level != null && position != null && !suppressed && (army.status() != ArmyRecord.Status.BESIEGING || battle != null)
            && level.isPositionEntityTicking(BlockPos.containing(position))
            && (held || (was ? distance <= settings.dematerializationRadius() : distance <= settings.materializationRadius()));
        if (!active)
        {
            if (existing != null) dematerialize(existing);
            return;
        }
        final Squad squad = existing != null ? existing : new Squad(army.id(), army.dimension(), player == null ? null : player.getUUID(), gameTime);
        squads.putIfAbsent(army.id(), squad);
        // movement and the marching squad's hold on its army
        final SoldierEntity leader = leader(squad);
        if (path != null && leader != null)
        {
            final double projected = path.projectProgress(leader.position());
            final double moved = army.outbound() ? projected : -projected;
            if (Double.isNaN(squad.lastProgress) || moved > squad.lastProgress + 2.0D / Math.max(1.0D, path.length()))
            {
                squad.lastProgress = moved;
                squad.lastProgressAt = gameTime;
            }
            else if (gameTime - squad.lastProgressAt >= STUCK_TICKS)
            {
                stuckRecoveries++;
                suppressedUntil.put(army.id(), gameTime + STUCK_SUPPRESSION_TICKS);
                dematerialize(squad);
                return;
            }
            CampaignService.hold(data, army, projected, gameTime);
        }
        final double now = army.progressAt(gameTime);
        for (final UUID id : squad.soldiers)
            if (entity(squad.dimension, id) instanceof SoldierEntity soldier)
                soldier.planTarget(battle != null ? siegeTarget(data, battle)
                    : army.outbound() ? path.localWaypoint(now, LOOKAHEAD_BLOCKS) : path.positionAt(Math.max(0.0D, now - LOOKAHEAD_BLOCKS / Math.max(1.0D, path.length()))));
        // squad size
        final int remaining = battle != null ? Math.max(0, battle.attackerStrength() - battle.attackerPhysicalLosses()) : army.strength();
        final int target = Math.min(settings.maxSoldiersPerArmy(), remaining);
        while (squad.soldiers.size() > target)
        {
            final UUID id = squad.soldiers.iterator().next();
            squad.soldiers.remove(id);
            soldierArmies.remove(id);
            if (entity(squad.dimension, id) instanceof SoldierEntity soldier) soldier.discardByManager();
            dematerialized++;
        }
        final int global = soldierArmies.size();
        final UUID observer = squad.observer;
        final int allowed = Math.max(0, Math.min(Math.min(target - squad.soldiers.size(), SPAWNS_PER_CYCLE),
            Math.min(settings.maxSoldiersGlobal() - global, observer == null ? Integer.MAX_VALUE
                : settings.maxSoldiersPerPlayer() - perPlayer.getOrDefault(observer, 0))));
        if (allowed <= 0) return;
        final String style = data.growth().layout(army.originSettlementId()).map(SettlementLayoutPlan::styleFamily).orElse("");
        final String title = data.settlements().get(army.originSettlementId()).map(SettlementRecord::name).orElse("Enemy") + " Soldier";
        final Vec3 behind = battle != null ? position : path.positionAt(Math.max(0.0D, Math.min(1.0D,
            now - (army.outbound() ? 1 : -1) * 4.0D / Math.max(1.0D, path.length()))));
        for (int index = 0; index < allowed; index++)
        {
            if (spawn(level, squad, army, style, title, BlockPos.containing(index == 0 ? position : behind)) == null) break;
            if (observer != null) perPlayer.merge(observer, 1, Integer::sum);
        }
    }

    private static Vec3 siegeTarget(final KingdomsSavedData data, final BattleRecord battle)
    {
        return data.settlements().get(battle.settlementId()).map(settlement -> Vec3.atBottomCenterOf(settlement.anchor()))
            .orElse(Vec3.atBottomCenterOf(battle.position()));
    }

    private SoldierEntity spawn(final ServerLevel level, final Squad squad, final ArmyRecord army, final String style, final String title,
        final BlockPos origin)
    {
        final Optional<BlockPos> position = SafeSpawnFinder.find(origin, 4, 4, SettlementCitizenManager.view(level), List.of());
        if (position.isEmpty())
        {
            failedSpawns++;
            return null;
        }
        final SoldierEntity entity = ModEntities.SOLDIER.get().create(level);
        if (entity == null) return null;
        final long seed = army.id().getLeastSignificantBits() ^ (++spawnSerial * 0x9E3779B97F4A7C15L);
        entity.configure(army.id(), army.factionId(), title, CitizenAppearance.textureKey(style, CitizenRole.GUARD, false, seed));
        entity.moveTo(position.get().getX() + 0.5D, position.get().getY(), position.get().getZ() + 0.5D, (float) Math.floorMod(seed, 360L), 0.0F);
        if (!level.addFreshEntity(entity))
        {
            failedSpawns++;
            return null;
        }
        squad.soldiers.add(entity.getUUID());
        soldierArmies.put(entity.getUUID(), army.id());
        materialized++;
        return entity;
    }

    private RoadShipmentPath path(final KingdomsSavedData data, final ArmyRecord army)
    {
        final PathEntry cached = paths.get(army.id());
        if (cached != null && cached.roads().equals(army.routeRoads())) return cached.path();
        try
        {
            final RoadShipmentPath path = new RoadShipmentPath(new RoadRoute(army.originSettlementId(), army.targetSettlementId(),
                army.routeSettlements(), army.routeRoads(), 0.0D), data.roads());
            paths.put(army.id(), new PathEntry(army.routeRoads(), path));
            return path;
        }
        catch (RuntimeException exception)
        {
            return null; // a road of the route is gone: the army travels on abstractly
        }
    }

    // ------------------------------------------------------------------------------------------------ entity callbacks

    public boolean isCurrent(final SoldierEntity entity)
    {
        final UUID army = soldierArmies.get(entity.getUUID());
        return army != null && army.equals(entity.armyId());
    }

    /** Whether a soldier and a guard fight: the guard's settlement belongs to a faction at war with the soldier's. */
    public boolean enemies(final SoldierEntity soldier, final SettlementGuardEntity guard)
    {
        if (soldier.factionId() == null || guard.settlementId() == null) return false;
        final UUID guardFaction = settlementFactions.get(guard.settlementId());
        return guardFaction != null && !guardFaction.equals(soldier.factionId())
            && warPairs.contains(WarState.key(guardFaction, soldier.factionId()));
    }

    /** Guards look for enemy soldiers only while some pair of factions is at war. */
    @Override
    public boolean active()
    {
        return server != null && !warPairs.isEmpty();
    }

    @Override
    public boolean hostile(final SettlementGuardEntity guard, final LivingEntity target)
    {
        return target instanceof SoldierEntity soldier && isCurrent(soldier) && enemies(soldier, guard);
    }

    /**
     * A guard died. If a soldier of the army besieging the guard's settlement killed it, the battle records one defender
     * loss (applied once, at the battle's resolution). Returns whether it counted.
     */
    @Override
    public boolean guardKilled(final SettlementGuardEntity guard, final Entity killer)
    {
        if (server == null || !(killer instanceof SoldierEntity soldier) || !isCurrent(soldier) || guard.settlementId() == null) return false;
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        final ArmyRecord army = data.war().army(soldier.armyId()).orElse(null);
        if (army == null || army.status() != ArmyRecord.Status.BESIEGING || army.battleId() == null) return false;
        final BattleRecord battle = data.war().battle(army.battleId()).orElse(null);
        if (battle == null || !battle.open() || !battle.settlementId().equals(guard.settlementId())) return false;
        return CampaignService.defenderFell(data, battle);
    }

    public void onSoldierHurt(final SoldierEntity entity, final DamageSource source)
    {
        if (server == null || !isCurrent(entity) || !(source.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        battleOf(data, entity).ifPresent(battle -> CampaignService.recordDefender(data, battle, player.getUUID()));
    }

    /**
     * A soldier died. Killed by a player or an enemy guard: during a siege the battle records an attacker loss (applied
     * once at its resolution); on the march the army loses the soldier now (a skirmish). A player also loses reputation
     * with the army's faction. Anything else (mobs, falls, lava) is a technical loss.
     */
    public void onSoldierDeath(final SoldierEntity entity, final DamageSource source)
    {
        if (!isCurrent(entity) || server == null) return;
        final UUID armyId = soldierArmies.remove(entity.getUUID());
        final Squad squad = squads.get(armyId);
        if (squad != null) squad.soldiers.remove(entity.getUUID());
        deaths++;
        final Entity killer = source.getEntity();
        final ServerPlayer player = killer instanceof ServerPlayer value && !(value instanceof FakePlayer) ? value : null; // machines are not people
        final boolean enemyGuard = killer instanceof SettlementGuardEntity guard && enemies(entity, guard);
        if (player == null && !enemyGuard) return;
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        final ArmyRecord army = data.war().army(armyId).orElse(null);
        if (army == null || !army.open()) return;
        final long gameTime = server.overworld().getGameTime();
        final Optional<BattleRecord> battle = battleOf(data, entity);
        final boolean counted = battle.isPresent() ? CampaignService.attackerFell(data, battle.get(), player == null ? null : player.getUUID())
            : CampaignService.skirmishLoss(data, army);
        if (!counted || player == null || battle.isPresent()) return; // siege kills are judged once, at the battle's resolution
        final int penalty = KingdomsConfig.SERVER.warSoldierKillPenalty.get();
        final Faction faction = data.faction(army.factionId()).orElse(null);
        if (faction == null || penalty <= 0) return;
        final ReputationService.Result result = ReputationService.adjust(data, player.getUUID(), faction.id(), -penalty,
            ReputationRegistry.Cause.SOLDIERS_KILLED, entity.getUUID(), gameTime);
        if (!result.isEmpty()) player.sendSystemMessage(ContractText.reputation(data, result));
    }

    private Optional<BattleRecord> battleOf(final KingdomsSavedData data, final SoldierEntity entity)
    {
        return data.war().army(entity.armyId())
            .filter(army -> army.status() == ArmyRecord.Status.BESIEGING && army.battleId() != null)
            .flatMap(army -> data.war().battle(army.battleId())).filter(BattleRecord::open);
    }

    // ------------------------------------------------------------------------------------------------ operator API

    public int materializeNow(final MinecraftServer value, final UUID armyId)
    {
        suppressedUntil.remove(armyId);
        heldUntil.put(armyId, value.overworld().getGameTime() + MANUAL_HOLD_TICKS);
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final WarSettings settings = settingsFromConfig();
        refreshWarCache(data);
        final Map<UUID, Integer> perPlayer = new HashMap<>();
        final long gameTime = value.overworld().getGameTime();
        data.war().army(armyId).filter(ArmyRecord::open).ifPresent(army -> {
            for (int attempt = 0; attempt < Math.max(1, (settings.maxSoldiersPerArmy() + SPAWNS_PER_CYCLE - 1) / SPAWNS_PER_CYCLE); attempt++)
                update(data, army, gameTime, settings, perPlayer);
        });
        return soldiersOf(armyId);
    }

    public int dematerializeNow(final MinecraftServer value, final UUID armyId)
    {
        heldUntil.remove(armyId);
        suppressedUntil.put(armyId, value.overworld().getGameTime() + MANUAL_HOLD_TICKS);
        final Squad squad = squads.get(armyId);
        final int count = squad == null ? 0 : squad.soldiers.size();
        if (squad != null) dematerialize(squad);
        return count;
    }

    public int soldiersOf(final UUID armyId)
    {
        final Squad squad = squads.get(armyId);
        return squad == null ? 0 : squad.soldiers.size();
    }

    public int physicalSoldiers() { return soldierArmies.size(); }
    public int activeSquads() { return squads.size(); }

    public record Stats(long cycles, double averageNanos, long maximumNanos, long materialized, long dematerialized, long deaths,
        long stuckRecoveries, long failedSpawns) {}

    public Stats stats()
    {
        return new Stats(cycles, cycles == 0 ? 0.0D : (double) totalNanos / cycles, maximumNanos, materialized, dematerialized, deaths,
            stuckRecoveries, failedSpawns);
    }

    // ------------------------------------------------------------------------------------------------ internals

    private SoldierEntity leader(final Squad squad)
    {
        for (final UUID id : squad.soldiers)
            if (entity(squad.dimension, id) instanceof SoldierEntity soldier && soldier.getTarget() == null) return soldier;
        return null;
    }

    private void dematerialize(final Squad squad)
    {
        squads.remove(squad.armyId);
        for (final UUID id : squad.soldiers)
        {
            soldierArmies.remove(id);
            if (entity(squad.dimension, id) instanceof SoldierEntity soldier) soldier.discardByManager();
            dematerialized++;
        }
        squad.soldiers.clear();
    }

    private Entity entity(final ResourceLocation dimension, final UUID id)
    {
        final ServerLevel level = level(dimension);
        return level == null ? null : level.getEntity(id);
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

    public static WarSettings settingsFromConfig()
    {
        return WarManager.settingsFromConfig();
    }
}
