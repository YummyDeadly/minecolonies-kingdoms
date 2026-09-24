package com.minecolonies.kingdoms.contract;

import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.diplomacy.ReputationTier;
import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * One contract record. The objective (resource, amount, rewards, evidence of the need) is frozen when the offer is
 * posted, and the acceptance terms (holder, deadline, agreed and reserved reward) when it is accepted; later economy
 * changes never alter them. State changes are package-private and made only by {@link ContractService}.
 */
public final class Contract
{
    public enum CloseReason { COMPLETED, DEADLINE_MISSED, OFFER_EXPIRED, PLAYER_ABANDONED, SETTLEMENT_REMOVED, OBJECTIVE_GONE, ADMIN }

    /**
     * What the contract asks for. DELIVERY: hand over {@code amount} units of {@code resource}. ESCORT_CARAVAN: defeat
     * the bandit encounter ({@code targetEncounter}) that threatens the settlement's caravan ({@code targetShipment},
     * carrying {@code amount} of {@code resource}). CLEAR_BANDITS: defeat the roadblock encounter of {@code amount}
     * bandits. Bandit contracts complete only through the encounter's own resolution (Phase 8).
     */
    public enum Kind { DELIVERY, ESCORT_CARAVAN, CLEAR_BANDITS }

    /** Immutable objective snapshot taken when the offer was posted. */
    public record Objective(Kind kind, EconomicResource resource, long amount, NeedSeverity severity, double needCurrent,
        double needTarget, int baseReward, int reputationReward, UUID targetEncounter, UUID targetShipment, BlockPos targetPosition)
    {
        public Objective
        {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(severity, "severity");
            if (amount <= 0L || baseReward < 1 || reputationReward < 0) throw new IllegalArgumentException("Invalid contract objective");
            switch (kind)
            {
                case DELIVERY -> Objects.requireNonNull(resource, "resource");
                case ESCORT_CARAVAN ->
                {
                    Objects.requireNonNull(resource, "resource");
                    Objects.requireNonNull(targetEncounter, "targetEncounter");
                    Objects.requireNonNull(targetShipment, "targetShipment");
                }
                case CLEAR_BANDITS -> Objects.requireNonNull(targetEncounter, "targetEncounter");
            }
            targetPosition = targetPosition == null ? null : targetPosition.immutable();
        }

        public static Objective delivery(final EconomicResource resource, final long amount, final NeedSeverity severity,
            final double needCurrent, final double needTarget, final int baseReward, final int reputationReward)
        {
            return new Objective(Kind.DELIVERY, resource, amount, severity, needCurrent, needTarget, baseReward, reputationReward,
                null, null, null);
        }

        public boolean security() { return kind != Kind.DELIVERY; }
    }

    private final UUID id;
    private final int sequence;
    private final UUID settlementId;
    private final UUID factionId;
    private final Objective objective;
    private final long offeredAt;
    private final long offerExpiresAt;
    private ContractStatus status = ContractStatus.OFFERED;
    private UUID holder;
    private long acceptedAt = -1L;
    private long deadline = -1L;
    private ReputationTier tierAtAcceptance;
    private int agreedReward;
    private int reservedReward;
    private long delivered;
    private int deliveries;
    private long closedAt = -1L;
    private CloseReason closeReason;
    private boolean reputationApplied;
    private boolean rewardIssued;
    private long rewardIssuedAt = -1L;

    public Contract(final UUID id, final int sequence, final UUID settlementId, final UUID factionId, final Objective objective,
        final long offeredAt, final long offerExpiresAt)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.sequence = sequence;
        this.settlementId = Objects.requireNonNull(settlementId, "settlementId");
        this.factionId = Objects.requireNonNull(factionId, "factionId");
        this.objective = Objects.requireNonNull(objective, "objective");
        if (offerExpiresAt <= offeredAt) throw new IllegalArgumentException("Offer must expire after it is posted");
        this.offeredAt = offeredAt;
        this.offerExpiresAt = offerExpiresAt;
    }

    public UUID id() { return id; }
    public int sequence() { return sequence; }
    public UUID settlementId() { return settlementId; }
    public UUID factionId() { return factionId; }
    public Objective objective() { return objective; }
    public EconomicResource resource() { return objective.resource(); }
    public long amount() { return objective.amount(); }
    public long offeredAt() { return offeredAt; }
    public long offerExpiresAt() { return offerExpiresAt; }
    public ContractStatus status() { return status; }
    public UUID holder() { return holder; }
    public long acceptedAt() { return acceptedAt; }
    public long deadline() { return deadline; }
    public ReputationTier tierAtAcceptance() { return tierAtAcceptance; }
    public int agreedReward() { return agreedReward; }
    public int reservedReward() { return reservedReward; }
    public long delivered() { return delivered; }
    public int deliveries() { return deliveries; }
    public long remaining() { return Math.max(0L, objective.amount() - delivered); }
    public Kind kind() { return objective.kind(); }
    public long closedAt() { return closedAt; }
    public CloseReason closeReason() { return closeReason; }
    public boolean reputationApplied() { return reputationApplied; }
    public boolean rewardIssued() { return rewardIssued; }
    public long rewardIssuedAt() { return rewardIssuedAt; }
    /** The reward shown to players: agreed once accepted, the base reward while offered. */
    public int reward() { return status == ContractStatus.OFFERED ? objective.baseReward() : agreedReward; }
    public boolean rewardPending() { return status == ContractStatus.COMPLETED && !rewardIssued; }

    // ------------------------------------------------------------------------------------------------ transitions

    void accept(final UUID player, final long gameTime, final long duration, final ReputationTier tier, final int reserved)
    {
        require(ContractStatus.OFFERED);
        if (reserved < objective.baseReward()) throw new IllegalArgumentException("Reservation below the base reward");
        holder = Objects.requireNonNull(player, "player");
        acceptedAt = gameTime;
        deadline = gameTime + duration;
        tierAtAcceptance = Objects.requireNonNull(tier, "tier");
        agreedReward = reserved;
        reservedReward = reserved;
        status = ContractStatus.ACCEPTED;
    }

    /** Credits up to the remaining amount; returns the credited units. */
    long credit(final long units)
    {
        require(ContractStatus.ACCEPTED);
        final long credited = Math.max(0L, Math.min(units, remaining()));
        delivered += credited;
        deliveries++;
        return credited;
    }

    /** A security objective was achieved (its encounter was won by the holder): nothing remains. */
    void fulfil()
    {
        require(ContractStatus.ACCEPTED);
        if (!objective.security()) throw new IllegalStateException("Deliveries are fulfilled by handing over goods");
        delivered = objective.amount();
        deliveries++;
    }

    /** Undoes a {@link #credit} whose surrounding transaction failed. */
    void uncredit(final long credited)
    {
        require(ContractStatus.ACCEPTED);
        delivered = Math.max(0L, delivered - credited);
        deliveries = Math.max(0, deliveries - 1);
    }

    void close(final ContractStatus terminal, final CloseReason reason, final long gameTime)
    {
        if (!terminal.terminal()) throw new IllegalArgumentException("Not a terminal status: " + terminal);
        if (status.terminal()) throw new IllegalStateException("Contract " + id + " is already " + status);
        if (terminal == ContractStatus.COMPLETED && remaining() > 0L) throw new IllegalStateException("Contract is not fulfilled");
        if ((terminal == ContractStatus.COMPLETED || terminal == ContractStatus.FAILED) && status != ContractStatus.ACCEPTED)
            throw new IllegalStateException("Only an accepted contract can complete or fail");
        if (terminal == ContractStatus.EXPIRED && status != ContractStatus.OFFERED)
            throw new IllegalStateException("Only an offer can expire");
        status = terminal;
        closeReason = Objects.requireNonNull(reason, "reason");
        closedAt = gameTime;
    }

    /** Releases the reservation (on payout or refund) and returns the released amount. */
    int releaseReservation()
    {
        final int released = reservedReward;
        reservedReward = 0;
        return released;
    }

    void markReputationApplied() { reputationApplied = true; }

    void markRewardIssued(final long gameTime)
    {
        if (rewardIssued) throw new IllegalStateException("Reward of contract " + id + " was already issued");
        rewardIssued = true;
        rewardIssuedAt = gameTime;
    }

    private void require(final ContractStatus expected)
    {
        if (status != expected) throw new IllegalStateException("Contract " + id + " is " + status + ", expected " + expected);
    }

    // ------------------------------------------------------------------------------------------------ persistence

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putInt("sequence", sequence);
        tag.putUUID("settlement", settlementId);
        tag.putUUID("faction", factionId);
        tag.putString("kind", objective.kind().name());
        if (objective.resource() != null) tag.putString("resource", objective.resource().name());
        if (objective.targetEncounter() != null) tag.putUUID("targetEncounter", objective.targetEncounter());
        if (objective.targetShipment() != null) tag.putUUID("targetShipment", objective.targetShipment());
        if (objective.targetPosition() != null) tag.putLong("targetPosition", objective.targetPosition().asLong());
        tag.putLong("amount", objective.amount());
        tag.putString("severity", objective.severity().name());
        tag.putDouble("needCurrent", objective.needCurrent());
        tag.putDouble("needTarget", objective.needTarget());
        tag.putInt("baseReward", objective.baseReward());
        tag.putInt("reputationReward", objective.reputationReward());
        tag.putLong("offeredAt", offeredAt);
        tag.putLong("offerExpiresAt", offerExpiresAt);
        tag.putString("status", status.name());
        if (holder != null) tag.putUUID("holder", holder);
        tag.putLong("acceptedAt", acceptedAt);
        tag.putLong("deadline", deadline);
        if (tierAtAcceptance != null) tag.putString("tier", tierAtAcceptance.name());
        tag.putInt("agreedReward", agreedReward);
        tag.putInt("reservedReward", reservedReward);
        tag.putLong("delivered", delivered);
        tag.putInt("deliveries", deliveries);
        tag.putLong("closedAt", closedAt);
        if (closeReason != null) tag.putString("closeReason", closeReason.name());
        tag.putBoolean("reputationApplied", reputationApplied);
        tag.putBoolean("rewardIssued", rewardIssued);
        tag.putLong("rewardIssuedAt", rewardIssuedAt);
        return tag;
    }

    public static Contract load(final CompoundTag tag)
    {
        final Objective objective = new Objective(tag.contains("kind") ? Kind.valueOf(tag.getString("kind")) : Kind.DELIVERY,
            tag.contains("resource") ? EconomicResource.valueOf(tag.getString("resource")) : null, tag.getLong("amount"),
            NeedSeverity.valueOf(tag.getString("severity")), tag.getDouble("needCurrent"), tag.getDouble("needTarget"),
            tag.getInt("baseReward"), tag.getInt("reputationReward"),
            tag.hasUUID("targetEncounter") ? tag.getUUID("targetEncounter") : null,
            tag.hasUUID("targetShipment") ? tag.getUUID("targetShipment") : null,
            tag.contains("targetPosition") ? net.minecraft.core.BlockPos.of(tag.getLong("targetPosition")) : null);
        final Contract contract = new Contract(tag.getUUID("id"), tag.getInt("sequence"), tag.getUUID("settlement"),
            tag.getUUID("faction"), objective, tag.getLong("offeredAt"), tag.getLong("offerExpiresAt"));
        contract.status = ContractStatus.valueOf(tag.getString("status"));
        contract.holder = tag.hasUUID("holder") ? tag.getUUID("holder") : null;
        contract.acceptedAt = tag.getLong("acceptedAt");
        contract.deadline = tag.getLong("deadline");
        contract.tierAtAcceptance = tag.contains("tier") ? ReputationTier.valueOf(tag.getString("tier")) : null;
        contract.agreedReward = Math.max(0, tag.getInt("agreedReward"));
        contract.reservedReward = Math.max(0, tag.getInt("reservedReward"));
        contract.delivered = Math.max(0L, Math.min(objective.amount(), tag.getLong("delivered")));
        contract.deliveries = Math.max(0, tag.getInt("deliveries"));
        contract.closedAt = tag.getLong("closedAt");
        contract.closeReason = tag.contains("closeReason") ? CloseReason.valueOf(tag.getString("closeReason")) : null;
        contract.reputationApplied = tag.getBoolean("reputationApplied");
        contract.rewardIssued = tag.getBoolean("rewardIssued");
        contract.rewardIssuedAt = tag.getLong("rewardIssuedAt");
        final boolean accepted = contract.status == ContractStatus.ACCEPTED || contract.status == ContractStatus.COMPLETED
            || contract.status == ContractStatus.FAILED;
        if (accepted && (contract.holder == null || contract.tierAtAcceptance == null))
            throw new IllegalArgumentException("Accepted contract " + contract.id + " without holder or terms");
        if (contract.status.terminal() && contract.closeReason == null)
            throw new IllegalArgumentException("Closed contract " + contract.id + " without reason");
        return contract;
    }
}
