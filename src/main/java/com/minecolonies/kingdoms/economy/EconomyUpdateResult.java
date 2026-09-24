package com.minecolonies.kingdoms.economy;

public record EconomyUpdateResult(boolean updated, boolean physicalSnapshotApplied)
{
    public static EconomyUpdateResult skipped()
    {
        return new EconomyUpdateResult(false, false);
    }
}
