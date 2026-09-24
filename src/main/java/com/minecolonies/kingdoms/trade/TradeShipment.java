package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

public final class TradeShipment
{
    private final UUID id;
    private final UUID routeId;
    private final UUID originColonyId;
    private final UUID destinationColonyId;
    private final EconomicResource resource;
    private final long amount;
    private final long createdAt;
    private ShipmentRepresentation representation;
    private long departureAt;
    private long arrivalAt;
    private long travelDurationTicks;
    private long progressUpdatedAt;
    private double storedProgress;
    private TradeShipmentStatus status;
    private ShipmentFailureReason failureReason;
    private String failureDetail;
    /** Cargo taken by bandits (Phase 8); applied at most once, by one encounter. */
    private long lostAmount;
    private UUID banditEncounterId;

    public TradeShipment(
        final UUID id,
        final UUID routeId,
        final UUID originColonyId,
        final UUID destinationColonyId,
        final EconomicResource resource,
        final long amount,
        final long createdAt)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.routeId = Objects.requireNonNull(routeId, "routeId");
        this.originColonyId = Objects.requireNonNull(originColonyId, "originColonyId");
        this.destinationColonyId = Objects.requireNonNull(destinationColonyId, "destinationColonyId");
        this.resource = Objects.requireNonNull(resource, "resource");
        if (originColonyId.equals(destinationColonyId) || amount <= 0L || createdAt < 0L)
        {
            throw new IllegalArgumentException("Invalid trade shipment");
        }
        this.amount = amount;
        this.createdAt = createdAt;
        this.status = TradeShipmentStatus.PLANNED;
        this.representation = ShipmentRepresentation.ABSTRACT;
    }

    public UUID id() { return id; }
    public UUID routeId() { return routeId; }
    public UUID originColonyId() { return originColonyId; }
    public UUID destinationColonyId() { return destinationColonyId; }
    public EconomicResource resource() { return resource; }
    public long amount() { return amount; }
    public long createdAt() { return createdAt; }
    public long departureAt() { return departureAt; }
    public long arrivalAt() { return arrivalAt; }
    public TradeShipmentStatus status() { return status; }
    public ShipmentRepresentation representation() { return representation; }
    public ShipmentFailureReason failureReason() { return failureReason; }
    public String failureDetail() { return failureDetail; }
    public long travelDurationTicks() { return travelDurationTicks; }
    public double storedProgress() { return storedProgress; }
    public long lostAmount() { return lostAmount; }
    public UUID banditEncounterId() { return banditEncounterId; }
    /** What reaches the destination: the original amount minus cargo lost to bandits. */
    public long deliverableAmount() { return Math.max(0L, amount - lostAmount); }

    /**
     * Records the cargo an encounter took, exactly once per shipment. Returns false (and changes nothing) if a loss
     * was already recorded, so a repeated resolution can never take cargo twice.
     */
    public boolean recordBanditLoss(final UUID encounterId, final long lost)
    {
        requireInTransit();
        if (banditEncounterId != null) return false;
        banditEncounterId = Objects.requireNonNull(encounterId, "encounterId");
        lostAmount = Math.max(0L, Math.min(amount, lost));
        return true;
    }

    /**
     * Stops abstract progress until {@code until} (an encounter holding the caravan, or a delay after it). Physical
     * progress is driven by the caravan and unaffected. Arrival moves back by the same time.
     */
    public void holdUntil(final long gameTime, final long until)
    {
        requireInTransit();
        if (representation == ShipmentRepresentation.PHYSICAL || until <= gameTime) return;
        storedProgress = progressAt(gameTime);
        progressUpdatedAt = Math.max(progressUpdatedAt, until);
        final long remaining = (long) Math.ceil((1.0D - storedProgress) * Math.max(1L, travelDurationTicks));
        arrivalAt = progressUpdatedAt > Long.MAX_VALUE - remaining ? Long.MAX_VALUE : progressUpdatedAt + remaining;
    }

    /** Ends a hold early (the encounter was resolved sooner); progress continues from where it stood. */
    public void releaseHold(final long gameTime)
    {
        requireInTransit();
        if (representation == ShipmentRepresentation.PHYSICAL || progressUpdatedAt <= gameTime) return;
        progressUpdatedAt = gameTime;
        final long remaining = (long) Math.ceil((1.0D - storedProgress) * Math.max(1L, travelDurationTicks));
        arrivalAt = gameTime > Long.MAX_VALUE - remaining ? Long.MAX_VALUE : gameTime + remaining;
    }

    public boolean heldAt(final long gameTime)
    {
        return status == TradeShipmentStatus.IN_TRANSIT && representation == ShipmentRepresentation.ABSTRACT
            && progressUpdatedAt > gameTime;
    }

    public void depart(final long gameTime, final long travelTicks)
    {
        if (status != TradeShipmentStatus.PLANNED || gameTime < 0L || travelTicks <= 0L)
        {
            throw new IllegalStateException("Only a planned shipment can depart");
        }
        departureAt = gameTime;
        arrivalAt = gameTime > Long.MAX_VALUE - travelTicks ? Long.MAX_VALUE : gameTime + travelTicks;
        travelDurationTicks = travelTicks;
        progressUpdatedAt = gameTime;
        storedProgress = 0.0D;
        representation = ShipmentRepresentation.ABSTRACT;
        status = TradeShipmentStatus.IN_TRANSIT;
    }

    public double progressAt(final long gameTime)
    {
        if (status == TradeShipmentStatus.DELIVERED)
        {
            return 1.0D;
        }
        if (status != TradeShipmentStatus.IN_TRANSIT || representation == ShipmentRepresentation.PHYSICAL)
        {
            return storedProgress;
        }
        final long elapsed = Math.max(0L, gameTime - progressUpdatedAt);
        return clampProgress(storedProgress + elapsed / (double) Math.max(1L, travelDurationTicks));
    }

    public void materialize(final long gameTime)
    {
        requireInTransit();
        storedProgress = progressAt(gameTime);
        progressUpdatedAt = gameTime;
        representation = ShipmentRepresentation.PHYSICAL;
    }

    public void updatePhysicalProgress(final double progress, final long gameTime)
    {
        requireInTransit();
        if (representation != ShipmentRepresentation.PHYSICAL)
        {
            throw new IllegalStateException("Only a physical shipment accepts physical progress");
        }
        storedProgress = Math.max(storedProgress, clampProgress(progress));
        progressUpdatedAt = Math.max(progressUpdatedAt, gameTime);
    }

    public void dematerialize(final long gameTime)
    {
        requireInTransit();
        representation = ShipmentRepresentation.ABSTRACT;
        progressUpdatedAt = gameTime;
        final long remaining = (long) Math.ceil((1.0D - storedProgress) * Math.max(1L, travelDurationTicks));
        arrivalAt = gameTime > Long.MAX_VALUE - remaining ? Long.MAX_VALUE : gameTime + remaining;
    }

    public void deliver()
    {
        if (status != TradeShipmentStatus.IN_TRANSIT)
        {
            throw new IllegalStateException("Only in-transit cargo can be delivered");
        }
        status = TradeShipmentStatus.DELIVERED;
        representation = ShipmentRepresentation.ABSTRACT;
        storedProgress = 1.0D;
    }

    public void fail(final ShipmentFailureReason reason, final String detail)
    {
        if (status == TradeShipmentStatus.DELIVERED || status == TradeShipmentStatus.FAILED)
        {
            return;
        }
        failureReason = Objects.requireNonNull(reason, "reason");
        failureDetail = requireReason(detail);
        representation = ShipmentRepresentation.ABSTRACT;
        status = TradeShipmentStatus.FAILED;
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("route", routeId);
        tag.putUUID("origin", originColonyId);
        tag.putUUID("destination", destinationColonyId);
        tag.putString("resource", resource.name());
        tag.putLong("amount", amount);
        tag.putLong("createdAt", createdAt);
        tag.putLong("departureAt", departureAt);
        tag.putLong("arrivalAt", arrivalAt);
        tag.putLong("travelDurationTicks", travelDurationTicks);
        tag.putLong("progressUpdatedAt", progressUpdatedAt);
        tag.putDouble("progress", storedProgress);
        tag.putString("status", status.name());
        tag.putString("representation", representation.name());
        if (failureReason != null)
        {
            tag.putString("failureReason", failureReason.name());
            tag.putString("failureDetail", failureDetail);
        }
        if (banditEncounterId != null)
        {
            tag.putUUID("banditEncounter", banditEncounterId);
            tag.putLong("lostAmount", lostAmount);
        }
        return tag;
    }

    public static TradeShipment load(final CompoundTag tag)
    {
        final TradeShipment shipment = new TradeShipment(
            tag.getUUID("id"),
            tag.getUUID("route"),
            tag.getUUID("origin"),
            tag.getUUID("destination"),
            EconomicResource.valueOf(tag.getString("resource")),
            Math.max(1L, tag.getLong("amount")),
            Math.max(0L, tag.getLong("createdAt")));
        shipment.departureAt = Math.max(0L, tag.getLong("departureAt"));
        shipment.arrivalAt = Math.max(0L, tag.getLong("arrivalAt"));
        shipment.travelDurationTicks = Math.max(1L, tag.contains("travelDurationTicks")
            ? tag.getLong("travelDurationTicks")
            : Math.max(1L, shipment.arrivalAt - shipment.departureAt));
        shipment.progressUpdatedAt = Math.max(0L, tag.contains("progressUpdatedAt")
            ? tag.getLong("progressUpdatedAt")
            : shipment.departureAt);
        shipment.storedProgress = clampProgress(tag.getDouble("progress"));
        shipment.status = TradeShipmentStatus.valueOf(tag.getString("status"));
        shipment.representation = tag.contains("representation")
            ? ShipmentRepresentation.valueOf(tag.getString("representation"))
            : ShipmentRepresentation.ABSTRACT;
        if (tag.contains("failureReason"))
        {
            final String storedReason = tag.getString("failureReason");
            try
            {
                shipment.failureReason = ShipmentFailureReason.valueOf(storedReason);
            }
            catch (IllegalArgumentException ignored)
            {
                shipment.failureReason = ShipmentFailureReason.TECHNICAL_FAILURE;
            }
            shipment.failureDetail = tag.contains("failureDetail") ? tag.getString("failureDetail") : storedReason;
        }
        if (tag.hasUUID("banditEncounter"))
        {
            shipment.banditEncounterId = tag.getUUID("banditEncounter");
            shipment.lostAmount = Math.max(0L, Math.min(shipment.amount, tag.getLong("lostAmount")));
        }
        return shipment;
    }

    private void requireInTransit()
    {
        if (status != TradeShipmentStatus.IN_TRANSIT)
        {
            throw new IllegalStateException("Shipment is not in transit");
        }
    }

    private static double clampProgress(final double value)
    {
        if (!Double.isFinite(value))
        {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String requireReason(final String value)
    {
        final String result = Objects.requireNonNull(value, "reason").trim();
        if (result.isEmpty())
        {
            throw new IllegalArgumentException("Failure reason must not be blank");
        }
        return result;
    }
}
