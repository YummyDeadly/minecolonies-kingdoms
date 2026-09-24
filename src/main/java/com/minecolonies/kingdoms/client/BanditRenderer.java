package com.minecolonies.kingdoms.client;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.entity.bandit.BanditEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Kingdoms-owned humanoid renderer for bandits. MineColonies' barbarian raider textures (64x32, standard humanoid
 * layout) are referenced at runtime and never copied; MineColonies is a required dependency.
 */
public final class BanditRenderer extends HumanoidMobRenderer<BanditEntity, HumanoidModel<BanditEntity>>
{
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(KingdomsMod.MOD_ID, "bandit"), "main");
    private static final ResourceLocation BANDIT = ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/entity/raiders/barbarian1.png");
    private static final ResourceLocation CHIEF = ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/entity/raiders/barbarianchief1.png");

    public BanditRenderer(final EntityRendererProvider.Context context)
    {
        super(context, new HumanoidModel<>(context.bakeLayer(LAYER)), 0.5F);
    }

    public static LayerDefinition layer()
    {
        return LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F), 64, 32);
    }

    @Override
    public ResourceLocation getTextureLocation(final BanditEntity entity)
    {
        return entity.chief() ? CHIEF : BANDIT;
    }
}
