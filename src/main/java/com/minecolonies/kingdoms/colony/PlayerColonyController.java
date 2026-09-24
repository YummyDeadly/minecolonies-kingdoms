package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.economy.EconomyUpdateResult;

public final class PlayerColonyController implements ColonyController
{
    @Override
    public ColonyControllerResult tick(final ColonyUpdateContext context)
    {
        final EconomyUpdateResult economy = ControllerSupport.observeAndUpdate(context);
        return new ColonyControllerResult(economy.updated(), false);
    }
}
