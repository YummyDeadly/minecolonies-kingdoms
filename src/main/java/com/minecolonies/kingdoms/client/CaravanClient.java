package com.minecolonies.kingdoms.client;

import com.minecolonies.kingdoms.entity.caravan.ModEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class CaravanClient
{
    private CaravanClient()
    {
    }

    public static void register(final IEventBus modBus)
    {
        modBus.addListener(CaravanClient::registerRenderers);
        modBus.addListener(CaravanClient::registerLayers);
    }

    private static void registerLayers(final EntityRenderersEvent.RegisterLayerDefinitions event)
    {
        event.registerLayerDefinition(SettlementCitizenRenderer.WIDE, SettlementCitizenRenderer::wide);
        event.registerLayerDefinition(SettlementCitizenRenderer.SLIM, SettlementCitizenRenderer::slim);
        event.registerLayerDefinition(BanditRenderer.LAYER, BanditRenderer::layer);
    }

    private static void registerRenderers(final EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(ModEntities.CARAVAN_MEMBER.get(), CaravanMemberRenderer::new);
        event.registerEntityRenderer(ModEntities.SETTLEMENT_CITIZEN.get(), SettlementCitizenRenderer::new);
        event.registerEntityRenderer(ModEntities.BANDIT.get(), BanditRenderer::new);
    }
}
