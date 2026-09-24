package com.minecolonies.kingdoms.client;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.citizen.CitizenAppearance;
import com.minecolonies.kingdoms.entity.citizen.SettlementCitizenEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kingdoms-owned humanoid renderer. The model is vanilla player geometry baked for a 128x64 texture. MineColonies'
 * {@code MaleCitizenModel}/{@code FemaleCitizenModel} use exactly the player UV layout for head (0,0) plus the +0.5
 * overlay at (32,0), body/jacket, arms/sleeves (4 wide, 3 wide for female) and legs/pants, so their textures map 1:1;
 * only the optional accessory cubes MineColonies draws from the right half of the texture are not rendered. Textures
 * are referenced at runtime from the installed MineColonies resources and never copied; if a texture is missing or
 * not in that layout (MineColonies' 128x128 dress models, legacy 64x32 skins), the default MineColonies style is
 * tried, then a vanilla default skin.
 */
public final class SettlementCitizenRenderer extends MobRenderer<SettlementCitizenEntity, PlayerModel<SettlementCitizenEntity>>
{
    public static final ModelLayerLocation WIDE = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(KingdomsMod.MOD_ID, "settlement_citizen"), "wide");
    public static final ModelLayerLocation SLIM = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(KingdomsMod.MOD_ID, "settlement_citizen"), "slim");
    private static final Map<ResourceLocation, Boolean> AVAILABLE = new ConcurrentHashMap<>();

    private final PlayerModel<SettlementCitizenEntity> citizenWide;
    private final PlayerModel<SettlementCitizenEntity> citizenSlim;
    private final PlayerModel<SettlementCitizenEntity> vanillaWide;
    private final PlayerModel<SettlementCitizenEntity> vanillaSlim;

    public SettlementCitizenRenderer(final EntityRendererProvider.Context context)
    {
        super(context, new PlayerModel<>(context.bakeLayer(WIDE), false), 0.5F);
        citizenWide = model;
        citizenSlim = new PlayerModel<>(context.bakeLayer(SLIM), true);
        vanillaWide = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false);
        vanillaSlim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        // The hat part stays visible: MineColonies bakes the same (32,0) +0.5 overlay into its head (hair, caps).
    }

    public static LayerDefinition wide() { return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 128, 64); }
    public static LayerDefinition slim() { return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, true), 128, 64); }

    @Override
    public void render(final SettlementCitizenEntity entity, final float yaw, final float partialTicks, final PoseStack pose,
        final MultiBufferSource buffers, final int light)
    {
        final Appearance appearance = appearance(entity);
        model = appearance.model();
        super.render(entity, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(final SettlementCitizenEntity entity)
    {
        return appearance(entity).texture();
    }

    private Appearance appearance(final SettlementCitizenEntity entity)
    {
        final String key = entity.textureKey();
        final boolean female = key.contains("female");
        if (!key.isEmpty())
        {
            final ResourceLocation styled = citizenTexture(key);
            if (available(styled)) return new Appearance(styled, female ? citizenSlim : citizenWide);
            final String name = key.substring(key.indexOf('/') + 1);
            final ResourceLocation fallback = citizenTexture("default/" + name);
            if (available(fallback)) return new Appearance(fallback, female ? citizenSlim : citizenWide);
        }
        final PlayerSkin skin = DefaultPlayerSkin.get(entity.getUUID());
        return new Appearance(skin.texture(), skin.model() == PlayerSkin.Model.SLIM ? vanillaSlim : vanillaWide);
    }

    static ResourceLocation citizenTexture(final String key)
    {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/entity/citizen/" + key + ".png");
    }

    /** Present and in the humanoid layout (read once from the PNG header; resource packs may replace textures). */
    static boolean available(final ResourceLocation texture)
    {
        return AVAILABLE.computeIfAbsent(texture, SettlementCitizenRenderer::humanoidTexture);
    }

    private static boolean humanoidTexture(final ResourceLocation location)
    {
        final var resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return false;
        try (InputStream stream = resource.get().open())
        {
            final byte[] header = stream.readNBytes(24);
            if (header.length < 24 || header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') return false;
            final ByteBuffer buffer = ByteBuffer.wrap(header);
            final boolean humanoid = CitizenAppearance.humanoidLayout(buffer.getInt(16), buffer.getInt(20));
            if (!humanoid) KingdomsMod.LOGGER.debug("Citizen texture {} is not a 128x64 humanoid layout; using a fallback", location);
            return humanoid;
        }
        catch (IOException exception)
        {
            return false;
        }
    }

    private record Appearance(ResourceLocation texture, PlayerModel<SettlementCitizenEntity> model) {}
}
