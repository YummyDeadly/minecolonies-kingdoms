package com.minecolonies.kingdoms.event;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.command.KingdomsCommands;
import com.minecolonies.kingdoms.caravan.CaravanManager;
import com.minecolonies.kingdoms.integration.minecolonies.MineColoniesIntegration;
import com.minecolonies.kingdoms.integration.structurize.SettlementStructureService;
import com.minecolonies.kingdoms.simulation.WorldSimulationManager;
import com.minecolonies.kingdoms.trade.TradeManager;
import com.minecolonies.kingdoms.world.WorldSettlementManager;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementGrowthManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

public final class KingdomsEventHandler
{
    private KingdomsEventHandler()
    {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event)
    {
        KingdomsCommands.register(event.getDispatcher());
        KingdomsMod.LOGGER.info("Registered /kingdoms server commands");
    }

    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event)
    {
        SettlementStructureService.getInstance().initialize(event.getServer()).thenAccept(state -> {
            if (state == SettlementStructureService.State.READY)
                event.getServer().execute(() -> SettlementGrowthManager.getInstance().catalogReady(event.getServer()));
        });
        MineColoniesIntegration.synchronizeAll(event.getServer());
        WorldSimulationManager.getInstance().initialize(event.getServer());
        TradeManager.getInstance().initialize(event.getServer());
        CaravanManager.getInstance().initialize(event.getServer());
        WorldSettlementManager.getInstance().initialize(event.getServer());
        SettlementGrowthManager.getInstance().initialize(event.getServer());
        com.minecolonies.kingdoms.citizen.SettlementCitizenManager.getInstance().initialize(event.getServer());
        com.minecolonies.kingdoms.contract.ContractManager.getInstance().initialize(event.getServer());
        com.minecolonies.kingdoms.diplomacy.DiplomacyManager.getInstance().initialize(event.getServer());
        com.minecolonies.kingdoms.bandit.BanditManager.getInstance().initialize(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event)
    {
        WorldSimulationManager.getInstance().tick(event.getServer());
        TradeManager.getInstance().tick(event.getServer());
        CaravanManager.getInstance().tick(event.getServer());
        WorldSettlementManager.getInstance().tick();
        SettlementGrowthManager.getInstance().tick(event.getServer());
        com.minecolonies.kingdoms.citizen.SettlementCitizenManager.getInstance().tick(event.getServer());
        com.minecolonies.kingdoms.contract.ContractManager.getInstance().tick(event.getServer());
        com.minecolonies.kingdoms.diplomacy.DiplomacyManager.getInstance().tick(event.getServer());
        com.minecolonies.kingdoms.bandit.BanditManager.getInstance().tick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event)
    {
        SettlementStructureService.getInstance().shutdown();
        WorldSimulationManager.getInstance().shutdown();
        TradeManager.getInstance().shutdown();
        CaravanManager.getInstance().shutdown();
        WorldSettlementManager.getInstance().shutdown();
        SettlementGrowthManager.getInstance().shutdown();
        com.minecolonies.kingdoms.citizen.SettlementCitizenManager.getInstance().shutdown();
        com.minecolonies.kingdoms.contract.ContractManager.getInstance().shutdown();
        com.minecolonies.kingdoms.diplomacy.DiplomacyManager.getInstance().shutdown();
        com.minecolonies.kingdoms.bandit.BanditManager.getInstance().shutdown();
    }

    @SubscribeEvent
    public static void onChunkLoad(final ChunkEvent.Load event)
    {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        WorldSettlementManager.getInstance().queueChunkLoad(level, chunk, event.isNewChunk());
    }
}
