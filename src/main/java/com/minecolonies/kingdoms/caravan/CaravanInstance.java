package com.minecolonies.kingdoms.caravan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CaravanInstance
{
    private final UUID id;
    private final UUID shipmentId;
    private final ResourceLocation dimension;
    private final Vec3 origin;
    private final Vec3 destination;
    private final long createdAt;
    private final Set<UUID> entityIds = new LinkedHashSet<>();
    private Vec3 position;
    private CaravanState state;
    private long lastUpdate;
    private long lastMovementAt;
    private long lastPathAttemptAt;
    private double progress;
    private UUID observerPlayerId;
    private long forcedUntil;

    public CaravanInstance(
        final UUID id,
        final UUID shipmentId,
        final ResourceLocation dimension,
        final Vec3 origin,
        final Vec3 destination,
        final Vec3 position,
        final double progress,
        final long createdAt,
        final UUID observerPlayerId,
        final long forcedUntil)
    {
        this.id = id;
        this.shipmentId = shipmentId;
        this.dimension = dimension;
        this.origin = origin;
        this.destination = destination;
        this.position = position;
        this.progress = progress;
        this.createdAt = createdAt;
        this.lastUpdate = createdAt;
        this.lastMovementAt = createdAt;
        this.lastPathAttemptAt = createdAt;
        this.observerPlayerId = observerPlayerId;
        this.forcedUntil = forcedUntil;
        this.state = CaravanState.MATERIALIZING;
    }

    public UUID id() { return id; }
    public UUID shipmentId() { return shipmentId; }
    public ResourceLocation dimension() { return dimension; }
    public Vec3 origin() { return origin; }
    public Vec3 destination() { return destination; }
    public Vec3 position() { return position; }
    public CaravanState state() { return state; }
    public long createdAt() { return createdAt; }
    public long lastUpdate() { return lastUpdate; }
    public long lastMovementAt() { return lastMovementAt; }
    public long lastPathAttemptAt() { return lastPathAttemptAt; }
    public double progress() { return progress; }
    public Optional<UUID> observerPlayerId() { return Optional.ofNullable(observerPlayerId); }
    public long forcedUntil() { return forcedUntil; }
    public Set<UUID> entityIds() { return Collections.unmodifiableSet(entityIds); }

    public void setEntities(final Set<UUID> ids)
    {
        entityIds.clear();
        entityIds.addAll(ids);
    }

    /** Standing still on purpose (held by bandits) is not being stuck. */
    public void hold(final long gameTime)
    {
        lastMovementAt = gameTime;
        lastUpdate = gameTime;
    }

    public void update(final Vec3 newPosition, final double newProgress, final long gameTime)
    {
        if (newPosition.distanceToSqr(position) > 0.25D || newProgress > progress + 0.0001D)
        {
            lastMovementAt = gameTime;
        }
        position = newPosition;
        progress = Math.max(progress, Math.min(1.0D, newProgress));
        lastUpdate = gameTime;
        state = CaravanState.TRAVELLING;
    }

    public void state(final CaravanState value) { state = value; }
    public void forcedUntil(final long value) { forcedUntil = value; }
    public void markPathAttempt(final long gameTime) { lastPathAttemptAt = gameTime; }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("shipment", shipmentId);
        tag.putString("dimension", dimension.toString());
        putVec(tag, "origin", origin);
        putVec(tag, "destination", destination);
        putVec(tag, "position", position);
        tag.putString("state", state.name());
        tag.putLong("createdAt", createdAt);
        tag.putLong("lastUpdate", lastUpdate);
        tag.putLong("lastMovementAt", lastMovementAt);
        tag.putLong("lastPathAttemptAt", lastPathAttemptAt);
        tag.putDouble("progress", progress);
        tag.putLong("forcedUntil", forcedUntil);
        if (observerPlayerId != null)
        {
            tag.putUUID("observer", observerPlayerId);
        }
        final ListTag entities = new ListTag();
        entityIds.forEach(entityId -> {
            final CompoundTag entity = new CompoundTag();
            entity.putUUID("id", entityId);
            entities.add(entity);
        });
        tag.put("entities", entities);
        return tag;
    }

    public static CaravanInstance load(final CompoundTag tag)
    {
        final CaravanInstance instance = new CaravanInstance(
            tag.getUUID("id"), tag.getUUID("shipment"), ResourceLocation.parse(tag.getString("dimension")),
            getVec(tag, "origin"), getVec(tag, "destination"), getVec(tag, "position"),
            tag.getDouble("progress"), tag.getLong("createdAt"),
            tag.hasUUID("observer") ? tag.getUUID("observer") : null, tag.getLong("forcedUntil"));
        instance.state = CaravanState.valueOf(tag.getString("state"));
        instance.lastUpdate = tag.getLong("lastUpdate");
        instance.lastMovementAt = tag.getLong("lastMovementAt");
        instance.lastPathAttemptAt = tag.contains("lastPathAttemptAt")
            ? tag.getLong("lastPathAttemptAt") : instance.lastUpdate;
        tag.getList("entities", Tag.TAG_COMPOUND).forEach(value -> instance.entityIds.add(((CompoundTag) value).getUUID("id")));
        return instance;
    }

    private static void putVec(final CompoundTag tag, final String key, final Vec3 value)
    {
        final CompoundTag vector = new CompoundTag();
        vector.putDouble("x", value.x);
        vector.putDouble("y", value.y);
        vector.putDouble("z", value.z);
        tag.put(key, vector);
    }

    private static Vec3 getVec(final CompoundTag tag, final String key)
    {
        final CompoundTag vector = tag.getCompound(key);
        return new Vec3(vector.getDouble("x"), vector.getDouble("y"), vector.getDouble("z"));
    }
}
