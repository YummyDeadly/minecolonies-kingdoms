package com.minecolonies.kingdoms.integration.minecolonies;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.eventbus.events.colony.ColonyCreatedModEvent;
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

    private static void synchronize(final IColony colony)
    {
        if (colony.getWorld() instanceof ServerLevel level)
        {
            MineColoniesColonySynchronizer.synchronize(level, colony);
        }
    }
}
