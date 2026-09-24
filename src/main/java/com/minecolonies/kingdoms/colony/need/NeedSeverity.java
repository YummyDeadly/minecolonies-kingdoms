package com.minecolonies.kingdoms.colony.need;

public enum NeedSeverity
{
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static NeedSeverity fromShortageRatio(final double ratio)
    {
        final double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        if (clamped >= 0.75D)
        {
            return CRITICAL;
        }
        if (clamped >= 0.50D)
        {
            return HIGH;
        }
        if (clamped >= 0.25D)
        {
            return MEDIUM;
        }
        return LOW;
    }
}
