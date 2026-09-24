package com.minecolonies.kingdoms.citizen;

import java.util.Locale;

/**
 * Deterministic outfit key {@code "<style>/<outfit><gender><variant>_<tone>"}. On the client it resolves to the
 * installed MineColonies citizen texture {@code minecolonies:textures/entity/citizen/<key>.png} (a runtime resource
 * reference, no copied asset); the renderer falls back to the default style and then to a vanilla skin.
 */
public final class CitizenAppearance
{
    private static final String[] TONES = {"a", "b", "d", "w"};

    private CitizenAppearance() {}

    public static String textureKey(final String styleFamily, final CitizenRole role, final boolean female, final long seed)
    {
        final int variant = 1 + (int) Math.floorMod(seed >>> 11, role.textureVariants(female));
        final String tone = TONES[(int) Math.floorMod(seed >>> 5, TONES.length)];
        return textureStyle(styleFamily) + '/' + role.texturePrefix(female) + (female ? "female" : "male") + variant + '_' + tone;
    }

    /**
     * Whether a texture of this size uses the 128x64 humanoid layout (or an HD multiple of it). MineColonies also ships
     * 128x128 dress-model textures and legacy 64x32 skins; mapping those onto the humanoid model produces garbage.
     */
    public static boolean humanoidLayout(final int width, final int height)
    {
        return width >= 128 && width % 128 == 0 && width == height * 2;
    }

    /** Maps a settlement's blueprint style family to one of MineColonies' eight citizen texture sets. */
    public static String textureStyle(final String styleFamily)
    {
        final String style = styleFamily == null ? "" : styleFamily.toLowerCase(Locale.ROOT);
        if (style.contains("medieval") || style.contains("fortress") || style.contains("colonial")) return "medieval";
        if (style.contains("asian") || style.contains("shogun") || style.contains("pagoda") || style.contains("oriental")) return "eastasian";
        if (style.contains("athens") || style.contains("greek") || style.contains("hellen") || style.contains("roman")) return "hellenic";
        if (style.contains("nordic") || style.contains("viking") || style.contains("caledonia") || style.contains("norse")) return "nordic";
        if (style.contains("nether") || style.contains("warped") || style.contains("crimson")) return "nether";
        if (style.contains("modern") || style.contains("industrial") || style.contains("urban")) return "modern";
        if (style.contains("undead") || style.contains("spooky") || style.contains("haunted")) return "undead";
        return "default";
    }
}
