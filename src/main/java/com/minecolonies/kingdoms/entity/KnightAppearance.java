package com.minecolonies.kingdoms.entity;

/**
 * A humanoid Kingdoms representative drawn with a MineColonies citizen texture (referenced at runtime by key, never
 * copied): settlement guards (Phase 9) and army soldiers (Phase 10). Client renderers read only this key.
 */
public interface KnightAppearance
{
    String textureKey();
}
