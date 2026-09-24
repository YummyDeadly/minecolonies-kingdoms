package com.minecolonies.kingdoms.war;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One army in the field (Phase 10): a persistent strategic object, never a set of saved soldiers. Its soldiers were
 * detached from one settlement's garrison in one transaction and return there (survivors only) in one transaction.
 *
 * <pre>
 * MARCHING --arrives--> BESIEGING --battle resolved--> RETURNING --arrives home--> DISBANDED
 *     +------------------war over / route gone / destroyed--------------------------------^
 * </pre>
 *
 * Movement is time-based along a persisted road route (the global road graph): progress is a pure function of the
 * departure time and the travel time, so a restart neither moves nor rewinds an army. Physical soldiers near players
 * only represent it. State changes are package-private and made only by {@link CampaignService}.
 */
public final class ArmyRecord
{
    public enum Status
    {
        MARCHING, BESIEGING, RETURNING, DISBANDED;

        public boolean terminal() { return this == DISBANDED; }
    }

    private final UUID id;
    private final UUID warId;
    private final UUID factionId;
    private final UUID originSettlementId;
    private final UUID targetSettlementId;
    private final ResourceLocation dimension;
    private final List<UUID> routeRoads;
    private final List<UUID> routeSettlements;
    private final int detached;
    private final long raisedAt;
    private int strength;
    private Status status = Status.MARCHING;
    private final long fullTravelTicks;
    private long legStartedAt;
    private long legTravelTicks;
    private double legStartProgress;
    private boolean outbound = true;
    private UUID battleId;
    private int skirmishLosses;
    private long disbandedAt = -1L;
    private int returned;

    ArmyRecord(final UUID id, final UUID warId, final UUID factionId, final UUID originSettlementId, final UUID targetSettlementId,
        final ResourceLocation dimension, final List<UUID> routeRoads, final List<UUID> routeSettlements, final int strength,
        final long raisedAt, final long travelTicks)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.warId = Objects.requireNonNull(warId, "warId");
        this.factionId = Objects.requireNonNull(factionId, "factionId");
        this.originSettlementId = Objects.requireNonNull(originSettlementId, "originSettlementId");
        this.targetSettlementId = Objects.requireNonNull(targetSettlementId, "targetSettlementId");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (routeRoads.isEmpty() || routeSettlements.size() != routeRoads.size() + 1) throw new IllegalArgumentException("Invalid army route");
        this.routeRoads = List.copyOf(routeRoads);
        this.routeSettlements = List.copyOf(routeSettlements);
        if (strength < 1) throw new IllegalArgumentException("An army needs soldiers");
        this.detached = strength;
        this.strength = strength;
        this.raisedAt = raisedAt;
        this.fullTravelTicks = Math.max(1L, travelTicks);
        this.legStartedAt = raisedAt;
        this.legTravelTicks = this.fullTravelTicks;
    }

    public UUID id() { return id; }
    public UUID warId() { return warId; }
    public UUID factionId() { return factionId; }
    public UUID originSettlementId() { return originSettlementId; }
    public UUID targetSettlementId() { return targetSettlementId; }
    public ResourceLocation dimension() { return dimension; }
    /** Roads from the origin to the target, in marching order. */
    public List<UUID> routeRoads() { return routeRoads; }
    public List<UUID> routeSettlements() { return routeSettlements; }
    /** Soldiers that left the garrison (the return transaction gives back at most this many). */
    public int detached() { return detached; }
    public int strength() { return strength; }
    public long raisedAt() { return raisedAt; }
    public Status status() { return status; }
    public boolean open() { return !status.terminal(); }
    public boolean outbound() { return outbound; }
    public UUID battleId() { return battleId; }
    public int skirmishLosses() { return skirmishLosses; }
    public long disbandedAt() { return disbandedAt; }
    public int returned() { return returned; }
    public long legStartedAt() { return legStartedAt; }
    public long legTravelTicks() { return legTravelTicks; }
    /** Progress at the start of the current leg (the last point its squad held it at, while observed). */
    public double heldProgress() { return legStartProgress; }
    /** Travel time of the whole route (fixed when the army was raised). */
    public long fullTravelTicks() { return fullTravelTicks; }

    /**
     * Progress along the outbound route (0 = origin, 1 = target) at a time. Marching moves it forward, returning moves it
     * back towards 0; a besieging army stands at 1.
     */
    public double progressAt(final long gameTime)
    {
        final double moved = Math.max(0.0D, Math.min(1.0D, (gameTime - legStartedAt) / (double) Math.max(1L, legTravelTicks)));
        return switch (status)
        {
            case MARCHING -> Math.min(1.0D, legStartProgress + moved * (1.0D - legStartProgress));
            case BESIEGING -> 1.0D;
            case RETURNING -> Math.max(0.0D, legStartProgress - moved * legStartProgress);
            case DISBANDED -> outbound ? legStartProgress : 0.0D;
        };
    }

    public boolean arrivedAt(final long gameTime)
    {
        return (status == Status.MARCHING || status == Status.RETURNING) && gameTime - legStartedAt >= legTravelTicks;
    }

    // ------------------------------------------------------------------------------------------------ transitions

    void besiege(final UUID battle, final long gameTime)
    {
        if (status != Status.MARCHING) throw new IllegalStateException("Army " + id + " is " + status);
        status = Status.BESIEGING;
        battleId = Objects.requireNonNull(battle, "battle");
        legStartProgress = 1.0D;
        legStartedAt = gameTime;
    }

    /** Turns back from where it stands now; the way home takes the same time per block as the way out. */
    void returnHome(final long gameTime)
    {
        if (status == Status.RETURNING || status.terminal()) return;
        final double at = progressAt(gameTime);
        status = Status.RETURNING;
        outbound = false;
        legStartProgress = at;
        legStartedAt = gameTime;
        legTravelTicks = Math.max(1L, (long) Math.ceil(fullTravelTicks * at));
    }

    /**
     * The army follows its physical squad (Phase 10 presentation): progress moves to where the squad's leader is, never
     * back past the last such point and never ahead of the army's own schedule (a squad can only slow its army down);
     * the remaining leg keeps the army's pace from there.
     */
    void hold(final double progress, final long gameTime)
    {
        final double scheduled = progressAt(gameTime);
        if (status == Status.MARCHING)
        {
            final double at = Math.max(legStartProgress, Math.min(scheduled, progress));
            legStartProgress = at;
            legStartedAt = gameTime;
            legTravelTicks = Math.max(1L, (long) Math.ceil(fullTravelTicks * (1.0D - at)));
        }
        else if (status == Status.RETURNING)
        {
            final double at = Math.min(legStartProgress, Math.max(scheduled, progress));
            legStartProgress = at;
            legStartedAt = gameTime;
            legTravelTicks = Math.max(1L, (long) Math.ceil(fullTravelTicks * at));
        }
    }

    /** Soldiers lost in a battle or a skirmish (already bounded by the caller); returns those actually lost. */
    int lose(final int losses)
    {
        final int lost = Math.max(0, Math.min(strength, losses));
        strength -= lost;
        return lost;
    }

    int skirmishLoss()
    {
        final int lost = lose(1);
        skirmishLosses += lost;
        return lost;
    }

    void battleOver()
    {
        battleId = null;
    }

    void disband(final int survivors, final long gameTime)
    {
        if (status.terminal()) throw new IllegalStateException("Army " + id + " is already disbanded");
        returned = Math.max(0, Math.min(detached, survivors));
        status = Status.DISBANDED;
        disbandedAt = gameTime;
    }

    // ------------------------------------------------------------------------------------------------ persistence

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("war", warId);
        tag.putUUID("faction", factionId);
        tag.putUUID("origin", originSettlementId);
        tag.putUUID("target", targetSettlementId);
        tag.putString("dimension", dimension.toString());
        tag.put("roads", uuids(routeRoads));
        tag.put("settlements", uuids(routeSettlements));
        tag.putInt("detached", detached);
        tag.putInt("strength", strength);
        tag.putLong("raisedAt", raisedAt);
        tag.putString("status", status.name());
        tag.putLong("legStartedAt", legStartedAt);
        tag.putLong("legTravelTicks", legTravelTicks);
        tag.putLong("fullTravelTicks", fullTravelTicks);
        tag.putDouble("legStartProgress", legStartProgress);
        tag.putBoolean("outbound", outbound);
        if (battleId != null) tag.putUUID("battle", battleId);
        tag.putInt("skirmishLosses", skirmishLosses);
        tag.putLong("disbandedAt", disbandedAt);
        tag.putInt("returned", returned);
        return tag;
    }

    static ArmyRecord load(final CompoundTag tag)
    {
        final ArmyRecord army = new ArmyRecord(tag.getUUID("id"), tag.getUUID("war"), tag.getUUID("faction"), tag.getUUID("origin"),
            tag.getUUID("target"), ResourceLocation.parse(tag.getString("dimension")), readUuids(tag.getList("roads", Tag.TAG_COMPOUND)),
            readUuids(tag.getList("settlements", Tag.TAG_COMPOUND)), Math.max(1, tag.getInt("detached")), tag.getLong("raisedAt"),
            Math.max(1L, tag.contains("fullTravelTicks") ? tag.getLong("fullTravelTicks") : tag.getLong("legTravelTicks")));
        army.legTravelTicks = Math.max(1L, tag.getLong("legTravelTicks"));
        army.strength = Math.max(0, Math.min(army.detached, tag.getInt("strength")));
        army.status = Status.valueOf(tag.getString("status"));
        army.legStartedAt = tag.getLong("legStartedAt");
        final double progress = tag.getDouble("legStartProgress");
        army.legStartProgress = Double.isFinite(progress) ? Math.max(0.0D, Math.min(1.0D, progress)) : 0.0D;
        army.outbound = tag.getBoolean("outbound");
        army.battleId = tag.hasUUID("battle") ? tag.getUUID("battle") : null;
        army.skirmishLosses = Math.max(0, tag.getInt("skirmishLosses"));
        army.disbandedAt = tag.getLong("disbandedAt");
        army.returned = Math.max(0, tag.getInt("returned"));
        return army;
    }

    private static ListTag uuids(final List<UUID> values)
    {
        final ListTag list = new ListTag();
        values.forEach(value -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", value);
            list.add(entry);
        });
        return list;
    }

    private static List<UUID> readUuids(final ListTag list)
    {
        final List<UUID> values = new ArrayList<>();
        list.forEach(value -> values.add(((CompoundTag) value).getUUID("id")));
        return values;
    }
}
