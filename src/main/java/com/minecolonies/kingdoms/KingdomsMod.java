package com.minecolonies.kingdoms;

import com.minecolonies.kingdoms.event.KingdomsEventHandler;
import com.minecolonies.kingdoms.client.CaravanClient;
import com.minecolonies.kingdoms.entity.caravan.ModEntities;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.integration.minecolonies.MineColoniesIntegration;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(KingdomsMod.MOD_ID)
public final class KingdomsMod
{
    public static final String MOD_ID = "minecolonies_kingdoms";
    public static final Logger LOGGER = LogUtils.getLogger();

    public KingdomsMod(final FMLModContainer container, final Dist dist)
    {
        final IEventBus modBus = container.getEventBus();
        container.registerConfig(ModConfig.Type.SERVER, KingdomsConfig.SERVER_SPEC);
        ModEntities.register(modBus);
        if (dist == Dist.CLIENT)
        {
            CaravanClient.register(modBus);
        }
        modBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(KingdomsEventHandler.class);
        LOGGER.info("Initializing MineColonies: Kingdoms on {}", dist);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        event.enqueueWork(MineColoniesIntegration::registerEventHooks);
    }
}
