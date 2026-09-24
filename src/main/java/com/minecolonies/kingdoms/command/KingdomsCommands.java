package com.minecolonies.kingdoms.command;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.caravan.CaravanInstance;
import com.minecolonies.kingdoms.caravan.CaravanManager;
import com.minecolonies.kingdoms.caravan.CaravanStats;
import com.minecolonies.kingdoms.caravan.ShipmentPath;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.economy.ResourceEconomy;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.simulation.SimulationStats;
import com.minecolonies.kingdoms.simulation.WorldSimulationManager;
import com.minecolonies.kingdoms.trade.TradeManager;
import com.minecolonies.kingdoms.trade.TradeMatchResult;
import com.minecolonies.kingdoms.trade.TradeProcessResult;
import com.minecolonies.kingdoms.trade.TradeRoute;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeStats;
import com.minecolonies.kingdoms.world.road.RoadShipmentPath;
import com.minecolonies.kingdoms.world.road.ShipmentPathResolver;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class KingdomsCommands
{
    private static final EconomyManager ECONOMY = new EconomyManager();

    private KingdomsCommands()
    {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("kingdoms")
            .then(Commands.literal("factions").executes(context -> listFactions(context.getSource())))
            .then(Commands.literal("colonies").executes(context -> listColonies(context.getSource())))
            .then(Commands.literal("colony")
                .then(Commands.literal("create-npc")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> createNpcColony(
                            context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("delete")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("uuid", StringArgumentType.word())
                        .executes(context -> deleteNpcColony(
                            context.getSource(), StringArgumentType.getString(context, "uuid")))))
                .then(Commands.argument("colony", StringArgumentType.word())
                    .executes(context -> showColony(
                        context.getSource(), StringArgumentType.getString(context, "colony")))))
            .then(Commands.literal("economy")
                .then(Commands.literal("show")
                    .then(Commands.argument("colony", StringArgumentType.word())
                        .executes(context -> showEconomy(
                            context.getSource(), StringArgumentType.getString(context, "colony")))))
                .then(economySetCommand())
                .then(Commands.argument("colony", StringArgumentType.word())
                    .executes(context -> showEconomy(
                        context.getSource(), StringArgumentType.getString(context, "colony")))))
            .then(Commands.literal("needs")
                .then(Commands.argument("colony", StringArgumentType.word())
                    .executes(context -> showNeeds(
                        context.getSource(), StringArgumentType.getString(context, "colony")))))
            .then(tradeCommand())
            .then(caravanCommand())
            .then(WorldCommands.settlementCommand())
            .then(WorldCommands.roadCommand())
            .then(WorldCommands.growthCommand())
            .then(CitizenCommands.citizenCommand())
            .then(ContractCommands.contractCommand())
            .then(ContractCommands.reputationCommand())
            .then(DiplomacyCommands.diplomacyCommand())
            .then(BanditCommands.banditCommand())
            .then(Commands.literal("simulation")
                .requires(source -> source.hasPermission(2))
                .executes(context -> showSimulation(context.getSource()))
                .then(Commands.literal("stats").executes(context -> showSimulationStats(context.getSource()))))
            .then(Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .executes(context -> showDebug(context.getSource()))));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> economySetCommand()
    {
        return Commands.literal("set")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("colony", StringArgumentType.word())
                .then(Commands.argument("resource", StringArgumentType.word())
                    .then(Commands.literal("stockpile")
                        .then(Commands.argument("value", LongArgumentType.longArg(0L))
                            .executes(context -> setEconomyLong(
                                context.getSource(),
                                StringArgumentType.getString(context, "colony"),
                                StringArgumentType.getString(context, "resource"),
                                "stockpile",
                                LongArgumentType.getLong(context, "value")))))
                    .then(Commands.literal("reserve")
                        .then(Commands.argument("value", LongArgumentType.longArg(0L))
                            .executes(context -> setEconomyLong(
                                context.getSource(),
                                StringArgumentType.getString(context, "colony"),
                                StringArgumentType.getString(context, "resource"),
                                "reserve",
                                LongArgumentType.getLong(context, "value")))))
                    .then(Commands.literal("production")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D))
                            .executes(context -> setEconomyDouble(
                                context.getSource(),
                                StringArgumentType.getString(context, "colony"),
                                StringArgumentType.getString(context, "resource"),
                                "production",
                                DoubleArgumentType.getDouble(context, "value")))))
                    .then(Commands.literal("consumption")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D))
                            .executes(context -> setEconomyDouble(
                                context.getSource(),
                                StringArgumentType.getString(context, "colony"),
                                StringArgumentType.getString(context, "resource"),
                                "consumption",
                                DoubleArgumentType.getDouble(context, "value")))))));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> tradeCommand()
    {
        return Commands.literal("trade")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("routes").executes(context -> listRoutes(context.getSource())))
            .then(Commands.literal("route")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(context -> showRoute(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("shipments").executes(context -> listShipments(context.getSource())))
            .then(Commands.literal("shipment")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(context -> showShipment(context.getSource(), StringArgumentType.getString(context, "id")))))
            .then(Commands.literal("match").executes(context -> runTradeMatch(context.getSource())))
            .then(Commands.literal("tick").executes(context -> runTradeTick(context.getSource())))
            .then(Commands.literal("stats").executes(context -> showTradeStats(context.getSource())));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> caravanCommand()
    {
        return Commands.literal("caravan")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("list").executes(context -> listCaravans(context.getSource())))
            .then(Commands.literal("info")
                .then(Commands.argument("shipment", StringArgumentType.word())
                    .executes(context -> showCaravan(context.getSource(),
                        StringArgumentType.getString(context, "shipment")))))
            .then(Commands.literal("materialize")
                .then(Commands.argument("shipment", StringArgumentType.word())
                    .executes(context -> materializeCaravan(context.getSource(),
                        StringArgumentType.getString(context, "shipment")))))
            .then(Commands.literal("dematerialize")
                .then(Commands.argument("shipment", StringArgumentType.word())
                    .executes(context -> dematerializeCaravan(context.getSource(),
                        StringArgumentType.getString(context, "shipment")))))
            .then(Commands.literal("teleport-near")
                .then(Commands.argument("shipment", StringArgumentType.word())
                    .executes(context -> teleportCaravanNear(context.getSource(),
                        StringArgumentType.getString(context, "shipment")))))
            .then(Commands.literal("stats").executes(context -> showCaravanStats(context.getSource())));
    }

    private static int listCaravans(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<CaravanInstance> instances = data.caravanLedger().instances().stream()
            .sorted(Comparator.comparing(CaravanInstance::shipmentId)).toList();
        source.sendSuccess(() -> Component.literal("Physical caravans: " + instances.size())
            .withStyle(ChatFormatting.GOLD), false);
        instances.forEach(instance -> source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "- shipment=%s caravan=%s state=%s progress=%.1f%% entities=%d pos=(%.1f, %.1f, %.1f)",
            instance.shipmentId(), instance.id(), instance.state(), instance.progress() * 100.0D,
            instance.entityIds().size(), instance.position().x, instance.position().y, instance.position().z)), false));
        return instances.size();
    }

    private static int showCaravan(final CommandSourceStack source, final String idText)
    {
        final UUID id = parseUuid(source, idText, "shipment");
        if (id == null) return 0;
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final CaravanInstance instance = data.caravanLedger().forShipment(id).orElse(null);
        final TradeShipment shipment = data.tradeLedger().shipment(id).orElse(null);
        if (shipment == null)
        {
            source.sendFailure(Component.literal("Unknown shipment " + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Shipment " + id + " is " + shipment.representation()
            + ", status=" + shipment.status() + String.format(Locale.ROOT, ", progress=%.1f%%",
            shipment.progressAt(source.getLevel().getGameTime()) * 100.0D)).withStyle(ChatFormatting.GOLD), false);
        final ShipmentPath path = new ShipmentPathResolver().resolve(data, shipment);
        if (path != null)
        {
            final double progress = shipment.progressAt(source.getLevel().getGameTime());
            final net.minecraft.world.phys.Vec3 position = path.positionAt(progress);
            final net.minecraft.world.phys.Vec3 waypoint = path.localWaypoint(progress,
                KingdomsConfig.SERVER.caravanLocalWaypointDistance.get());
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Path=%s length=%.1f pathPosition=(%.1f, %.1f, %.1f) nextWaypoint=(%.1f, %.1f, %.1f)%s",
                path instanceof RoadShipmentPath ? "ROAD" : "DIRECT", path.length(), position.x, position.y, position.z,
                waypoint.x, waypoint.y, waypoint.z,
                path instanceof RoadShipmentPath road ? String.format(Locale.ROOT, " speedMultiplier=%.2f", road.speedMultiplier()) : "")), false);
        }
        if (instance != null)
        {
            source.sendSuccess(() -> Component.literal("Caravan=" + instance.id() + " state=" + instance.state()
                + " dimension=" + instance.dimension() + " entities=" + instance.entityIds().size()), false);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Position=(%.1f, %.1f, %.1f) updated=%d moving=%d",
                instance.position().x, instance.position().y, instance.position().z,
                instance.lastUpdate(), instance.lastMovementAt())), false);
        }
        return 1;
    }

    private static int materializeCaravan(final CommandSourceStack source, final String idText)
    {
        final UUID id = parseUuid(source, idText, "shipment");
        if (id == null) return 0;
        final boolean result = CaravanManager.getInstance().materializeNow(source.getServer(), id);
        if (!result)
        {
            source.sendFailure(Component.literal("Shipment cannot be materialized (state, cap, endpoint, or chunk unavailable)"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Materialized caravan for shipment " + id), true);
        return 1;
    }

    private static int dematerializeCaravan(final CommandSourceStack source, final String idText)
    {
        final UUID id = parseUuid(source, idText, "shipment");
        if (id == null) return 0;
        final boolean result = CaravanManager.getInstance().dematerializeNow(source.getServer(), id);
        if (!result)
        {
            source.sendFailure(Component.literal("Shipment has no physical caravan"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Dematerialized caravan for shipment " + id), true);
        return 1;
    }

    private static int teleportCaravanNear(final CommandSourceStack source, final String idText)
    {
        final UUID id = parseUuid(source, idText, "shipment");
        if (id == null) return 0;
        final boolean result = CaravanManager.getInstance().teleportNear(source.getLevel(), id, source.getPosition());
        if (!result)
        {
            source.sendFailure(Component.literal("Shipment has no physical caravan in this dimension"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Teleported caravan near command source"), true);
        return 1;
    }

    private static int showCaravanStats(final CommandSourceStack source)
    {
        final CaravanStats stats = CaravanManager.getInstance().stats(KingdomsSavedData.get(source.getLevel()));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
            "Caravan stats: physical=%d materialized=%d dematerialized=%d pathFailures=%d avgLifetime=%.1f ticks stuckRecoveries=%d",
            stats.physicalCaravans(), stats.materializations(), stats.dematerializations(), stats.pathFailures(),
            stats.averagePhysicalLifetimeTicks(), stats.stuckRecoveries())).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int createNpcColony(final CommandSourceStack source, final String requestedName)
    {
        final String name = requestedName.trim();
        if (name.isEmpty())
        {
            source.sendFailure(Component.literal("Colony name must not be blank"));
            return 0;
        }
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        if (data.colonies().stream().anyMatch(colony -> colony.name().equalsIgnoreCase(name)))
        {
            source.sendFailure(Component.literal("A tracked colony named '" + name + "' already exists"));
            return 0;
        }
        final UUID colonyId = UUID.randomUUID();
        final UUID factionId = UUID.randomUUID();
        final BlockPos position = BlockPos.containing(
            source.getPosition().x,
            source.getPosition().y,
            source.getPosition().z);
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(
            colonyId,
            source.getLevel().dimension().location(),
            position,
            name,
            factionId,
            source.getLevel().getGameTime());
        final Faction faction = new Faction(factionId, name, FactionType.CITY_STATE);
        faction.setCapitalColonyId(colonyId);
        data.putFaction(faction);
        data.putColony(colony);
        source.sendSuccess(() -> Component.literal("Created strategic NPC colony " + name)
            .withStyle(ChatFormatting.GREEN), true);
        source.sendSuccess(() -> Component.literal("UUID: " + colonyId), false);
        source.sendSuccess(() -> Component.literal("Position: " + position.toShortString()), false);
        source.sendSuccess(() -> Component.literal("Mode: " + colony.simulationMode() + " / " + colony.kind()), false);
        return 1;
    }

    private static int deleteNpcColony(final CommandSourceStack source, final String idText)
    {
        final UUID id = parseUuid(source, idText, "colony");
        if (id == null)
        {
            return 0;
        }
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final NPCColonyData colony = data.colony(id).orElse(null);
        if (colony == null)
        {
            source.sendFailure(Component.literal("Unknown colony UUID " + id));
            return 0;
        }
        if (colony.kind() != ColonyKind.NPC_ABSTRACT)
        {
            source.sendFailure(Component.literal("Only strategic NPC colonies can be deleted by this command"));
            return 0;
        }
        TradeManager.getInstance().handleColonyDeletion(data, id, source.getLevel().getGameTime());
        data.removeColony(id);
        data.faction(colony.factionId()).ifPresent(faction -> {
            faction.removeSettlement(id);
            if (faction.settlementIds().isEmpty())
            {
                data.removeFaction(faction.id());
            }
        });
        data.markChanged();
        source.sendSuccess(() -> Component.literal("Deleted strategic NPC colony " + colony.name())
            .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int setEconomyLong(
        final CommandSourceStack source,
        final String colonyToken,
        final String resourceToken,
        final String field,
        final long value)
    {
        final NPCColonyData colony = findColony(source, colonyToken);
        final EconomicResource resource = parseResource(source, resourceToken);
        if (colony == null || resource == null)
        {
            return 0;
        }
        if ("stockpile".equals(field))
        {
            ECONOMY.setStockpile(colony, resource, value);
        }
        else
        {
            ECONOMY.setDesiredReserve(colony, resource, value);
        }
        KingdomsSavedData.get(source.getLevel()).markChanged();
        source.sendSuccess(() -> Component.literal("Set " + colony.name() + " " + resource + " " + field + " = " + value), true);
        return 1;
    }

    private static int setEconomyDouble(
        final CommandSourceStack source,
        final String colonyToken,
        final String resourceToken,
        final String field,
        final double value)
    {
        final NPCColonyData colony = findColony(source, colonyToken);
        final EconomicResource resource = parseResource(source, resourceToken);
        if (colony == null || resource == null)
        {
            return 0;
        }
        if ("production".equals(field))
        {
            ECONOMY.setProduction(colony, resource, value);
        }
        else
        {
            ECONOMY.setConsumption(colony, resource, value);
        }
        KingdomsSavedData.get(source.getLevel()).markChanged();
        source.sendSuccess(() -> Component.literal("Set " + colony.name() + " " + resource + " " + field + " = " + value), true);
        return 1;
    }

    private static int listColonies(final CommandSourceStack source)
    {
        final List<NPCColonyData> colonies = KingdomsSavedData.get(source.getLevel()).colonies().stream()
            .sorted(Comparator.comparing(NPCColonyData::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(NPCColonyData::id))
            .toList();
        source.sendSuccess(() -> Component.literal("Tracked colonies: " + colonies.size())
            .withStyle(ChatFormatting.GOLD), false);
        colonies.forEach(colony -> source.sendSuccess(() -> Component.literal(String.format(
            Locale.ROOT,
            "- %s %s [%s, %s] pop=%d id=%s",
            colony.mineColoniesColonyId().isPresent() ? "#" + colony.mineColoniesColonyId().getAsInt() : "NPC",
            colony.name(), colony.kind(), colony.simulationMode(), colony.population(), colony.id())), false));
        return colonies.size();
    }

    private static int showColony(final CommandSourceStack source, final String token)
    {
        final NPCColonyData colony = findColony(source, token);
        if (colony == null)
        {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Colony: " + colony.name()).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("UUID: " + colony.id()), false);
        source.sendSuccess(() -> Component.literal("Type: " + colony.kind() + "; MineColonies ID: "
            + (colony.mineColoniesColonyId().isPresent() ? colony.mineColoniesColonyId().getAsInt() : "none")), false);
        source.sendSuccess(() -> Component.literal("Dimension/center: " + colony.dimension() + " " + colony.center().toShortString()), false);
        source.sendSuccess(() -> Component.literal("Mode: " + colony.simulationMode() + "; created=" + colony.createdAt()), false);
        source.sendSuccess(() -> Component.literal("Population: " + colony.population() + " workers=" + colony.workers()
            + " soldiers=" + colony.soldiers()), false);
        source.sendSuccess(() -> Component.literal("Capacity: housing=" + colony.housingCapacity()
            + " storage=" + colony.storageCapacity()), false);
        source.sendSuccess(() -> Component.literal("Updates: strategic=" + colony.lastStrategicUpdate()
            + " economy=" + colony.lastEconomyUpdate()), false);
        source.sendSuccess(() -> Component.literal("Last decision: " + colony.lastDecision().action()
            + (colony.lastDecision().reason() == null ? "" : " because " + colony.lastDecision().reason())), false);
        return 1;
    }

    private static int showEconomy(final CommandSourceStack source, final String token)
    {
        final NPCColonyData colony = findColony(source, token);
        if (colony == null)
        {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Economy for " + colony.name()).withStyle(ChatFormatting.GOLD), false);
        for (final EconomicResource resource : EconomicResource.values())
        {
            final ResourceEconomy value = colony.economy().resource(resource);
            source.sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "- %s stock=%d reserve=%d production=%.2f/day consumption=%.2f/day net=%+.2f shortage=%d exportable=%d",
                resource, value.stockpile().amount(), value.stockpile().desiredReserve(),
                value.flow().productionPerDay(), value.flow().consumptionPerDay(), value.flow().netFlowPerDay(),
                value.stockpile().reserveShortage(), value.stockpile().exportableStock())), false);
        }
        return 1;
    }

    private static int showNeeds(final CommandSourceStack source, final String token)
    {
        final NPCColonyData colony = findColony(source, token);
        if (colony == null)
        {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Needs for " + colony.name() + ": " + colony.needs().size())
            .withStyle(ChatFormatting.GOLD), false);
        for (final ColonyNeed need : colony.needs())
        {
            source.sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT, "- %s %s current=%.2f target=%.2f shortage=%.1f%% since=%d",
                need.type(), need.severity(), need.current(), need.target(), need.shortageRatio() * 100.0D, need.createdAt())), false);
        }
        source.sendSuccess(() -> Component.literal("Decision: " + colony.lastDecision().action()
            + (colony.lastDecision().resource() == null ? "" : " " + colony.lastDecision().resource())), false);
        return 1;
    }

    private static int listRoutes(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<TradeRoute> routes = data.tradeLedger().routes().stream()
            .sorted(Comparator.comparingLong(TradeRoute::createdAt).thenComparing(TradeRoute::id))
            .toList();
        source.sendSuccess(() -> Component.literal("Trade routes: " + routes.size()).withStyle(ChatFormatting.GOLD), false);
        routes.forEach(route -> source.sendSuccess(() -> Component.literal(
            route.id() + " " + route.status() + " " + colonyName(data, route.originColonyId()) + " -> "
                + colonyName(data, route.destinationColonyId()) + " " + route.resource()
                + " target=" + route.targetAmountPerCycle() + " last=" + route.actualAmountLastCycle()), false));
        return routes.size();
    }

    private static int showRoute(final CommandSourceStack source, final String idText)
    {
        final UUID id = parseUuid(source, idText, "route");
        if (id == null)
        {
            return 0;
        }
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final TradeRoute route = data.tradeLedger().route(id).orElse(null);
        if (route == null)
        {
            source.sendFailure(Component.literal("Unknown trade route " + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Trade route " + route.id()).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal(route.status() + " " + colonyName(data, route.originColonyId())
            + " -> " + colonyName(data, route.destinationColonyId())), false);
        source.sendSuccess(() -> Component.literal(route.resource() + " target=" + route.targetAmountPerCycle()
            + "/cycle last=" + route.actualAmountLastCycle() + " priority=" + route.priority()), false);
        source.sendSuccess(() -> Component.literal("Created=" + route.createdAt() + " processed=" + route.lastProcessedAt()
            + (route.reason() == null ? "" : " reason=" + route.reason())), false);
        return 1;
    }

    private static int listShipments(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<TradeShipment> shipments = data.tradeLedger().shipments().stream()
            .sorted(Comparator.comparingLong(TradeShipment::createdAt).thenComparing(TradeShipment::id))
            .toList();
        source.sendSuccess(() -> Component.literal("Trade shipments: " + shipments.size()).withStyle(ChatFormatting.GOLD), false);
        shipments.forEach(shipment -> source.sendSuccess(() -> Component.literal(
            shipment.id() + " " + shipment.status() + " " + shipment.amount() + " " + shipment.resource()
                + " " + colonyName(data, shipment.originColonyId()) + " -> "
                + colonyName(data, shipment.destinationColonyId()) + " arrival=" + shipment.arrivalAt()), false));
        return shipments.size();
    }

    private static int showShipment(final CommandSourceStack source, final String idText)
    {
        final UUID id = parseUuid(source, idText, "shipment");
        if (id == null)
        {
            return 0;
        }
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final TradeShipment shipment = data.tradeLedger().shipment(id).orElse(null);
        if (shipment == null)
        {
            source.sendFailure(Component.literal("Unknown trade shipment " + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Trade shipment " + shipment.id()).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Route=" + shipment.routeId() + " status=" + shipment.status()
            + " representation=" + shipment.representation()), false);
        source.sendSuccess(() -> Component.literal(shipment.amount() + " " + shipment.resource() + " "
            + colonyName(data, shipment.originColonyId()) + " -> " + colonyName(data, shipment.destinationColonyId())), false);
        source.sendSuccess(() -> Component.literal("Created=" + shipment.createdAt() + " departure=" + shipment.departureAt()
            + " arrival=" + shipment.arrivalAt() + " duration=" + shipment.travelDurationTicks()
            + (shipment.failureReason() == null ? "" : " failure=" + shipment.failureReason()
                + " (" + shipment.failureDetail() + ")")), false);
        if (shipment.banditEncounterId() != null)
            source.sendSuccess(() -> Component.literal("Bandits took " + shipment.lostAmount() + "; deliverable=" + shipment.deliverableAmount()
                + " (encounter " + shipment.banditEncounterId() + ")").withStyle(ChatFormatting.RED), false);
        data.bandits().openForShipment(shipment.id()).ifPresent(encounter -> source.sendSuccess(() -> Component.literal(
            "Bandit encounter " + encounter.id() + " " + encounter.status() + " at " + encounter.position().toShortString()
                + (shipment.heldAt(source.getServer().overworld().getGameTime()) ? " (caravan held)" : "")).withStyle(ChatFormatting.RED), false));
        return 1;
    }

    private static int runTradeMatch(final CommandSourceStack source)
    {
        final TradeMatchResult result = TradeManager.getInstance().matchNow(source.getServer());
        source.sendSuccess(() -> Component.literal("Trade match: candidates=" + result.candidates()
            + " routesCreated=" + result.routesCreated() + " shipmentsPlanned=" + result.shipmentsPlanned()
            + String.format(Locale.ROOT, " duration=%.3f ms", result.durationNanos() / 1_000_000.0D)), true);
        return result.shipmentsPlanned();
    }

    private static int runTradeTick(final CommandSourceStack source)
    {
        final TradeProcessResult result = TradeManager.getInstance().processNow(source.getServer());
        source.sendSuccess(() -> Component.literal("Trade tick: departed=" + result.departed()
            + " delivered=" + result.delivered() + " failed=" + result.failed()
            + " resourcesDelivered=" + result.resourcesDelivered()), true);
        return result.departed() + result.delivered() + result.failed();
    }

    private static int showTradeStats(final CommandSourceStack source)
    {
        final TradeStats stats = TradeManager.getInstance().stats(KingdomsSavedData.get(source.getLevel()));
        source.sendSuccess(() -> Component.literal(String.format(
            Locale.ROOT,
            "Trade stats: routes active=%d paused=%d broken=%d; shipments planned=%d transit=%d delivered=%d failed=%d; moved=%d; matches=%d avg=%.3f ms max=%.3f ms",
            stats.activeRoutes(), stats.pausedRoutes(), stats.brokenRoutes(), stats.plannedShipments(),
            stats.shipmentsInTransit(), stats.shipmentsDelivered(), stats.shipmentsFailed(), stats.resourcesMoved(),
            stats.matchingCycles(), stats.averageMatchingDurationNanos() / 1_000_000.0D,
            stats.maxMatchingDurationNanos() / 1_000_000.0D)).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int listFactions(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        final List<Faction> factions = data.factions().stream()
            .sorted(Comparator.comparing(Faction::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
        source.sendSuccess(() -> Component.literal("Kingdoms factions: " + factions.size()).withStyle(ChatFormatting.GOLD), false);
        factions.forEach(faction -> source.sendSuccess(() -> Component.literal(
            "- " + faction.name() + " [" + faction.type() + "] settlements=" + faction.settlementIds().size()
                + " treasury=" + faction.treasury()), false));
        return factions.size();
    }

    private static int showSimulation(final CommandSourceStack source)
    {
        source.sendSuccess(() -> Component.literal("Kingdoms simulation").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Enabled: " + KingdomsConfig.SERVER.simulationEnabled.get()), false);
        source.sendSuccess(() -> Component.literal("Batch/interval: " + KingdomsConfig.SERVER.batchSize.get()
            + "/" + KingdomsConfig.SERVER.strategicIntervalTicks.get() + " ticks"), false);
        source.sendSuccess(() -> Component.literal("Economy interval: " + KingdomsConfig.SERVER.economyIntervalTicks.get() + " ticks"), false);
        source.sendSuccess(() -> Component.literal("Budget: " + KingdomsConfig.SERVER.maxWorkNanosPerTick.get()
            + " ns/tick; radii=" + KingdomsConfig.SERVER.activeRadius.get() + "/"
            + KingdomsConfig.SERVER.effectiveDeactivationRadius()), false);
        return showSimulationStats(source);
    }

    private static int showSimulationStats(final CommandSourceStack source)
    {
        final SimulationStats stats = WorldSimulationManager.getInstance().stats();
        source.sendSuccess(() -> Component.literal(String.format(
            Locale.ROOT,
            "Stats: colonies=%d batches=%d pending=%d avg=%.3f ms max=%.3f ms economy=%d AI=%d",
            stats.coloniesProcessed(), stats.batchesProcessed(), stats.pendingColonies(),
            stats.averageColonyDurationNanos() / 1_000_000.0D,
            stats.maxColonyDurationNanos() / 1_000_000.0D,
            stats.economyUpdates(), stats.aiEvaluations())), false);
        return 1;
    }

    private static NPCColonyData findColony(final CommandSourceStack source, final String token)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        NPCColonyData colony = null;
        try
        {
            colony = data.colony(UUID.fromString(token)).orElse(null);
        }
        catch (IllegalArgumentException ignored)
        {
            // The token may be a MineColonies numeric id or a unique name.
        }
        if (colony == null)
        {
            try
            {
                colony = data.colonyByMineColoniesId(Integer.parseInt(token)).orElse(null);
            }
            catch (NumberFormatException ignored)
            {
                // Continue with a name lookup.
            }
        }
        if (colony == null)
        {
            final List<NPCColonyData> named = data.colonies().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(token))
                .toList();
            if (named.size() == 1)
            {
                colony = named.getFirst();
            }
            else if (named.size() > 1)
            {
                source.sendFailure(Component.literal("Colony name is ambiguous; use UUID"));
                return null;
            }
        }
        if (colony == null)
        {
            source.sendFailure(Component.literal("No tracked colony matching '" + token + "'"));
        }
        return colony;
    }

    private static EconomicResource parseResource(final CommandSourceStack source, final String token)
    {
        try
        {
            return EconomicResource.valueOf(token.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception)
        {
            source.sendFailure(Component.literal("Unknown resource '" + token + "'"));
            return null;
        }
    }

    private static UUID parseUuid(final CommandSourceStack source, final String value, final String subject)
    {
        try
        {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException exception)
        {
            source.sendFailure(Component.literal("Invalid " + subject + " UUID: " + value));
            return null;
        }
    }

    private static String colonyName(final KingdomsSavedData data, final UUID id)
    {
        return data.colony(id).map(NPCColonyData::name).orElse("<deleted:" + id + ">");
    }

    private static int showDebug(final CommandSourceStack source)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(source.getLevel());
        source.sendSuccess(() -> Component.literal("MineColonies: Kingdoms debug").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("MineColonies colonies: " + IColonyManager.getInstance().getAllColonies().size()), false);
        source.sendSuccess(() -> Component.literal("Tracked colonies: " + data.colonies().size()), false);
        source.sendSuccess(() -> Component.literal("Factions: " + data.factions().size()), false);
        source.sendSuccess(() -> Component.literal("Kingdoms: " + data.kingdoms().size()), false);
        source.sendSuccess(() -> Component.literal("Trade routes/shipments: " + data.tradeLedger().routes().size()
            + "/" + data.tradeLedger().shipments().size()), false);
        source.sendSuccess(() -> Component.literal("Settlements/roads: " + data.settlements().records().size()
            + "/" + data.roads().roads().size()), false);
        source.sendSuccess(() -> Component.literal("Growth states/buildings: " + data.growth().states().size()
            + "/" + data.growth().buildings().size()), false);
        source.sendSuccess(() -> Component.literal("SavedData version: " + KingdomsSavedData.DATA_VERSION), false);
        return 1;
    }
}
