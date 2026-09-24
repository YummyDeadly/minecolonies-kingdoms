package com.minecolonies.kingdoms.colony;

public enum ColonyKind
{
    PLAYER_PHYSICAL,
    NPC_PHYSICAL,
    NPC_ABSTRACT;

    public boolean isNpcControlled()
    {
        return this != PLAYER_PHYSICAL;
    }

    public boolean hasPhysicalMineColoniesColony()
    {
        return this != NPC_ABSTRACT;
    }
}
