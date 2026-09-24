package com.minecolonies.kingdoms.colony;

/**
 * Strategic control surface above a MineColonies colony.
 * Implementations must be server-side and are scheduled by the global simulation.
 */
public interface ColonyController
{
    ColonyControllerResult tick(ColonyUpdateContext context);
}
