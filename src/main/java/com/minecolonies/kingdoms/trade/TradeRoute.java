package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

public final class TradeRoute
{
    private final UUID id;
    private final UUID originColonyId;
    private final UUID destinationColonyId;
    private final EconomicResource resource;
    private final long createdAt;
    private long targetAmountPerCycle;
    private long actualAmountLastCycle;
    private long lastProcessedAt;
    private TradeRouteStatus status;
    private int priority;
    private int unmatchedCycles;
    private String reason;

    public TradeRoute(
        final UUID id,
        final UUID originColonyId,
        final UUID destinationColonyId,
        final EconomicResource resource,
        final long targetAmountPerCycle,
        final long createdAt,
        final int priority)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.originColonyId = Objects.requireNonNull(originColonyId, "originColonyId");
        this.destinationColonyId = Objects.requireNonNull(destinationColonyId, "destinationColonyId");
        this.resource = Objects.requireNonNull(resource, "resource");
        if (originColonyId.equals(destinationColonyId) || targetAmountPerCycle <= 0L || createdAt < 0L)
        {
            throw new IllegalArgumentException("Invalid trade route");
        }
        this.targetAmountPerCycle = targetAmountPerCycle;
        this.createdAt = createdAt;
        this.priority = priority;
        this.status = TradeRouteStatus.ACTIVE;
    }

    public UUID id()
    {
        return id;
    }

    public UUID originColonyId()
    {
        return originColonyId;
    }

    public UUID destinationColonyId()
    {
        return destinationColonyId;
    }

    public EconomicResource resource()
    {
        return resource;
    }

    public TradeRouteKey key()
    {
        return new TradeRouteKey(originColonyId, destinationColonyId, resource);
    }

    public long targetAmountPerCycle()
    {
        return targetAmountPerCycle;
    }

    public long actualAmountLastCycle()
    {
        return actualAmountLastCycle;
    }

    public long createdAt()
    {
        return createdAt;
    }

    public long lastProcessedAt()
    {
        return lastProcessedAt;
    }

    public TradeRouteStatus status()
    {
        return status;
    }

    public int priority()
    {
        return priority;
    }

    public int unmatchedCycles()
    {
        return unmatchedCycles;
    }

    public String reason()
    {
        return reason;
    }

    public void matched(final long targetAmount, final int newPriority, final long gameTime)
    {
        if (targetAmount <= 0L || gameTime < 0L)
        {
            throw new IllegalArgumentException("Invalid route match");
        }
        targetAmountPerCycle = targetAmount;
        priority = newPriority;
        unmatchedCycles = 0;
        status = TradeRouteStatus.ACTIVE;
        reason = null;
        lastProcessedAt = gameTime;
    }

    public void unmatched(final int pauseGraceCycles, final long gameTime)
    {
        if (pauseGraceCycles <= 0 || gameTime < 0L || status == TradeRouteStatus.BROKEN)
        {
            return;
        }
        unmatchedCycles++;
        lastProcessedAt = gameTime;
        if (unmatchedCycles >= pauseGraceCycles)
        {
            status = TradeRouteStatus.PAUSED;
            reason = "No compatible deficit/offer";
        }
    }

    public void recordShipment(final long amount, final long gameTime)
    {
        if (amount <= 0L || gameTime < 0L)
        {
            throw new IllegalArgumentException("Invalid shipment record");
        }
        actualAmountLastCycle = amount;
        lastProcessedAt = gameTime;
    }

    public void breakRoute(final String brokenReason, final long gameTime)
    {
        status = TradeRouteStatus.BROKEN;
        reason = requireReason(brokenReason);
        lastProcessedAt = Math.max(0L, gameTime);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("origin", originColonyId);
        tag.putUUID("destination", destinationColonyId);
        tag.putString("resource", resource.name());
        tag.putLong("targetAmountPerCycle", targetAmountPerCycle);
        tag.putLong("actualAmountLastCycle", actualAmountLastCycle);
        tag.putLong("createdAt", createdAt);
        tag.putLong("lastProcessedAt", lastProcessedAt);
        tag.putString("status", status.name());
        tag.putInt("priority", priority);
        tag.putInt("unmatchedCycles", unmatchedCycles);
        if (reason != null)
        {
            tag.putString("reason", reason);
        }
        return tag;
    }

    public static TradeRoute load(final CompoundTag tag)
    {
        final TradeRoute route = new TradeRoute(
            tag.getUUID("id"),
            tag.getUUID("origin"),
            tag.getUUID("destination"),
            EconomicResource.valueOf(tag.getString("resource")),
            Math.max(1L, tag.getLong("targetAmountPerCycle")),
            Math.max(0L, tag.getLong("createdAt")),
            tag.getInt("priority"));
        route.actualAmountLastCycle = Math.max(0L, tag.getLong("actualAmountLastCycle"));
        route.lastProcessedAt = Math.max(0L, tag.getLong("lastProcessedAt"));
        route.status = TradeRouteStatus.valueOf(tag.getString("status"));
        route.unmatchedCycles = Math.max(0, tag.getInt("unmatchedCycles"));
        route.reason = tag.contains("reason") ? tag.getString("reason") : null;
        return route;
    }

    private static String requireReason(final String value)
    {
        final String result = Objects.requireNonNull(value, "reason").trim();
        if (result.isEmpty())
        {
            throw new IllegalArgumentException("Reason must not be blank");
        }
        return result;
    }
}
