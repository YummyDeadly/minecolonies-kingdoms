package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.citizen.SettlementCitizenManager;
import com.minecolonies.kingdoms.citizen.interaction.SettlementCitizenInteractionService;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.contract.ContractManager;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.contract.ContractText;
import com.minecolonies.kingdoms.entity.bandit.BanditEntity;
import com.minecolonies.kingdoms.entity.caravan.ModEntities;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.road.ShipmentPathResolver;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server orchestration of bandits. Strategic work runs every {@value #CYCLE_TICKS} ticks and is cheap: one threat
 * evaluation per configured interval (roads + unassessed in-transit shipments), then only open encounters. Physical
 * bandits exist only near players, only in entity-ticking chunks (never force-loaded), within per-encounter,
 * per-player, and global caps; they are never saved and are discarded as orphans. Their deaths lower the encounter's
 * persisted strength; the encounter record alone decides outcomes, through {@link EncounterService}.
 */
public final class BanditManager
{
    private static final BanditManager INSTANCE = new BanditManager();
    public static final int CYCLE_TICKS = 20;
    public static final long MANUAL_HOLD_TICKS = 1_200L;
    static final long FAILURE_RETRY_TICKS = 200L;
    /** No player hit and no bandit death for this long: the fight is stuck and goes back to abstract rules. */
    static final long STALL_TICKS = 6_000L;
    /** A roadblock past its lifetime leaves once nobody has fought it for this long. */
    static final long EXPIRY_GRACE_TICKS = 600L;
    static final double FLEE_DISTANCE = 40.0D;

    private final ShipmentPathResolver paths = new ShipmentPathResolver();
    private final BanditRoster roster = new BanditRoster();
    private final Map<UUID, Double> remoteCache = new HashMap<>();
    private int remoteCacheSettlements = -1;
    private final BanditProfiler profiler = new BanditProfiler();
    private final CampWorks campWorks = new CampWorks();
    private MinecraftServer server;

    private BanditManager() {}
    public static BanditManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        clearRuntime();
        server = value;
        profiler.reset();
        SettlementCitizenInteractionService.getInstance().register(new BanditReportHandler());
    }

    public void shutdown()
    {
        clearRuntime();
        server = null;
    }

    private void clearRuntime()
    {
        roster.clear();
        remoteCache.clear();
        remoteCacheSettlements = -1;
        campWorks.clear();
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
            cycle(KingdomsSavedData.get(value.overworld()), gameTime, settingsFromConfig(), ContractManager.settings());
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error("Bandit update failed", exception);
        }
        profiler.cycle(System.nanoTime() - started);
    }

    private void cycle(final KingdomsSavedData data, final long gameTime, final BanditSettings settings, final ContractSettings contracts)
    {
        if (!settings.enabled())
        {
            // strategic state is kept as it is; nothing new happens and no bandit stays in the world
            for (final UUID encounterId : roster.encounterIds()) dematerialize(data, encounterId, gameTime, settings);
            return;
        }
        final long last = data.bandits().lastEvaluatedAt();
        if (last < 0L || gameTime - last >= settings.evaluationIntervalTicks()) evaluate(data, gameTime, settings, contracts);
        for (final EncounterService.Resolution resolution : EncounterService.cancelStale(data, gameTime, settings, contracts)) finish(data, resolution);
        for (final BanditEncounter encounter : EncounterService.activateDue(data, gameTime, settings, contracts))
        {
            profiler.activated();
            KingdomsMod.LOGGER.info("Bandit encounter {} activated on road {} at {}", encounter.id(), encounter.roadId(),
                encounter.position().toShortString());
            SecurityContracts.post(data, gameTime, settings, contracts);
        }
        EncounterService.enforceHolds(data, gameTime);
        if (!CampService.recruit(data, gameTime, settings).isEmpty()) profiler.campRecruit();
        campWorks.update(data, gameTime, settings, this::level).ifPresent(done -> profiler.campWork());
        updatePhysical(data, gameTime, settings, contracts);
        materializeNearby(data, gameTime, settings);
        for (final EncounterService.Resolution resolution : EncounterService.resolveDue(data, gameTime, settings, contracts))
        {
            profiler.abstractResolution();
            finish(data, resolution);
        }
        final Set<UUID> open = new HashSet<>();
        data.bandits().open().forEach(encounter -> open.add(encounter.id()));
        roster.sweep(gameTime, open);
    }

    /** Periodic strategic evaluation: assess new shipments, update road threats and roadblocks, post security offers. */
    public void evaluate(final KingdomsSavedData data, final long gameTime, final BanditSettings settings, final ContractSettings contracts)
    {
        final List<Vec3> anchors = anchors(data);
        for (final TradeShipment shipment : List.copyOf(data.tradeLedger().shipments()))
        {
            if (shipment.status() != TradeShipmentStatus.IN_TRANSIT || data.bandits().assessed(shipment.id())) continue;
            if (paths.resolve(data, shipment) instanceof RoadShipmentPath path)
            {
                EncounterService.assess(data, shipment, path, anchors, gameTime, settings).ifPresent(encounter -> {
                    profiler.planned();
                    KingdomsMod.LOGGER.info("Bandit ambush {} planned for shipment {} on road {} (threat {})", encounter.id(),
                        shipment.id(), encounter.roadId(), String.format(java.util.Locale.ROOT, "%.1f", encounter.threatAtCreation()));
                });
            }
            else data.bandits().markAssessed(shipment.id(), gameTime); // no road: never ambushed
        }
        if (remoteCacheSettlements != anchors.size())
        {
            remoteCache.clear();
            remoteCacheSettlements = anchors.size();
        }
        final ThreatEvaluator.Report report = ThreatEvaluator.evaluate(data,
            road -> remoteCache.computeIfAbsent(road.id(), id -> EncounterPlanner.remoteLength(road, anchors, settings.settlementExclusionRadius())),
            anchors, gameTime, settings, contracts);
        if (report.roadblocks() > 0) KingdomsMod.LOGGER.info("Bandits set up {} roadblock(s)", report.roadblocks());
        if (report.camps() > 0)
        {
            profiler.campsEstablished(report.camps());
            KingdomsMod.LOGGER.info("Bandits settled {} new camp(s)", report.camps());
        }
        report.cancellations().forEach(resolution -> finish(data, resolution));
        SecurityContracts.post(data, gameTime, settings, contracts);
        profiler.evaluation();
    }

    // ------------------------------------------------------------------------------------------------ physical

    private void updatePhysical(final KingdomsSavedData data, final long gameTime, final BanditSettings settings,
        final ContractSettings contracts)
    {
        for (final BanditRoster.Presence presence : roster.presences())
        {
            final BanditEncounter encounter = data.bandits().encounter(presence.encounterId()).orElse(null);
            final ServerLevel level = encounter == null ? null : level(encounter.dimension());
            if (encounter == null || !encounter.open() || level == null)
            {
                discard(level, roster.end(presence.encounterId()));
                continue;
            }
            if (!BanditRoster.mayAppear(level.getDifficulty()))
            {
                dematerialize(data, encounter.id(), gameTime, settings);
                continue;
            }
            final Vec3 center = Vec3.atCenterOf(encounter.position());
            for (final UUID id : presence.entities())
            {
                if (!(level.getEntity(id) instanceof BanditEntity bandit) || !bandit.isAlive())
                {
                    roster.drop(id); // unloaded or removed without dying: a technical loss, strength is kept
                }
                else if (bandit.distanceToSqr(center) > FLEE_DISTANCE * FLEE_DISTANCE)
                {
                    profiler.fled(); // strength is kept: it comes back with the next materialization
                    roster.drop(id);
                    bandit.discardByManager();
                }
            }
            if (presence.size() == 0 && encounter.remainingStrength() == 0)
            {
                final BanditEncounter.Cause cause = encounter.defenders().isEmpty()
                    ? BanditEncounter.Cause.CARAVAN_GUARDS : BanditEncounter.Cause.PLAYER_VICTORY;
                profiler.physicalResolution();
                finish(data, EncounterService.resolve(data, encounter, new EncounterRules.Decision(EncounterRules.Outcome.BANDITS_DEFEATED,
                    0.0D, 0L), cause, List.of(), gameTime, settings, contracts));
                continue;
            }
            if (BanditRoster.stalled(presence, encounter, gameTime))
            {
                // stuck recovery: go abstract and stay abstract until the abstract rules have settled it
                profiler.stalled();
                dematerialize(data, encounter.id(), gameTime, settings);
                roster.suppress(encounter.id(), gameTime + BanditRoster.stallSuppression(settings));
                continue;
            }
            if (BanditRoster.shouldLeave(nearestPlayerDistance(level, center), roster.held(encounter.id(), gameTime), settings))
            {
                dematerialize(data, encounter.id(), gameTime, settings);
                continue;
            }
            final int missing = encounter.remainingStrength() - presence.size();
            if (missing > 0 && level.isPositionEntityTicking(encounter.position()))
                spawn(data, encounter, presence, level, Math.min(missing, roster.allowance(presence.observer(), missing, settings)), settings);
            if (presence.size() == 0) dematerialize(data, encounter.id(), gameTime, settings);
        }
    }

    private void materializeNearby(final KingdomsSavedData data, final long gameTime, final BanditSettings settings)
    {
        record Candidate(BanditEncounter encounter, ServerLevel level, ServerPlayer player, double distance) {}
        final List<Candidate> candidates = new ArrayList<>();
        for (final BanditEncounter encounter : data.bandits().open())
        {
            if (encounter.status() != BanditEncounter.Status.ACTIVE || roster.physical(encounter.id())
                || encounter.remainingStrength() <= 0 || !roster.mayMaterialize(encounter.id(), gameTime)) continue;
            final ServerLevel level = level(encounter.dimension());
            // only where the world already runs: a chunk is never loaded for bandits
            if (level == null || !BanditRoster.mayAppear(level.getDifficulty()) || !level.isPositionEntityTicking(encounter.position())) continue;
            final ServerPlayer player = nearestPlayer(level, Vec3.atCenterOf(encounter.position()));
            final double distance = player == null ? Double.POSITIVE_INFINITY : Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(encounter.position())));
            if (!BanditRoster.inMaterializationRange(distance, roster.held(encounter.id(), gameTime), settings)) continue;
            candidates.add(new Candidate(encounter, level, player, distance));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(candidate -> candidate.encounter().id()));
        for (final Candidate candidate : candidates)
        {
            final BanditEncounter encounter = candidate.encounter();
            if (!campWorks.readyToMaterialize(data, encounter, candidate.level(), gameTime, settings)) continue;
            final UUID observer = candidate.player() == null ? null : candidate.player().getUUID();
            final int count = roster.allowance(observer, encounter.remainingStrength(), settings);
            if (count <= 0) continue;
            final BanditRoster.Presence presence = roster.begin(encounter.id(), observer, gameTime);
            if (spawn(data, encounter, presence, candidate.level(), count, settings) == 0)
            {
                // visualization failed: the encounter simply stays abstract and is tried again later
                roster.end(encounter.id());
                profiler.materializationFailure();
                roster.retry(encounter.id(), gameTime + FAILURE_RETRY_TICKS);
                continue;
            }
            encounter.representation(BanditEncounter.Representation.PHYSICAL);
            profiler.materialized();
            data.markChanged();
            notifyNear(candidate.level(), encounter.position(), settings.dematerializationRadius(),
                Component.literal(switch (encounter.kind())
                {
                    case AMBUSH -> "Bandits are attacking a caravan nearby!";
                    case ROADBLOCK -> "Bandits have blocked the road ahead!";
                    case CAMP -> "You have found a bandit camp!";
                }).withStyle(ChatFormatting.RED));
        }
    }

    private int spawn(final KingdomsSavedData data, final BanditEncounter encounter, final BanditRoster.Presence presence,
        final ServerLevel level, final int count, final BanditSettings settings)
    {
        if (count <= 0) return 0;
        final List<Vec3> players = level.players().stream().map(player -> player.position()).toList();
        int spawned = 0;
        for (final BanditSpawnPlanner.Placement placement : BanditSpawnPlanner.place(encounter.seed(), encounter.position(), count,
            presence.spawnedEver(), SettlementCitizenManager.view(level),
            position -> level.getBrightness(LightLayer.SKY, position) >= BanditSpawnPlanner.MIN_SKY_LIGHT,
            anchors(data), settings.settlementExclusionRadius(), players))
        {
            final BanditEntity bandit = ModEntities.BANDIT.get().create(level);
            if (bandit == null) break;
            final boolean chief = !presence.chiefSpawned() && encounter.strength() >= 4;
            bandit.configure(encounter.id(), encounter.shipmentId(), encounter.position(), chief);
            final BlockPos position = placement.position();
            bandit.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, placement.yaw(), 0.0F);
            if (!level.addFreshEntity(bandit)) continue;
            presence.chiefSpawned(chief);
            roster.add(encounter.id(), bandit.getUUID());
            spawned++;
        }
        return spawned;
    }

    private void dematerialize(final KingdomsSavedData data, final UUID encounterId, final long gameTime, final BanditSettings settings)
    {
        if (!roster.physical(encounterId)) return;
        final BanditEncounter encounter = data.bandits().encounter(encounterId).orElse(null);
        discard(encounter == null ? null : level(encounter.dimension()), roster.end(encounterId));
        if (encounter != null && encounter.open())
        {
            encounter.representation(BanditEncounter.Representation.ABSTRACT);
            // leaving never decides the fight on the spot
            encounter.deferResolution(gameTime, settings.abstractResolveTicks() / 2);
            encounter.deferExpiry(gameTime, settings.abstractResolveTicks() / 2);
            data.markChanged();
        }
        profiler.dematerialized();
    }

    /** Removes the given bandits from the world (no death, no drops, no strategic effect). */
    private static void discard(final ServerLevel level, final List<UUID> entities)
    {
        if (level == null) return; // not loaded: the bandits are not in the world either, and orphans remove themselves
        for (final UUID id : entities) if (level.getEntity(id) instanceof BanditEntity bandit) bandit.discardByManager();
    }

    /** After a resolution: remove bandits, tell the people involved, and pay rewards that are now due. */
    private void finish(final KingdomsSavedData data, final EncounterService.Resolution resolution)
    {
        if (!resolution.applied()) return;
        final BanditEncounter encounter = resolution.encounter();
        discard(level(encounter.dimension()), roster.end(encounter.id()));
        KingdomsMod.LOGGER.info("Bandit encounter {} ({}) resolved: {} / {} / {}; cargo lost {}", encounter.id(), encounter.kind(),
            encounter.status(), encounter.outcome(), encounter.cause(), resolution.cargoLost());
        if (server == null) return;
        final Set<UUID> told = new LinkedHashSet<>(encounter.defenders());
        resolution.contracts().forEach(closure -> { if (closure.contract().holder() != null) told.add(closure.contract().holder()); });
        for (final UUID playerId : told)
        {
            final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            player.sendSystemMessage(Component.literal(describe(encounter)).withStyle(
                encounter.status() == BanditEncounter.Status.RESOLVED_BANDITS ? ChatFormatting.RED : ChatFormatting.GREEN));
            for (final ContractService.Closure closure : resolution.contracts())
            {
                if (!playerId.equals(closure.contract().holder())) continue;
                player.sendSystemMessage(Component.literal(switch (closure.contract().status())
                {
                    case COMPLETED -> "Contract fulfilled.";
                    case FAILED -> "Contract failed: the caravan was robbed.";
                    default -> "Your contract was cancelled: the situation resolved without you.";
                }).withStyle(closure.contract().status() == ContractStatus.COMPLETED ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                if (!closure.reputation().isEmpty()) player.sendSystemMessage(ContractText.reputation(data, closure.reputation()));
            }
            final var reputation = resolution.reputation().get(playerId);
            if (reputation != null && !reputation.isEmpty()) player.sendSystemMessage(ContractText.reputation(data, reputation));
            ContractManager.getInstance().claimPending(player).ifPresent(player::sendSystemMessage);
        }
    }

    static String describe(final BanditEncounter encounter)
    {
        if (encounter.outcome() == null) return "The bandit threat is gone.";
        return switch (encounter.outcome())
        {
            case BANDITS_DEFEATED -> switch (encounter.kind())
            {
                case AMBUSH -> "The bandits are beaten; the caravan continues.";
                case ROADBLOCK -> "The road is clear of bandits.";
                case CAMP -> "The bandit camp is cleared.";
            };
            case CARAVAN_ESCAPED -> "The caravan got away from the bandits.";
            case CARAVAN_DELAYED -> "The caravan got away, but lost time.";
            case PARTIAL_LOSS -> "Bandits robbed the caravan: " + encounter.cargoLost() + " of " + encounter.cargoBefore() + " "
                + (encounter.cargoResource() == null ? "goods" : encounter.cargoResource().name().toLowerCase(java.util.Locale.ROOT)) + " lost.";
            case TOTAL_LOSS -> "Bandits took the whole cargo.";
        };
    }

    // ------------------------------------------------------------------------------------------------ callbacks

    public boolean isCurrent(final BanditEntity bandit)
    {
        return bandit.encounterId() != null && roster.isCurrent(bandit.getUUID(), bandit.encounterId());
    }

    public void onBanditHurt(final BanditEntity bandit, final ServerPlayer player)
    {
        if (!isCurrent(bandit) || server == null) return;
        roster.presence(bandit.encounterId()).ifPresent(presence -> presence.progress(server.overworld().getGameTime()));
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        data.bandits().encounter(bandit.encounterId()).filter(BanditEncounter::open)
            .ifPresent(encounter -> EncounterService.recordDefender(data, encounter, player.getUUID()));
    }

    /** One bandit died: the encounter's persisted strength drops by one (no reward, no reputation per kill). */
    public void onBanditDeath(final BanditEntity bandit, final ServerPlayer killer)
    {
        if (!isCurrent(bandit) || server == null) return;
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        roster.presence(bandit.encounterId()).ifPresent(presence -> presence.progress(server.overworld().getGameTime()));
        roster.drop(bandit.getUUID());
        profiler.banditDeath();
        data.bandits().encounter(bandit.encounterId()).filter(BanditEncounter::open).ifPresent(encounter -> {
            if (killer != null) EncounterService.recordDefender(data, encounter, killer.getUUID());
            encounter.banditLost();
            data.markChanged();
        });
    }

    /** Whether an active encounter holds this shipment (its caravan stops and defends). */
    public boolean holds(final UUID shipmentId)
    {
        return server != null && KingdomsSavedData.get(server.overworld()).bandits().activeForShipment(shipmentId).isPresent();
    }

    /**
     * The caravan's leader or carrier died while bandits hold it: the encounter (not the entity death) decides the
     * cargo: the bandits won and took a seeded 30-60%. Returns false when no encounter holds the shipment, in which
     * case the caravan's own destruction rules apply.
     */
    public boolean onCaravanOverrun(final UUID shipmentId)
    {
        if (server == null) return false;
        final KingdomsSavedData data = KingdomsSavedData.get(server.overworld());
        final BanditEncounter encounter = data.bandits().activeForShipment(shipmentId).orElse(null);
        if (encounter == null) return false;
        profiler.overrun();
        finish(data, EncounterService.resolve(data, encounter, new EncounterRules.Decision(EncounterRules.Outcome.PARTIAL_LOSS,
            EncounterRules.overrunFraction(encounter.seed()), 0L), BanditEncounter.Cause.CARAVAN_OVERRUN, List.of(),
            server.overworld().getGameTime(), settingsFromConfig(), ContractManager.settings()));
        return true;
    }

    // ------------------------------------------------------------------------------------------------ operator API

    public boolean materializeNow(final MinecraftServer value, final UUID encounterId)
    {
        final long gameTime = value.overworld().getGameTime();
        roster.unsuppress(encounterId);
        roster.hold(encounterId, gameTime + MANUAL_HOLD_TICKS);
        materializeNearby(KingdomsSavedData.get(value.overworld()), gameTime, settingsFromConfig());
        return roster.physical(encounterId);
    }

    public boolean dematerializeNow(final MinecraftServer value, final UUID encounterId)
    {
        roster.release(encounterId);
        roster.suppress(encounterId, value.overworld().getGameTime() + MANUAL_HOLD_TICKS);
        final boolean was = roster.physical(encounterId);
        dematerialize(KingdomsSavedData.get(value.overworld()), encounterId, value.overworld().getGameTime(), settingsFromConfig());
        return was;
    }

    /** Operator resolution through the same transaction as everything else. */
    public EncounterService.Resolution resolveNow(final MinecraftServer value, final BanditEncounter encounter, final EncounterRules.Outcome outcome)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final EncounterRules.Decision decision = switch (outcome)
        {
            case PARTIAL_LOSS -> new EncounterRules.Decision(outcome, 0.4D, 0L);
            case TOTAL_LOSS -> new EncounterRules.Decision(outcome, 1.0D, 0L);
            case CARAVAN_DELAYED -> new EncounterRules.Decision(outcome, 0.0D, 2_400L);
            default -> new EncounterRules.Decision(outcome, 0.0D, 0L);
        };
        final EncounterService.Resolution resolution = EncounterService.resolve(data, encounter, decision, BanditEncounter.Cause.ADMIN,
            List.of(), value.overworld().getGameTime(), settingsFromConfig(), ContractManager.settings());
        finish(data, resolution);
        return resolution;
    }

    public EncounterService.Resolution cancelNow(final MinecraftServer value, final BanditEncounter encounter)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final EncounterService.Resolution resolution = EncounterService.cancel(data, encounter, BanditEncounter.Cause.ADMIN,
            value.overworld().getGameTime(), settingsFromConfig(), ContractManager.settings());
        finish(data, resolution);
        return resolution;
    }

    /** Operator test: plan an ambush for an in-transit shipment on this road (no chance roll), or set up a roadblock. */
    public Optional<BanditEncounter> spawnTest(final MinecraftServer value, final RoadRecord road, final boolean roadblock)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final long gameTime = value.overworld().getGameTime();
        final BanditSettings settings = settingsFromConfig();
        final List<Vec3> anchors = anchors(data);
        if (roadblock)
        {
            final Optional<BanditEncounter> created = EncounterService.createRoadblock(data, road, anchors, gameTime, settings, true);
            if (created.isPresent()) SecurityContracts.post(data, gameTime, settings, ContractManager.settings());
            return created;
        }
        for (final TradeShipment shipment : data.tradeLedger().shipments())
        {
            if (shipment.status() != TradeShipmentStatus.IN_TRANSIT) continue;
            if (!(paths.resolve(data, shipment) instanceof RoadShipmentPath path)) continue;
            for (final RoadShipmentPath.RoadSpan span : path.spans())
            {
                if (!span.roadId().equals(road.id())) continue;
                final Optional<BanditEncounter> planned = EncounterService.planAmbush(data, shipment, path, span, anchors, gameTime, settings);
                if (planned.isPresent())
                {
                    SecurityContracts.post(data, gameTime, settings, ContractManager.settings());
                    return planned;
                }
            }
        }
        return Optional.empty();
    }

    /** Operator test: establish a camp beside this road now (ignores threat, pressure, cooldowns, and caps). */
    public Optional<BanditCamp> establishCampNow(final MinecraftServer value, final RoadRecord road)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        final long gameTime = value.overworld().getGameTime();
        final BanditSettings settings = settingsFromConfig();
        final Optional<BanditCamp> camp = CampService.establish(data, road, anchors(data), gameTime, settings, true);
        if (camp.isPresent())
        {
            profiler.campsEstablished(1);
            SecurityContracts.post(data, gameTime, settings, ContractManager.settings());
        }
        return camp;
    }

    /** Operator: build the camp's structure now if a site passes (chunk ownership waived; every other check applies). */
    public Optional<String> buildCampNow(final MinecraftServer value, final BanditCamp camp)
    {
        final ServerLevel level = level(camp.dimension());
        if (level == null) return Optional.of("dimension not loaded");
        return campWorks.build(KingdomsSavedData.get(value.overworld()), camp, level, value.overworld().getGameTime(), settingsFromConfig(),
            true);
    }

    /** Operator: end a camp now; its fight is cancelled through the encounter transaction. */
    public boolean disbandCampNow(final MinecraftServer value, final BanditCamp camp)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        // an open fight is cancelled through the normal path, so contract holders are told and bandits removed
        data.bandits().encounter(camp.encounterId()).filter(BanditEncounter::open).ifPresent(encounter -> cancelNow(value, encounter));
        final boolean ended = !camp.active()
            || CampService.disband(data, camp, value.overworld().getGameTime(), settingsFromConfig(), ContractManager.settings());
        discard(level(camp.dimension()), roster.end(camp.encounterId()));
        return ended;
    }

    /** Operator: take the camp's placed blocks down now (only blocks that are still exactly what was placed). */
    public boolean removeCampNow(final MinecraftServer value, final BanditCamp camp)
    {
        final ServerLevel level = level(camp.dimension());
        return level != null && campWorks.remove(KingdomsSavedData.get(value.overworld()), camp, level, value.overworld().getGameTime(), true);
    }

    public void setThreat(final MinecraftServer value, final UUID roadId, final double threat)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
        data.bandits().threatFor(roadId).setThreat(threat);
        data.markChanged();
    }

    public record PhysicalSnapshot(UUID encounterId, UUID observer, int bandits, long since) {}

    public Optional<PhysicalSnapshot> physicalOf(final UUID encounterId)
    {
        return roster.presence(encounterId).map(value ->
            new PhysicalSnapshot(value.encounterId(), value.observer(), value.size(), value.materializedAt()));
    }

    public int physicalEncounters() { return roster.encounters(); }
    public int physicalBandits() { return roster.bandits(); }
    public BanditProfiler.Stats stats() { return profiler.snapshot(); }

    // ------------------------------------------------------------------------------------------------ helpers

    private static List<Vec3> anchors(final KingdomsSavedData data)
    {
        return EncounterPlanner.exclusionAnchors(data);
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

    private static double nearestPlayerDistance(final ServerLevel level, final Vec3 position)
    {
        final ServerPlayer player = nearestPlayer(level, position);
        return player == null ? Double.POSITIVE_INFINITY : Math.sqrt(player.distanceToSqr(position));
    }

    private static void notifyNear(final ServerLevel level, final BlockPos position, final int radius, final Component message)
    {
        final double limit = (double) radius * radius;
        level.players().stream().filter(player -> player.distanceToSqr(Vec3.atCenterOf(position)) <= limit)
            .forEach(player -> player.sendSystemMessage(message));
    }

    public static BanditSettings settingsFromConfig()
    {
        final var config = KingdomsConfig.SERVER;
        return new BanditSettings(config.banditsEnabled.get(), config.banditsEvaluationIntervalTicks.get(),
            config.banditsMaterializationRadius.get(),
            Math.max(config.banditsDematerializationRadius.get(), config.banditsMaterializationRadius.get() + 8),
            config.banditsMaxPerEncounter.get(), config.banditsMaxPhysicalGlobal.get(), config.banditsMaxPhysicalPerPlayer.get(),
            config.banditsSettlementExclusionRadius.get(), config.banditsBaseThreat.get(), config.banditsThreatStep.get(),
            config.banditsEncounterCooldownTicks.get(), config.banditsAmbushChanceAtMaxThreat.get(),
            config.banditsAbstractResolveTicks.get(), config.banditsRoadblockThreshold.get(), config.banditsRoadblockLifetimeTicks.get(),
            config.banditsSuppressionTicks.get(), config.banditsMaxActiveEncounters.get(),
            new CampSettings(config.campsEnabled.get(), config.campsThreshold.get(), config.campsPressureEvaluations.get(),
                config.campsMax.get(), config.campsMinStrength.get(), Math.max(config.campsMinStrength.get(), config.campsMaxStrength.get()),
                config.campsLifetimeTicks.get(), config.campsRespawnCooldownTicks.get(), config.campsThreatContribution.get(),
                config.campsRecruitIntervalTicks.get(), config.campsStructures.get(), config.campsMaxInhabitedTicks.get()));
    }
}
