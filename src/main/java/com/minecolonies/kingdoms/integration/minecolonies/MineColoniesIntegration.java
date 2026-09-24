package com.minecolonies.kingdoms.integration.minecolonies;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.eventbus.events.colony.ColonyCreatedModEvent;
import com.minecolonies.api.eventbus.events.colony.ColonyDeletedModEvent;
import com.minecolonies.api.eventbus.events.colony.ColonyNameChangedModEvent;
import com.minecolonies.kingdoms.KingdomsMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class MineColoniesIntegration
{
    private static boolean registered;

    private MineColoniesIntegration()
    {
    }

    public static synchronized void registerEventHooks()
    {
        if (registered)
        {
            return;
        }
        IMinecoloniesAPI.getInstance().getEventBus().subscribe(ColonyCreatedModEvent.class,
            event -> synchronize(event.getColony()));
        IMinecoloniesAPI.getInstance().getEventBus().subscribe(ColonyNameChangedModEvent.class,
            event -> synchronize(event.getColony()));
        IMinecoloniesAPI.getInstance().getEventBus().subscribe(ColonyDeletedModEvent.class,
            event -> removed(event.getColony()));
        registered = true;
        KingdomsMod.LOGGER.info("Registered MineColonies public API event hooks");
    }

    public static void synchronizeAll(final MinecraftServer server)
    {
        for (final IColony colony : IColonyManager.getInstance().getAllColonies())
        {
            final ServerLevel level = server.getLevel(colony.getDimension());
            if (level != null)
            {
                MineColoniesColonySynchronizer.synchronize(level, colony);
            }
        }
    }

    /** A MineColonies colony was deleted: its Kingdoms record goes the same way (routes break, cargo is handled, history). */
    private static void removed(final IColony colony)
    {
        try
        {
            final MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.isSameThread()) return;
            final com.minecolonies.kingdoms.persistence.KingdomsSavedData data =
                com.minecolonies.kingdoms.persistence.KingdomsSavedData.get(server.overworld());
            com.minecolonies.kingdoms.colony.ColonyRemoval.remove(data,
                MineColoniesColonySynchronizer.colonyId(colony.getDimension().location(), colony.getID()), server.overworld().getGameTime(),
                "its MineColonies colony was deleted");
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error("Could not remove the Kingdoms record of deleted colony {}", colony.getID(), exception);
        }
    }

    private static void synchronize(final IColony colony)
    {
        if (colony.getWorld() instanceof ServerLevel level)
        {
            MineColoniesColonySynchronizer.synchronize(level, colony);
        }
    }
}
