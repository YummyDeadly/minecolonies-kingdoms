package com.minecolonies.kingdoms.citizen;

/**
 * Detects a representative that stopped approaching its waypoint. After a timeout without half a block of
 * progress the waypoint is skipped; after repeated skips the representative gives up (and is safely dematerialized
 * to respawn later at a safe anchor). No teleporting through walls.
 */
public final class StuckTracker
{
    public enum Verdict { MOVING, SKIP_WAYPOINT, GIVE_UP }

    public static final int MAX_SKIPS = 3;
    private final long timeoutTicks;
    private double best = Double.MAX_VALUE;
    private long lastProgress = Long.MIN_VALUE;
    private int skips;

    public StuckTracker(final long timeoutTicks) { this.timeoutTicks = timeoutTicks; }

    public Verdict update(final double distance, final long gameTime)
    {
        if (lastProgress == Long.MIN_VALUE || distance < best - 0.5D)
        {
            best = distance;
            lastProgress = gameTime;
            return Verdict.MOVING;
        }
        if (gameTime - lastProgress < timeoutTicks) return Verdict.MOVING;
        reset();
        return ++skips >= MAX_SKIPS ? Verdict.GIVE_UP : Verdict.SKIP_WAYPOINT;
    }

    /** Called when a new waypoint is chosen; the skip count survives so repeated failures still give up. */
    public void reset()
    {
        best = Double.MAX_VALUE;
        lastProgress = Long.MIN_VALUE;
    }

    public void arrived() { reset(); skips = 0; }
    public int skips() { return skips; }
}
