package com.minecolonies.kingdoms.client;

import com.minecolonies.kingdoms.entity.KnightAppearance;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/**
 * Kingdoms-owned renderer for garrison guards (Phase 9) and army soldiers (Phase 10): the citizen humanoid model with
 * MineColonies' installed knight citizen textures referenced at runtime (never copied), plus vanilla armour and
 * held-item layers for their gear. Falls back to the default MineColonies style and then to a vanilla skin, like
 * settlement representatives.
 */
public final class KnightRenderer<T extends Mob & KnightAppearance> extends MobRenderer<T, PlayerModel<T>>
{
    private final PlayerModel<T> citizenWide;
    private final PlayerModel<T> citizenSlim;
    private final PlayerModel<T> vanillaWide;
    private final PlayerModel<T> vanillaSlim;

    public KnightRenderer(final EntityRendererProvider.Context context)
    {
        super(context, new PlayerModel<>(context.bakeLayer(SettlementCitizenRenderer.WIDE), false), 0.5F);
        citizenWide = model;
        citizenSlim = new PlayerModel<>(context.bakeLayer(SettlementCitizenRenderer.SLIM), true);
        vanillaWide = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false);
        vanillaSlim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new HumanoidArmorLayer<>(this, new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public void render(final T entity, final float yaw, final float partialTicks, final PoseStack pose,
        final MultiBufferSource buffers, final int light)
    {
        model = appearance(entity).model();
        super.render(entity, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(final T entity)
    {
        return appearance(entity).texture();
    }

    private Appearance<T> appearance(final T entity)
    {
        final String key = entity.textureKey();
        final boolean female = key.contains("female");
        if (!key.isEmpty())
        {
            final ResourceLocation styled = SettlementCitizenRenderer.citizenTexture(key);
            if (SettlementCitizenRenderer.available(styled)) return new Appearance<>(styled, female ? citizenSlim : citizenWide);
            final ResourceLocation fallback = SettlementCitizenRenderer.citizenTexture("default/" + key.substring(key.indexOf('/') + 1));
            if (SettlementCitizenRenderer.available(fallback)) return new Appearance<>(fallback, female ? citizenSlim : citizenWide);
        }
        final PlayerSkin skin = DefaultPlayerSkin.get(entity.getUUID());
        return new Appearance<>(skin.texture(), skin.model() == PlayerSkin.Model.SLIM ? vanillaSlim : vanillaWide);
    }

    private record Appearance<E extends Mob>(ResourceLocation texture, PlayerModel<E> model) {}
}
