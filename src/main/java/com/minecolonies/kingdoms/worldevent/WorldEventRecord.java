package com.minecolonies.kingdoms.worldevent;

import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * One world event: the authoritative record (Phase 11).
 *
 * <pre>
 * PLANNED --startsAt, subject still valid--> ACTIVE --endsAt--> RESOLVED
 *    |  +--startsAt, subject no longer valid--> EXPIRED
 *    +--operator / subject gone-------------------------> CANCELLED <--operator / subject gone-- ACTIVE
 * </pre>
 *
 * The effect is applied at most once ({@code applied}), and a reversible effect is given back at most once
 * ({@code reverted}); the amounts actually applied are stored so that giving back never returns more than was taken.
 * Terminal statuses never change. Transitions are package-private and made only by {@link WorldEventService}.
 */
public final class WorldEventRecord
{
    public enum Status
    {
        PLANNED, ACTIVE, RESOLVED, EXPIRED, CANCELLED;

        public boolean terminal() { return this != PLANNED && this != ACTIVE; }
    }

    /** GENERATED: by the periodic evaluation; ADMIN: by an operator command. */
    public enum Cause { GENERATED, ADMIN }

    public static final int MAX_REASON = 200;

    private final UUID id;
    private final WorldEventType type;
    private final Cause cause;
    private final long seed;
    private final ResourceLocation dimension;
    private final UUID settlementId;
    private final UUID otherSettlementId;
    private final UUID factionA;
    private final UUID factionB;
    private final UUID roadId;
    private final EconomicResource resource;
    private final double magnitude;
    private final long plannedAt;
    private final long startsAt;
    private final long endsAt;
    private final String reason;
    private Status status = Status.PLANNED;
    private long resolvedAt = -1L;
    private boolean applied;
    private boolean reverted;
    /** Production per day actually added (negative: removed) while active. */
    private double productionDelta;
    /** Stock actually lost at activation. */
    private long stockLost;
    /** Soldiers actually joined (positive) or lost (negative). */
    private int soldiers;
    /** Relation change actually applied. */
    private int relationChange;
    /** People who actually moved. */
    private int peopleMoved;
    private String outcome = "";

    public WorldEventRecord(final UUID id, final WorldEventType type, final Cause cause, final long seed, final ResourceLocation dimension,
        final UUID settlementId, final UUID otherSettlementId, final UUID factionA, final UUID factionB, final UUID roadId,
        final EconomicResource resource, final double magnitude, final long plannedAt, final long startsAt, final long endsAt, final String reason)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.seed = seed;
        this.dimension = dimension;
        this.settlementId = settlementId;
        this.otherSettlementId = otherSettlementId;
        this.factionA = factionA;
        this.factionB = factionB;
        this.roadId = roadId;
        this.resource = resource;
        this.magnitude = Double.isFinite(magnitude) ? Math.max(0.0D, Math.min(1.0D, magnitude)) : 0.0D;
        if (startsAt < plannedAt || endsAt < startsAt) throw new IllegalArgumentException("Event times out of order");
        this.plannedAt = plannedAt;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason == null ? "" : reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
        switch (type.scope())
        {
            case SETTLEMENT -> Objects.requireNonNull(settlementId, "settlementId");
            case ROAD -> Objects.requireNonNull(roadId, "roadId");
            case PAIR -> { Objects.requireNonNull(factionA, "factionA"); Objects.requireNonNull(factionB, "factionB"); }
            case MIGRATION -> { Objects.requireNonNull(settlementId, "settlementId"); Objects.requireNonNull(otherSettlementId, "otherSettlementId"); }
        }
    }

    public UUID id() { return id; }
    public WorldEventType type() { return type; }
    public Cause cause() { return cause; }
    public long seed() { return seed; }
    public ResourceLocation dimension() { return dimension; }
    public UUID settlementId() { return settlementId; }
    public UUID otherSettlementId() { return otherSettlementId; }
    public UUID factionA() { return factionA; }
    public UUID factionB() { return factionB; }
    public UUID roadId() { return roadId; }
    public EconomicResource resource() { return resource; }
    public double magnitude() { return magnitude; }
    public long plannedAt() { return plannedAt; }
    public long startsAt() { return startsAt; }
    public long endsAt() { return endsAt; }
    public String reason() { return reason; }
    public Status status() { return status; }
    public long resolvedAt() { return resolvedAt; }
    public boolean applied() { return applied; }
    public boolean reverted() { return reverted; }
    public double productionDelta() { return productionDelta; }
    public long stockLost() { return stockLost; }
    public int soldiers() { return soldiers; }
    public int relationChange() { return relationChange; }
    public int peopleMoved() { return peopleMoved; }
    public String outcome() { return outcome; }
    public boolean open() { return !status.terminal(); }
    public boolean activeAt(final long gameTime) { return status == Status.ACTIVE && gameTime < endsAt; }

    /** Whether the event concerns this settlement (as subject or destination). */
    public boolean involves(final UUID settlement)
    {
        return settlement != null && (settlement.equals(settlementId) || settlement.equals(otherSettlementId));
    }

    // ------------------------------------------------------------------------------------------------ transitions

    void activate()
    {
        if (status != Status.PLANNED) throw new IllegalStateException("Event " + id + " is " + status);
        status = Status.ACTIVE;
    }

    /** Records the effect actually applied; only once. */
    void applied(final double production, final long lost, final int soldierChange, final int relation, final int moved)
    {
        if (applied) throw new IllegalStateException("Event " + id + " already applied its effect");
        applied = true;
        productionDelta = production;
        stockLost = lost;
        soldiers = soldierChange;
        relationChange = relation;
        peopleMoved = moved;
    }

    void markReverted()
    {
        if (reverted) throw new IllegalStateException("Event " + id + " already gave its effect back");
        reverted = true;
    }

    void end(final Status terminal, final long gameTime, final String result)
    {
        if (!terminal.terminal()) throw new IllegalArgumentException("Not a terminal status: " + terminal);
        if (status.terminal()) throw new IllegalStateException("Event " + id + " is already " + status);
        status = terminal;
        resolvedAt = gameTime;
        outcome = result == null ? "" : result.length() > MAX_REASON ? result.substring(0, MAX_REASON) : result;
    }

    // ------------------------------------------------------------------------------------------------ persistence

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("type", type.name());
        tag.putString("cause", cause.name());
        tag.putLong("seed", seed);
        if (dimension != null) tag.putString("dimension", dimension.toString());
        if (settlementId != null) tag.putUUID("settlement", settlementId);
        if (otherSettlementId != null) tag.putUUID("other", otherSettlementId);
        if (factionA != null) tag.putUUID("factionA", factionA);
        if (factionB != null) tag.putUUID("factionB", factionB);
        if (roadId != null) tag.putUUID("road", roadId);
        if (resource != null) tag.putString("resource", resource.name());
        tag.putDouble("magnitude", magnitude);
        tag.putLong("plannedAt", plannedAt);
        tag.putLong("startsAt", startsAt);
        tag.putLong("endsAt", endsAt);
        tag.putString("reason", reason);
        tag.putString("status", status.name());
        tag.putLong("resolvedAt", resolvedAt);
        tag.putBoolean("applied", applied);
        tag.putBoolean("reverted", reverted);
        tag.putDouble("productionDelta", productionDelta);
        tag.putLong("stockLost", stockLost);
        tag.putInt("soldiers", soldiers);
        tag.putInt("relationChange", relationChange);
        tag.putInt("peopleMoved", peopleMoved);
        tag.putString("outcome", outcome);
        return tag;
    }

    static WorldEventRecord load(final CompoundTag tag)
    {
        final WorldEventRecord record = new WorldEventRecord(tag.getUUID("id"), WorldEventType.valueOf(tag.getString("type")),
            Cause.valueOf(tag.getString("cause")), tag.getLong("seed"),
            tag.contains("dimension") ? ResourceLocation.tryParse(tag.getString("dimension")) : null,
            tag.hasUUID("settlement") ? tag.getUUID("settlement") : null, tag.hasUUID("other") ? tag.getUUID("other") : null,
            tag.hasUUID("factionA") ? tag.getUUID("factionA") : null, tag.hasUUID("factionB") ? tag.getUUID("factionB") : null,
            tag.hasUUID("road") ? tag.getUUID("road") : null,
            tag.contains("resource") ? EconomicResource.valueOf(tag.getString("resource")) : null, tag.getDouble("magnitude"),
            tag.getLong("plannedAt"), tag.getLong("startsAt"), tag.getLong("endsAt"), tag.getString("reason"));
        record.status = Status.valueOf(tag.getString("status"));
        record.resolvedAt = tag.getLong("resolvedAt");
        record.applied = tag.getBoolean("applied");
        record.reverted = tag.getBoolean("reverted");
        final double production = tag.getDouble("productionDelta");
        record.productionDelta = Double.isFinite(production) ? production : 0.0D;
        record.stockLost = Math.max(0L, tag.getLong("stockLost"));
        record.soldiers = tag.getInt("soldiers");
        record.relationChange = tag.getInt("relationChange");
        record.peopleMoved = Math.max(0, tag.getInt("peopleMoved"));
        record.outcome = tag.getString("outcome");
        return record;
    }
}
