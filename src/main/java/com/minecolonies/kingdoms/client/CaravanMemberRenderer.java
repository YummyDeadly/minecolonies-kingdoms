package com.minecolonies.kingdoms.client;

import com.minecolonies.kingdoms.entity.caravan.CaravanMemberEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public final class CaravanMemberRenderer extends MobRenderer<CaravanMemberEntity, HumanoidModel<CaravanMemberEntity>>
{
    private static final ResourceLocation TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public CaravanMemberRenderer(final EntityRendererProvider.Context context)
    {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(final CaravanMemberEntity entity)
    {
        return TEXTURE;
    }
}
