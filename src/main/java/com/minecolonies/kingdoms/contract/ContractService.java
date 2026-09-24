package com.minecolonies.kingdoms.contract;

import com.minecolonies.kingdoms.colony.ColonyKind;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.diplomacy.ReputationRegistry;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.diplomacy.ReputationTier;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only place that changes contract state, the treasury reservations, and contract-driven reputation.
 *
 * <p>Money: the faction treasury is the free balance. Offers reserve nothing, but are posted only while the balance
 * covers all of the settlement's offers. Acceptance reserves the agreed reward (never more than the balance);
 * completion pays the reservation out once; failure and cancellation return it.
 *
 * <p>Delivery is one transaction: validate the contract, plan the items, remove them all-or-nothing, deposit into the
 * settlement's stockpile, credit the contract (and complete it), then apply reputation and issue the reward, each
 * guarded by a persisted flag. A failure while depositing/crediting rolls back the stockpile and the contract and
 * returns the items; a failure while paying leaves the reward pending, to be issued once on the next action.
 */
public final class ContractService
{
    private static final EconomyManager ECONOMY = new EconomyManager();

    private ContractService() {}

    public enum Refusal
    {
        NOT_OFFERED("That offer is no longer available."),
        EXPIRED("That contract has expired."),
        LIMIT_REACHED("You already have as many contracts as you can handle."),
        HOSTILE("They refuse to deal with you."),
        COUNCIL_CANNOT_PAY("The council cannot afford this contract right now."),
        SETTLEMENT_GONE("That settlement no longer posts contracts."),
        NOT_ACCEPTED("That contract is not active."),
        NOT_HOLDER("That is not your contract."),
        NOTHING_TO_DELIVER("You carry nothing they need for this contract."),
        NOT_A_DELIVERY("This contract is not fulfilled by handing over goods; deal with the bandits instead."),
        INVENTORY_CHANGED("Your inventory changed while handing over; nothing was taken. Try again.");

        private final String message;
        Refusal(final String message) { this.message = message; }
        public String message() { return message; }
    }

    /**
     * The player's inventory as a delivery sees it. Implementations must make {@link #remove} all-or-nothing and
     * {@link #give} either give everything or throw before giving anything.
     */
    public interface InventoryPort
    {
        /** Slots that hold items worth something for the resource (unit value per item). */
        List<ContractRules.Slot> slots(EconomicResource resource);

        /**
         * Removes exactly the planned items, or nothing: throws {@link IllegalStateException} if any slot no longer
         * matches. Returns an action that puts the removed items back.
         */
        Runnable remove(List<ContractRules.Removal> removals);

        void give(int emeralds);

        /**
         * Whether items given now actually reach the player. A player on the death screen still has an inventory, but
         * it is discarded on respawn (without keepInventory), so a reward given then would be lost while marked paid.
         */
        default boolean canReceive() { return true; }
    }

    public record Delivery(Optional<Refusal> refusal, long units, long credited, boolean completed, int payout,
        ReputationService.Result reputation)
    {
        static Delivery refused(final Refusal refusal, final ReputationService.Result reputation)
        {
            return new Delivery(Optional.of(refusal), 0L, 0L, false, 0, reputation);
        }
    }

    public record Closure(Contract contract, ReputationService.Result reputation) {}

    private static final Set<EconomicResource> CONTRACTABLE = EnumSet.of(EconomicResource.FOOD, EconomicResource.WOOD,
        EconomicResource.STONE, EconomicResource.IRON);

    // ------------------------------------------------------------------------------------------------ generation

    public static Optional<EconomicResource> resourceOf(final ColonyNeed need)
    {
        return switch (need.type())
        {
            case FOOD_SHORTAGE -> Optional.of(EconomicResource.FOOD);
            case WOOD_SHORTAGE -> Optional.of(EconomicResource.WOOD);
            case STONE_SHORTAGE -> Optional.of(EconomicResource.STONE);
            case IRON_SHORTAGE -> Optional.of(EconomicResource.IRON);
            default -> Optional.empty();
        };
    }

    public static UUID contractId(final UUID settlementId, final int sequence)
    {
        return UUID.nameUUIDFromBytes(("kingdoms-contract:" + settlementId + ':' + sequence).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Expires overdue offers and, at most once per {@code offerRefreshTicks} (unless forced), posts offers for the
     * settlement's resource needs of severity MEDIUM or worse, most severe first, subject to: one open contract per
     * resource, at most {@code maxOpenPerSettlement} open contracts, the per-resource cooldown after a contract closed,
     * and the treasury covering all posted offers. Only {@code NPC_ABSTRACT} settlements post contracts.
     */
    public static List<Contract> refresh(final KingdomsSavedData data, final UUID settlementId, final long gameTime,
        final ContractSettings settings, final boolean force)
    {
        final ContractRegistry registry = data.contracts();
        final NPCColonyData colony = issuer(data, settlementId).orElse(null);
        final Faction faction = colony == null ? null : data.faction(colony.factionId()).orElse(null);
        if (colony == null || faction == null) return List.of();
        accrueTreasury(data, faction, gameTime);
        for (final Contract offer : registry.offers(settlementId))
            if (offer.offerExpiresAt() <= gameTime) expire(data, offer, gameTime, settings);
        if (!settings.enabled() || (!force && gameTime < registry.nextOfferAt(settlementId))) return registry.offers(settlementId);

        final List<Contract> open = registry.open(settlementId).stream().filter(value -> value.kind() == Contract.Kind.DELIVERY).toList();
        int openCount = open.size();
        final Set<EconomicResource> taken = new HashSet<>();
        long offeredRewards = 0L;
        for (final Contract contract : open)
        {
            taken.add(contract.resource());
            if (contract.status() == ContractStatus.OFFERED) offeredRewards += contract.objective().baseReward();
        }
        final List<ColonyNeed> needs = new ArrayList<>(colony.needs());
        needs.sort(Comparator.comparing(ColonyNeed::severity).reversed().thenComparing(ColonyNeed::type));
        for (final ColonyNeed need : needs)
        {
            if (openCount >= settings.maxOpenPerSettlement()) break;
            final EconomicResource resource = resourceOf(need).orElse(null);
            if (resource == null || !CONTRACTABLE.contains(resource) || need.severity().compareTo(NeedSeverity.MEDIUM) < 0) continue;
            if (taken.contains(resource) || registry.resourceCooldownUntil(settlementId, resource) > gameTime) continue;
            final long amount = ContractRules.amount(resource, need.target() - need.current());
            final int reward = ContractRules.reward(resource, amount, need.severity());
            if (faction.treasury() < offeredRewards + reward) continue;
            final int sequence = registry.nextSequence(settlementId);
            registry.put(new Contract(contractId(settlementId, sequence), sequence, settlementId, faction.id(),
                Contract.Objective.delivery(resource, amount, need.severity(), need.current(), need.target(), reward,
                    ContractRules.reputationReward(need.severity())), gameTime, gameTime + settings.offerLifetimeTicks()));
            taken.add(resource);
            openCount++;
            offeredRewards += reward;
        }
        registry.setNextOfferAt(settlementId, gameTime + settings.offerRefreshTicks());
        data.markChanged();
        return registry.offers(settlementId);
    }

    /**
     * Taxes: {@code 0.2} emerald per citizen per day across the faction's settlements, up to {@code 64 + 2 x population}.
     * A faction seen for the first time starts with half of that cap. Refunds may exceed the cap; nothing is lost.
     */
    public static void accrueTreasury(final KingdomsSavedData data, final Faction faction, final long gameTime)
    {
        final ContractRegistry registry = data.contracts();
        int population = 0;
        for (final UUID settlement : faction.settlementIds())
            population += data.colony(settlement).map(NPCColonyData::population).orElse(0);
        final long cap = ContractRules.treasuryCap(population);
        final Optional<Long> accruedAt = registry.treasuryAccruedAt(faction.id());
        if (accruedAt.isEmpty() || accruedAt.get() > gameTime)
        {
            if (accruedAt.isEmpty()) faction.setTreasury(Math.max(faction.treasury(), cap / 2));
            registry.setTreasuryAccruedAt(faction.id(), gameTime);
            data.markChanged();
            return;
        }
        final long income = ContractRules.treasuryIncome(population, gameTime - accruedAt.get());
        if (income <= 0L) return;
        if (faction.treasury() + income >= cap)
        {
            faction.setTreasury(Math.max(faction.treasury(), cap));
            registry.setTreasuryAccruedAt(faction.id(), gameTime);
        }
        else
        {
            faction.setTreasury(faction.treasury() + income);
            registry.setTreasuryAccruedAt(faction.id(), accruedAt.get() + ContractRules.ticksFor(population, income));
        }
        data.markChanged();
    }

    // ------------------------------------------------------------------------------------------------ acceptance

    /** Accepts an offer and reserves the agreed reward (base reward times the player's tier, capped by the balance). */
    public static Optional<Refusal> accept(final KingdomsSavedData data, final UUID playerId, final Contract contract,
        final long gameTime, final ContractSettings settings)
    {
        if (contract.status() != ContractStatus.OFFERED) return Optional.of(Refusal.NOT_OFFERED);
        if (contract.offerExpiresAt() <= gameTime)
        {
            expire(data, contract, gameTime, settings);
            return Optional.of(Refusal.EXPIRED);
        }
        final Faction faction = data.faction(contract.factionId()).orElse(null);
        if (faction == null || issuer(data, contract.settlementId()).isEmpty())
        {
            cancel(data, contract, Contract.CloseReason.SETTLEMENT_REMOVED, gameTime);
            return Optional.of(Refusal.SETTLEMENT_GONE);
        }
        if (data.contracts().active(playerId).size() >= settings.maxActivePerPlayer()) return Optional.of(Refusal.LIMIT_REACHED);
        final ReputationTier tier = ReputationService.tier(data, playerId, contract.factionId());
        if (!tier.contractsAllowed()) return Optional.of(Refusal.HOSTILE);
        accrueTreasury(data, faction, gameTime);
        final int agreed = ContractRules.agreedReward(contract.objective().baseReward(), tier.rewardMultiplier());
        final int reserve = (int) Math.min(agreed, faction.treasury());
        if (reserve < contract.objective().baseReward()) return Optional.of(Refusal.COUNCIL_CANNOT_PAY);
        faction.setTreasury(faction.treasury() - reserve);
        contract.accept(playerId, gameTime, settings.contractDurationTicks(), tier, reserve);
        data.markChanged();
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------ delivery

    public static Delivery deliver(final KingdomsSavedData data, final UUID playerId, final Contract contract,
        final InventoryPort inventory, final long gameTime, final ContractSettings settings)
    {
        // 1. validate the contract
        if (contract.status() != ContractStatus.ACCEPTED) return Delivery.refused(Refusal.NOT_ACCEPTED, ReputationService.Result.NONE);
        if (contract.kind() != Contract.Kind.DELIVERY) return Delivery.refused(Refusal.NOT_A_DELIVERY, ReputationService.Result.NONE);
        if (!playerId.equals(contract.holder())) return Delivery.refused(Refusal.NOT_HOLDER, ReputationService.Result.NONE);
        if (gameTime > contract.deadline()) return Delivery.refused(Refusal.EXPIRED, fail(data, contract, gameTime, settings));
        final NPCColonyData colony = issuer(data, contract.settlementId()).orElse(null);
        if (colony == null)
        {
            cancel(data, contract, Contract.CloseReason.SETTLEMENT_REMOVED, gameTime);
            return Delivery.refused(Refusal.SETTLEMENT_GONE, ReputationService.Result.NONE);
        }
        // 2. validate the items
        final ContractRules.Plan plan = ContractRules.plan(contract.remaining(), inventory.slots(contract.resource()));
        if (plan.units() <= 0L) return Delivery.refused(Refusal.NOTHING_TO_DELIVER, ReputationService.Result.NONE);
        // 3. remove them, all or nothing
        final Runnable restoreItems;
        try { restoreItems = inventory.remove(plan.removals()); }
        catch (IllegalStateException changed) { return Delivery.refused(Refusal.INVENTORY_CHANGED, ReputationService.Result.NONE); }
        // 4. deposit into the real stockpile, 5. credit (and complete) the contract; roll everything back on failure
        final long stockBefore = colony.economy().resource(contract.resource()).stockpile().amount();
        long credited = 0L;
        boolean creditApplied = false;
        try
        {
            ECONOMY.depositShipment(colony, contract.resource(), plan.units());
            credited = contract.credit(plan.units());
            creditApplied = true;
            if (contract.remaining() == 0L) contract.close(ContractStatus.COMPLETED, Contract.CloseReason.COMPLETED, gameTime);
        }
        catch (RuntimeException failure)
        {
            if (creditApplied && contract.status() == ContractStatus.ACCEPTED) contract.uncredit(credited);
            ECONOMY.setStockpile(colony, contract.resource(), stockBefore);
            restoreItems.run();
            throw failure;
        }
        data.markChanged();
        if (contract.status() != ContractStatus.COMPLETED)
            return new Delivery(Optional.empty(), plan.units(), credited, false, 0, ReputationService.Result.NONE);
        // 6. reputation and 7. reward, each exactly once
        data.contracts().setResourceCooldown(contract.settlementId(), contract.resource(), gameTime + settings.resourceCooldownTicks());
        final ReputationService.Result reputation = applyCompletionReputation(data, contract, gameTime);
        final int payout = issueReward(data, contract, inventory, gameTime);
        return new Delivery(Optional.empty(), plan.units(), credited, true, payout, reputation);
    }

    /** Issues rewards (and reputation) of completed contracts that could not be paid before; returns the emeralds paid. */
    public static int claimPendingRewards(final KingdomsSavedData data, final UUID playerId, final InventoryPort inventory,
        final long gameTime)
    {
        int paid = 0;
        for (final Contract contract : data.contracts().pendingRewards(playerId))
        {
            applyCompletionReputation(data, contract, gameTime);
            paid += issueReward(data, contract, inventory, gameTime);
        }
        return paid;
    }

    private static ReputationService.Result applyCompletionReputation(final KingdomsSavedData data, final Contract contract, final long gameTime)
    {
        if (contract.reputationApplied()) return ReputationService.Result.NONE;
        contract.markReputationApplied();
        data.markChanged();
        return ReputationService.adjust(data, contract.holder(), contract.factionId(), contract.objective().reputationReward(),
            ReputationRegistry.Cause.CONTRACT_COMPLETED, contract.id(), gameTime);
    }

    private static int issueReward(final KingdomsSavedData data, final Contract contract, final InventoryPort inventory, final long gameTime)
    {
        if (!contract.rewardPending() || !inventory.canReceive()) return 0; // stays pending, paid once on a later action
        final int amount = contract.reservedReward();
        if (amount > 0) inventory.give(amount); // throws before giving anything: the reward stays pending
        contract.releaseReservation();
        contract.markRewardIssued(gameTime);
        data.markChanged();
        return amount;
    }

    // ------------------------------------------------------------------------------------------------ treasury transfers (Phase 10)

    /**
     * Moves up to {@code amount} from the payer's free treasury (reservations for accepted contracts are already set
     * aside, so they are never touched) to the payee, in one step. Returns what was actually moved; nothing is created or
     * destroyed. The caller guards against repeating it (a persisted flag on the war or battle).
     */
    public static int transferTreasury(final KingdomsSavedData data, final UUID payerId, final UUID payeeId, final int amount)
    {
        if (amount <= 0 || payerId == null || payeeId == null || payerId.equals(payeeId)) return 0;
        final Faction payer = data.faction(payerId).orElse(null);
        final Faction payee = data.faction(payeeId).orElse(null);
        if (payer == null || payee == null) return 0;
        final int paid = (int) Math.max(0L, Math.min(amount, payer.treasury()));
        if (paid <= 0) return 0;
        payer.setTreasury(payer.treasury() - paid);
        payee.setTreasury(payee.treasury() + paid);
        data.markChanged();
        return paid;
    }

    // ------------------------------------------------------------------------------------------------ security (Phase 8)

    /**
     * At most this many open security (escort/clear) contracts per settlement, on top of the delivery cap. Two, so a
     * long-lived camp contract never keeps a settlement from asking for an escort.
     */
    public static final int MAX_SECURITY_PER_SETTLEMENT = 2;

    /**
     * Posts a security offer for a real bandit encounter, if the settlement posts contracts, has no other open
     * security contract, nobody has an open contract for that encounter yet, and the treasury can fund all of its
     * offers. Nothing is reserved until a player accepts.
     */
    public static Optional<Contract> postSecurityOffer(final KingdomsSavedData data, final UUID settlementId,
        final Contract.Objective objective, final long gameTime, final long offerExpiresAt, final ContractSettings settings)
    {
        if (!settings.enabled() || !objective.security() || offerExpiresAt <= gameTime) return Optional.empty();
        final ContractRegistry registry = data.contracts();
        final NPCColonyData colony = issuer(data, settlementId).orElse(null);
        final Faction faction = colony == null ? null : data.faction(colony.factionId()).orElse(null);
        if (faction == null || !registry.targeting(objective.targetEncounter()).isEmpty()) return Optional.empty();
        final List<Contract> open = registry.open(settlementId);
        if (open.stream().filter(value -> value.objective().security()).count() >= MAX_SECURITY_PER_SETTLEMENT) return Optional.empty();
        accrueTreasury(data, faction, gameTime);
        final long offered = open.stream().filter(value -> value.status() == ContractStatus.OFFERED)
            .mapToLong(value -> value.objective().baseReward()).sum();
        if (faction.treasury() < offered + objective.baseReward()) return Optional.empty();
        final int sequence = registry.nextSequence(settlementId);
        final Contract contract = new Contract(contractId(settlementId, sequence), sequence, settlementId, faction.id(), objective,
            gameTime, offerExpiresAt);
        registry.put(contract);
        data.markChanged();
        return Optional.of(contract);
    }

    /**
     * Closes every open contract that targets a resolved encounter (or battle, Phase 10), exactly once. An accepted
     * contract completes when the players won and its holder fought ({@code defenders}); an escort or a defence fails if
     * the bandits (the besiegers) won, and a clear contract
     * fails if its roadblock or camp outlived its lifetime ({@code clearMissed}); anything else is cancelled without
     * penalty (the objective no longer exists). Completed rewards stay pending until paid.
     */
    public static List<Closure> onEncounterResolved(final KingdomsSavedData data, final UUID encounterId, final boolean playersWon,
        final boolean banditsWon, final boolean clearMissed, final java.util.Collection<UUID> defenders, final long gameTime,
        final ContractSettings settings)
    {
        final List<Closure> closures = new ArrayList<>();
        for (final Contract contract : data.contracts().targeting(encounterId))
        {
            if (contract.status() == ContractStatus.OFFERED)
            {
                cancel(data, contract, Contract.CloseReason.OBJECTIVE_GONE, gameTime);
                continue;
            }
            if (playersWon && defenders.contains(contract.holder()))
            {
                contract.fulfil();
                contract.close(ContractStatus.COMPLETED, Contract.CloseReason.COMPLETED, gameTime);
                data.markChanged();
                closures.add(new Closure(contract, applyCompletionReputation(data, contract, gameTime)));
            }
            else if ((banditsWon && (contract.kind() == Contract.Kind.ESCORT_CARAVAN || contract.kind() == Contract.Kind.DEFEND_SETTLEMENT))
                || (clearMissed && (contract.kind() == Contract.Kind.CLEAR_BANDITS || contract.kind() == Contract.Kind.CLEAR_CAMP)))
                closures.add(new Closure(contract, fail(data, contract, gameTime, settings)));
            else
            {
                cancel(data, contract, Contract.CloseReason.OBJECTIVE_GONE, gameTime);
                closures.add(new Closure(contract, ReputationService.Result.NONE));
            }
        }
        return closures;
    }

    // ------------------------------------------------------------------------------------------------ closing

    public static Optional<Refusal> checkAbandon(final UUID playerId, final Contract contract)
    {
        if (contract.status() != ContractStatus.ACCEPTED) return Optional.of(Refusal.NOT_ACCEPTED);
        if (!playerId.equals(contract.holder())) return Optional.of(Refusal.NOT_HOLDER);
        return Optional.empty();
    }

    /** Player gives up an accepted contract: CANCELLED, reservation returned, reputation penalty. */
    public static Closure abandon(final KingdomsSavedData data, final UUID playerId, final Contract contract, final long gameTime,
        final ContractSettings settings)
    {
        checkAbandon(playerId, contract).ifPresent(refusal -> { throw new IllegalStateException(refusal.message()); });
        contract.close(ContractStatus.CANCELLED, Contract.CloseReason.PLAYER_ABANDONED, gameTime);
        refund(data, contract);
        if (contract.kind() == Contract.Kind.DELIVERY)
            data.contracts().setResourceCooldown(contract.settlementId(), contract.resource(), gameTime + settings.resourceCooldownTicks());
        contract.markReputationApplied();
        data.markChanged();
        return new Closure(contract, ReputationService.adjust(data, playerId, contract.factionId(), -settings.abandonPenalty(),
            ReputationRegistry.Cause.CONTRACT_CANCELLED, contract.id(), gameTime));
    }

    /** System or operator cancellation: no reputation change; any reservation is returned. */
    public static void cancel(final KingdomsSavedData data, final Contract contract, final Contract.CloseReason reason, final long gameTime)
    {
        contract.close(ContractStatus.CANCELLED, reason, gameTime);
        refund(data, contract);
        contract.markReputationApplied();
        data.markChanged();
    }

    /**
     * Fails overdue accepted contracts, expires overdue offers, cancels contracts of settlements that no longer post
     * contracts, and prunes history. Returns failures and cancellations of accepted contracts, for notifications.
     */
    public static List<Closure> sweep(final KingdomsSavedData data, final long gameTime, final ContractSettings settings)
    {
        final ContractRegistry registry = data.contracts();
        final List<Closure> closures = new ArrayList<>();
        for (final Contract contract : List.copyOf(registry.contracts()))
        {
            if (contract.status().terminal()) continue;
            final boolean accepted = contract.status() == ContractStatus.ACCEPTED;
            if (issuer(data, contract.settlementId()).isEmpty() || data.faction(contract.factionId()).isEmpty())
            {
                cancel(data, contract, Contract.CloseReason.SETTLEMENT_REMOVED, gameTime);
                if (accepted) closures.add(new Closure(contract, ReputationService.Result.NONE));
            }
            else if (contract.objective().security() && !targetOpen(data, contract.objective().targetEncounter()))
            {
                // defensive: its encounter or battle is gone without closing it (they always close them); never a penalty
                cancel(data, contract, Contract.CloseReason.OBJECTIVE_GONE, gameTime);
                if (accepted) closures.add(new Closure(contract, ReputationService.Result.NONE));
            }
            else if (!accepted && contract.offerExpiresAt() <= gameTime) expire(data, contract, gameTime, settings);
            // an accepted security contract is decided by its encounter, which always ends; the clock never fails it
            else if (accepted && gameTime > contract.deadline() && !contract.objective().security())
                closures.add(new Closure(contract, fail(data, contract, gameTime, settings)));
        }
        final Set<UUID> settlements = new HashSet<>();
        data.colonies().forEach(colony -> settlements.add(colony.id()));
        final Set<UUID> factions = new HashSet<>();
        data.factions().forEach(faction -> factions.add(faction.id()));
        if (registry.prune(gameTime, settings.historyRetentionTicks(), settings.maxHistory(), settlements, factions) > 0) data.markChanged();
        if (data.reputation().retainFactions(factions) > 0) data.markChanged();
        return closures;
    }

    private static ReputationService.Result fail(final KingdomsSavedData data, final Contract contract, final long gameTime,
        final ContractSettings settings)
    {
        contract.close(ContractStatus.FAILED, Contract.CloseReason.DEADLINE_MISSED, gameTime);
        refund(data, contract);
        if (contract.kind() == Contract.Kind.DELIVERY)
            data.contracts().setResourceCooldown(contract.settlementId(), contract.resource(), gameTime + settings.resourceCooldownTicks());
        contract.markReputationApplied();
        data.markChanged();
        return ReputationService.adjust(data, contract.holder(), contract.factionId(), -settings.failPenalty(),
            ReputationRegistry.Cause.CONTRACT_FAILED, contract.id(), gameTime);
    }

    private static void expire(final KingdomsSavedData data, final Contract contract, final long gameTime, final ContractSettings settings)
    {
        contract.close(ContractStatus.EXPIRED, Contract.CloseReason.OFFER_EXPIRED, gameTime);
        if (contract.kind() == Contract.Kind.DELIVERY)
            data.contracts().setResourceCooldown(contract.settlementId(), contract.resource(), gameTime + settings.unacceptedCooldownTicks());
        contract.markReputationApplied();
        data.markChanged();
    }

    private static void refund(final KingdomsSavedData data, final Contract contract)
    {
        final int released = contract.releaseReservation();
        if (released > 0) data.faction(contract.factionId()).ifPresent(faction -> faction.setTreasury(faction.treasury() + released));
    }

    /** The objective of a security contract: an open bandit encounter, or an open battle for DEFEND_SETTLEMENT (Phase 10). */
    private static boolean targetOpen(final KingdomsSavedData data, final UUID targetId)
    {
        if (targetId == null) return false;
        return data.bandits().encounter(targetId).map(com.minecolonies.kingdoms.bandit.BanditEncounter::open)
            .or(() -> data.war().battle(targetId).map(com.minecolonies.kingdoms.war.BattleRecord::open)).orElse(false);
    }

    /** The settlement's colony, if it still posts contracts. */
    private static Optional<NPCColonyData> issuer(final KingdomsSavedData data, final UUID settlementId)
    {
        return data.colony(settlementId).filter(colony -> colony.kind() == ColonyKind.NPC_ABSTRACT);
    }
}
