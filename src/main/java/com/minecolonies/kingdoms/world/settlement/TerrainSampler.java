package com.minecolonies.kingdoms.world.settlement;

@FunctionalInterface
public interface TerrainSampler
{
    TerrainSample sample(int x, int z);
}
