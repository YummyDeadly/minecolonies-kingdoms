package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One bandit encounter: the authoritative record. Physical bandits only represent it.
 *
 * <pre>
 * PLANNED --shipment reaches the ambush point--> ACTIVE --resolution--> RESOLVED_BANDITS | RESOLVED_CARAVAN | RESOLVED_PLAYER
 *    |                                             |--roadblock lifetime over--> EXPIRED
 *    +--shipment gone / operator--> CANCELLED <-----+--shipment gone / operator
 * </pre>
 *
 * Representation (ABSTRACT or PHYSICAL) is separate from the status, like for shipments. Terminal statuses never
 * change, and every resolution goes through {@link EncounterService}, which checks the status first; there is no
 * second path to a result.
 */
public final class BanditEncounter
{
    public enum Kind { AMBUSH, ROADBLOCK }

    public enum Status
    {
        PLANNED, ACTIVE, RESOLVED_BANDITS, RESOLVED_CARAVAN, RESOLVED_PLAYER, EXPIRED, CANCELLED;

        public boolean terminal() { return this != PLANNED && this != ACTIVE; }
    }

    public enum Representation { ABSTRACT, PHYSICAL }

    /** Why an encounter reached its final status. */
    public enum Cause { ABSTRACT_ROLL, PLAYER_VICTORY, CARAVAN_GUARDS, CARAVAN_OVERRUN, SHIPMENT_GONE, ROAD_GONE, LIFETIME_OVER, ADMIN }

    public static final int MAX_DEFENDERS = 8;

    private final UUID id;
    private final Kind kind;
    private final UUID roadId;
    private final UUID shipmentId;
    private final ResourceLocation dimension;
    private final BlockPos position;
    private final double triggerProgress;
    private final long seed;
    private final double threatAtCreation;
    private final int strength;
    private final long createdAt;
    private Status status;
    private Representation representation = Representation.ABSTRACT;
    private int remainingStrength;
    private long activatedAt = -1L;
    private long resolveAt = -1L;
    private long expiresAt;
    private long resolvedAt = -1L;
    private Cause cause;
    private EncounterRules.Outcome outcome;
    private EconomicResource cargoResource;
    private long cargoBefore;
    private long cargoLost;
    private long delayTicks;
    private final List<UUID> defenders = new ArrayList<>();
    private boolean consequencesApplied;

    public BanditEncounter(final UUID id, final Kind kind, final UUID roadId, final UUID shipmentId, final ResourceLocation dimension,
        final BlockPos position, final double triggerProgress, final double threatAtCreation, final int strength,
        final long createdAt, final long expiresAt)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.roadId = Objects.requireNonNull(roadId, "roadId");
        this.shipmentId = shipmentId;
        if (kind == Kind.AMBUSH && shipmentId == null) throw new IllegalArgumentException("An ambush needs a shipment");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.position = Objects.requireNonNull(position, "position").immutable();
        this.triggerProgress = Math.max(0.0D, Math.min(1.0D, triggerProgress));
        if (strength < 1) throw new IllegalArgumentException("Encounter strength must be positive");
        this.seed = EncounterRules.seed(id);
        this.threatAtCreation = threatAtCreation;
        this.strength = strength;
        this.remainingStrength = strength;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = kind == Kind.AMBUSH ? Status.PLANNED : Status.ACTIVE;
        if (kind == Kind.ROADBLOCK) activatedAt = createdAt;
    }

    public UUID id() { return id; }
    public Kind kind() { return kind; }
    public UUID roadId() { return roadId; }
    public UUID shipmentId() { return shipmentId; }
    public ResourceLocation dimension() { return dimension; }
    public BlockPos position() { return position; }
    public double triggerProgress() { return triggerProgress; }
    public long seed() { return seed; }
    public double threatAtCreation() { return threatAtCreation; }
    public int strength() { return strength; }
    public int remainingStrength() { return remainingStrength; }
    public long createdAt() { return createdAt; }
    public Status status() { return status; }
    public Representation representation() { return representation; }
    public long activatedAt() { return activatedAt; }
    public long resolveAt() { return resolveAt; }
    public long expiresAt() { return expiresAt; }
    public long resolvedAt() { return resolvedAt; }
    public Cause cause() { return cause; }
    public EncounterRules.Outcome outcome() { return outcome; }
    public EconomicResource cargoResource() { return cargoResource; }
    public long cargoBefore() { return cargoBefore; }
    public long cargoLost() { return cargoLost; }
    public long delayTicks() { return delayTicks; }
    public List<UUID> defenders() { return List.copyOf(defenders); }
    public boolean consequencesApplied() { return consequencesApplied; }
    public boolean open() { return !status.terminal(); }

    // ------------------------------------------------------------------------------------------------ transitions

    void activate(final long gameTime, final long resolveAfter)
    {
        if (status != Status.PLANNED) throw new IllegalStateException("Encounter " + id + " is " + status);
        status = Status.ACTIVE;
        activatedAt = gameTime;
        resolveAt = gameTime + resolveAfter;
    }

    void representation(final Representation value)
    {
        requireOpen();
        representation = Objects.requireNonNull(value);
    }

    /** Leaving a fight never decides it on the spot: the abstract resolution waits at least {@code grace} more ticks. */
    void deferResolution(final long gameTime, final long grace)
    {
        if (status == Status.ACTIVE && kind == Kind.AMBUSH) resolveAt = Math.max(resolveAt, gameTime + grace);
    }

    /** One bandit of this encounter died; returns the remaining strength. */
    int banditLost()
    {
        requireOpen();
        remainingStrength = Math.max(0, remainingStrength - 1);
        return remainingStrength;
    }

    void defender(final UUID player)
    {
        if (status.terminal() && consequencesApplied) return; // a settled encounter never changes
        if (!defenders.contains(player) && defenders.size() < MAX_DEFENDERS) defenders.add(player);
    }

    void resolve(final Status terminal, final Cause resolutionCause, final EncounterRules.Outcome result, final long gameTime)
    {
        if (!terminal.terminal()) throw new IllegalArgumentException("Not a terminal status: " + terminal);
        if (status.terminal()) throw new IllegalStateException("Encounter " + id + " is already " + status);
        status = terminal;
        cause = Objects.requireNonNull(resolutionCause, "cause");
        outcome = result;
        resolvedAt = gameTime;
        representation = Representation.ABSTRACT;
    }

    void recordCargo(final EconomicResource resource, final long before, final long lost, final long delay)
    {
        cargoResource = resource;
        cargoBefore = before;
        cargoLost = lost;
        delayTicks = delay;
    }

    void markConsequencesApplied() { consequencesApplied = true; }

    private void requireOpen()
    {
        if (status.terminal()) throw new IllegalStateException("Encounter " + id + " is already " + status);
    }

    // ------------------------------------------------------------------------------------------------ persistence

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("kind", kind.name());
        tag.putUUID("road", roadId);
        if (shipmentId != null) tag.putUUID("shipment", shipmentId);
        tag.putString("dimension", dimension.toString());
        tag.putLong("position", position.asLong());
        tag.putDouble("trigger", triggerProgress);
        tag.putDouble("threat", threatAtCreation);
        tag.putInt("strength", strength);
        tag.putInt("remaining", remainingStrength);
        tag.putLong("createdAt", createdAt);
        tag.putString("status", status.name());
        tag.putString("representation", representation.name());
        tag.putLong("activatedAt", activatedAt);
        tag.putLong("resolveAt", resolveAt);
        tag.putLong("expiresAt", expiresAt);
        tag.putLong("resolvedAt", resolvedAt);
        if (cause != null) tag.putString("cause", cause.name());
        if (outcome != null) tag.putString("outcome", outcome.name());
        if (cargoResource != null) tag.putString("cargoResource", cargoResource.name());
        tag.putLong("cargoBefore", cargoBefore);
        tag.putLong("cargoLost", cargoLost);
        tag.putLong("delay", delayTicks);
        final ListTag list = new ListTag();
        defenders.forEach(player -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", player);
            list.add(entry);
        });
        tag.put("defenders", list);
        tag.putBoolean("consequencesApplied", consequencesApplied);
        return tag;
    }

    static BanditEncounter load(final CompoundTag tag)
    {
        final BanditEncounter encounter = new BanditEncounter(tag.getUUID("id"), Kind.valueOf(tag.getString("kind")),
            tag.getUUID("road"), tag.hasUUID("shipment") ? tag.getUUID("shipment") : null,
            ResourceLocation.parse(tag.getString("dimension")), BlockPos.of(tag.getLong("position")), tag.getDouble("trigger"),
            tag.getDouble("threat"), Math.max(1, tag.getInt("strength")), tag.getLong("createdAt"), tag.getLong("expiresAt"));
        encounter.remainingStrength = Math.max(0, Math.min(encounter.strength, tag.getInt("remaining")));
        encounter.status = Status.valueOf(tag.getString("status"));
        // physical bandits never survive a restart: every loaded encounter starts abstract
        encounter.representation = Representation.ABSTRACT;
        encounter.activatedAt = tag.getLong("activatedAt");
        encounter.resolveAt = tag.getLong("resolveAt");
        encounter.resolvedAt = tag.getLong("resolvedAt");
        encounter.cause = tag.contains("cause") ? Cause.valueOf(tag.getString("cause")) : null;
        encounter.outcome = tag.contains("outcome") ? EncounterRules.Outcome.valueOf(tag.getString("outcome")) : null;
        encounter.cargoResource = tag.contains("cargoResource") ? EconomicResource.valueOf(tag.getString("cargoResource")) : null;
        encounter.cargoBefore = tag.getLong("cargoBefore");
        encounter.cargoLost = tag.getLong("cargoLost");
        encounter.delayTicks = tag.getLong("delay");
        tag.getList("defenders", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            if (entry.hasUUID("id")) encounter.defender(entry.getUUID("id"));
        });
        encounter.consequencesApplied = tag.getBoolean("consequencesApplied");
        if (encounter.status.terminal() && encounter.cause == null)
            throw new IllegalArgumentException("Resolved encounter " + encounter.id + " without cause");
        return encounter;
    }
}
