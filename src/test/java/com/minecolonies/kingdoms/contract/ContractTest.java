package com.minecolonies.kingdoms.contract;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.diplomacy.ReputationRegistry;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.diplomacy.ReputationTier;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContractTest
{
    private static final ContractSettings SETTINGS = ContractSettings.defaults();
    private static final UUID TOWN = UUID.nameUUIDFromBytes("town".getBytes());
    private static final UUID FACTION = UUID.nameUUIDFromBytes("town-council".getBytes());
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes());
    private static final UUID OTHER = UUID.nameUUIDFromBytes("other".getBytes());

    // ------------------------------------------------------------------------------------------------ pure rules

    @Test
    void termsAreBandedSteppedAndPricedBySeverity()
    {
        assertEquals(48, ContractRules.amount(EconomicResource.FOOD, 10), "band minimum");
        assertEquals(192, ContractRules.amount(EconomicResource.FOOD, 5000), "band maximum");
        assertEquals(96, ContractRules.amount(EconomicResource.FOOD, 195), "half the shortage, rounded to the step of 8");
        assertEquals(0, ContractRules.amount(EconomicResource.IRON, 21) % 2);
        assertEquals(4, ContractRules.reward(EconomicResource.FOOD, 96, NeedSeverity.MEDIUM));
        assertEquals(5, ContractRules.reward(EconomicResource.FOOD, 96, NeedSeverity.HIGH));
        assertEquals(6, ContractRules.reward(EconomicResource.FOOD, 96, NeedSeverity.CRITICAL));
        assertEquals(3, ContractRules.reward(EconomicResource.IRON, 10, NeedSeverity.MEDIUM));
        assertEquals(7, ContractRules.reputationReward(NeedSeverity.CRITICAL));
        assertEquals(20, ContractRules.hintItems(EconomicResource.FOOD, 96));
        assertFalse(ContractRules.contractable(EconomicResource.TOOLS));
        assertEquals(1, ContractRules.agreedReward(1, 0.8D));
        assertEquals(7, ContractRules.agreedReward(5, 1.4D));
    }

    @Test
    void deliveryPlansTakeTheLeastSurplus()
    {
        assertEquals(List.of(new ContractRules.Removal(0, 2), new ContractRules.Removal(3, 2)),
            ContractRules.plan(20, List.of(new ContractRules.Slot(0, 64, 1), new ContractRules.Slot(3, 2, 9))).removals());
        assertEquals(100, ContractRules.plan(96, List.of(new ContractRules.Slot(1, 64, 5))).units(), "surplus smaller than one item");
        assertEquals(35, ContractRules.plan(96, List.of(new ContractRules.Slot(2, 7, 5), new ContractRules.Slot(5, 0, 5))).units());
        assertEquals(10, ContractRules.plan(10, List.of(new ContractRules.Slot(0, 1, 8), new ContractRules.Slot(1, 5, 6),
            new ContractRules.Slot(2, 5, 1))).units());
        assertTrue(ContractRules.plan(10, List.of(new ContractRules.Slot(0, 5, 0))).removals().isEmpty());
        assertEquals(164, ContractRules.treasuryCap(50));
        assertEquals(10, ContractRules.treasuryIncome(50, ContractRules.DAY_TICKS));
        assertEquals(ContractRules.DAY_TICKS, ContractRules.ticksFor(50, 10));
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    private static KingdomsSavedData world(final int population, final ColonyNeed... needs)
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final Faction faction = new Faction(FACTION, "Town Council", FactionType.CITY_STATE);
        faction.setCapitalColonyId(TOWN);
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(TOWN, SettlementFixtures.OVERWORLD, new BlockPos(0, 64, 0),
            "Town", FACTION, 0L);
        colony.updatePopulation(population, population / 2, Math.min(2, population - population / 2));
        colony.replaceNeeds(List.of(needs));
        data.putFaction(faction);
        data.putColony(colony);
        return data;
    }

    private static ColonyNeed need(final NeedType type, final NeedSeverity severity, final double current, final double target)
    {
        return new ColonyNeed(type, severity, current, target, 0L);
    }

    private static Faction faction(final KingdomsSavedData data) { return data.faction(FACTION).orElseThrow(); }

    private static long reserved(final KingdomsSavedData data)
    {
        return data.contracts().contracts().stream().mapToLong(Contract::reservedReward).sum();
    }

    private static long stock(final KingdomsSavedData data, final EconomicResource resource)
    {
        return data.colony(TOWN).orElseThrow().economy().resource(resource).stockpile().amount();
    }

    /** In-memory inventory: each slot is {count, unitsPerItem}; records emeralds; optional injected failures. */
    private static final class FakeInventory implements ContractService.InventoryPort
    {
        final long[][] slots;
        int emeralds;
        boolean failRemove;
        boolean failGive;

        FakeInventory(final long[]... slots) { this.slots = slots; }

        long count(final int index) { return slots[index][0]; }

        @Override
        public List<ContractRules.Slot> slots(final EconomicResource resource)
        {
            final List<ContractRules.Slot> result = new ArrayList<>();
            for (int index = 0; index < slots.length; index++)
                if (slots[index][0] > 0) result.add(new ContractRules.Slot(index, (int) slots[index][0], slots[index][1]));
            return result;
        }

        @Override
        public Runnable remove(final List<ContractRules.Removal> removals)
        {
            if (failRemove) throw new IllegalStateException("slot changed");
            for (final ContractRules.Removal removal : removals)
                if (slots[removal.index()][0] < removal.count()) throw new IllegalStateException("slot changed");
            final long[] before = new long[slots.length];
            for (int index = 0; index < slots.length; index++) before[index] = slots[index][0];
            removals.forEach(removal -> slots[removal.index()][0] -= removal.count());
            return () -> { for (int index = 0; index < slots.length; index++) slots[index][0] = before[index]; };
        }

        @Override
        public void give(final int amount)
        {
            if (failGive) throw new IllegalStateException("cannot give");
            emeralds += amount;
        }
    }

    // ------------------------------------------------------------------------------------------------ generation

    @Test
    void generationIsBoundedDeduplicatedAndSnapshotsTheObjective()
    {
        final KingdomsSavedData data = world(50, need(NeedType.FOOD_SHORTAGE, NeedSeverity.CRITICAL, 0, 400),
            need(NeedType.IRON_SHORTAGE, NeedSeverity.HIGH, 10, 40), need(NeedType.WOOD_SHORTAGE, NeedSeverity.LOW, 90, 100),
            need(NeedType.HOUSING_SHORTAGE, NeedSeverity.CRITICAL, 0, 10), need(NeedType.STONE_SHORTAGE, NeedSeverity.MEDIUM, 10, 200));
        final List<Contract> offers = ContractService.refresh(data, TOWN, 1000L, SETTINGS, false);
        assertEquals(List.of(EconomicResource.FOOD, EconomicResource.IRON, EconomicResource.STONE),
            offers.stream().map(Contract::resource).toList(), "most severe first; LOW and non-resource needs ignored");
        assertEquals(ContractService.contractId(TOWN, 1), offers.getFirst().id(), "stable IDs from settlement and sequence");
        assertEquals(1, offers.getFirst().sequence());
        assertEquals(ContractRules.treasuryCap(50) / 2, faction(data).treasury(), "offers reserve nothing");
        assertEquals(0, reserved(data));
        assertEquals(400.0D, offers.getFirst().objective().needTarget(), "evidence of the need is kept");

        data.colony(TOWN).orElseThrow().replaceNeeds(List.of(need(NeedType.FOOD_SHORTAGE, NeedSeverity.MEDIUM, 390, 400)));
        assertEquals(offers, ContractService.refresh(data, TOWN, 1500L, SETTINGS, false), "nothing new before the interval");
        assertEquals(3, ContractService.refresh(data, TOWN, 5000L, SETTINGS, true).size(), "max open, one per resource");
        assertEquals(192, offers.getFirst().amount(), "a posted objective never changes with the economy");
        assertEquals(NeedSeverity.CRITICAL, offers.getFirst().objective().severity());

        final long expiry = offers.getFirst().offerExpiresAt();
        data.colony(TOWN).orElseThrow().replaceNeeds(List.of(need(NeedType.FOOD_SHORTAGE, NeedSeverity.CRITICAL, 0, 400)));
        assertTrue(ContractService.refresh(data, TOWN, expiry, SETTINGS, true).isEmpty(), "expired, and no equivalent repost");
        assertTrue(offers.stream().allMatch(value -> value.status() == ContractStatus.EXPIRED));
        assertTrue(ContractService.refresh(data, TOWN, expiry + SETTINGS.unacceptedCooldownTicks() - 1, SETTINGS, true).isEmpty(),
            "the unresolved need waits out the cooldown");
        final List<Contract> again = ContractService.refresh(data, TOWN, expiry + SETTINGS.unacceptedCooldownTicks(), SETTINGS, true);
        assertEquals(1, again.size(), "then it is offered again, with a new ID");
        assertEquals(ContractService.contractId(TOWN, 4), again.getFirst().id());
    }

    @Test
    void offersNeedAFundableTreasury()
    {
        final KingdomsSavedData poor = world(0, need(NeedType.FOOD_SHORTAGE, NeedSeverity.CRITICAL, 0, 400));
        poor.contracts().setTreasuryAccruedAt(FACTION, 0L); // already accrued: no starting grant, no taxes at population 0
        assertTrue(ContractService.refresh(poor, TOWN, 1000L, SETTINGS, false).isEmpty(), "an empty treasury posts nothing");
        final KingdomsSavedData tight = world(0, need(NeedType.FOOD_SHORTAGE, NeedSeverity.CRITICAL, 0, 400),
            need(NeedType.IRON_SHORTAGE, NeedSeverity.CRITICAL, 0, 400));
        tight.contracts().setTreasuryAccruedAt(FACTION, 0L);
        faction(tight).setTreasury(20);
        assertEquals(1, ContractService.refresh(tight, TOWN, 1000L, SETTINGS, false).size(),
            "12 + 12 would exceed 20: only the first is advertised");
    }

    // ------------------------------------------------------------------------------------------------ money

    @Test
    void acceptanceReservesAndClosingReturnsTheReservation()
    {
        final KingdomsSavedData data = world(80, need(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200),
            need(NeedType.WOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200), need(NeedType.IRON_SHORTAGE, NeedSeverity.HIGH, 0, 60));
        final List<Contract> offers = ContractService.refresh(data, TOWN, 1000L, SETTINGS, false);
        final long money = faction(data).treasury();
        ReputationService.set(data, OTHER, FACTION, -70, 0L);
        assertEquals(ContractService.Refusal.HOSTILE, ContractService.accept(data, OTHER, offers.get(0), 1100L, SETTINGS).orElseThrow());

        ReputationService.set(data, PLAYER, FACTION, 95, 0L);
        final Contract food = offers.get(0);
        assertTrue(ContractService.accept(data, PLAYER, food, 1100L, SETTINGS).isEmpty());
        assertEquals(ContractStatus.ACCEPTED, food.status());
        assertEquals(ReputationTier.REVERED, food.tierAtAcceptance());
        assertEquals(7, food.agreedReward(), "5 x 1.4");
        assertEquals(7, food.reservedReward());
        assertEquals(money, faction(data).treasury() + reserved(data), "reservation moves money, never creates it");

        final ContractSettings one = new ContractSettings(true, 3, 1, 48_000L, 10_000L, 2_400L, 12_000L, 24_000L, 96, 6, 4, 10,
            168_000L, 256);
        assertEquals(ContractService.Refusal.LIMIT_REACHED, ContractService.accept(data, PLAYER, offers.get(1), 1100L, one).orElseThrow());

        final List<ContractService.Closure> failed = ContractService.sweep(data, 1100L + SETTINGS.contractDurationTicks() + 1, SETTINGS);
        assertEquals(1, failed.size());
        assertEquals(ContractStatus.FAILED, food.status());
        assertEquals(Contract.CloseReason.DEADLINE_MISSED, food.closeReason());
        assertEquals(0, food.reservedReward());
        assertTrue(faction(data).treasury() + reserved(data) >= money, "failure returns the reservation (plus taxes)");
        assertEquals(95 - 6, ReputationService.standing(data, PLAYER, FACTION));

        final long now = 1100L + SETTINGS.contractDurationTicks() + 2;
        assertEquals(ContractStatus.EXPIRED, offers.get(1).status(), "unaccepted offers expired meanwhile");
        final KingdomsSavedData fresh = world(80, need(NeedType.WOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200));
        final Contract wood = ContractService.refresh(fresh, TOWN, now, SETTINGS, false).getFirst();
        final long freshMoney = faction(fresh).treasury();
        assertTrue(ContractService.accept(fresh, PLAYER, wood, now, SETTINGS).isEmpty());
        final ContractService.Closure abandoned = ContractService.abandon(fresh, PLAYER, wood, now + 10, SETTINGS);
        assertEquals(ContractStatus.CANCELLED, abandoned.contract().status());
        assertEquals(Contract.CloseReason.PLAYER_ABANDONED, abandoned.contract().closeReason());
        assertEquals(-4, ReputationService.standing(fresh, PLAYER, FACTION));
        assertEquals(freshMoney, faction(fresh).treasury(), "abandonment returns the reservation");
        assertEquals(1, data.reputation().record(PLAYER, FACTION).orElseThrow().failed());
        assertEquals(1, fresh.reputation().record(PLAYER, FACTION).orElseThrow().cancelled());

        final KingdomsSavedData broke = world(80, need(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200));
        final Contract offer = ContractService.refresh(broke, TOWN, 1000L, SETTINGS, false).getFirst();
        faction(broke).setTreasury(2);
        assertEquals(ContractService.Refusal.COUNCIL_CANNOT_PAY, ContractService.accept(broke, PLAYER, offer, 1100L, SETTINGS).orElseThrow());
        assertEquals(ContractStatus.OFFERED, offer.status(), "the offer stays open");
        assertEquals(2, faction(broke).treasury(), "the treasury never goes negative");
    }

    // ------------------------------------------------------------------------------------------------ transaction

    @Test
    void deliveryIsOneTransactionThatCompletesExactlyOnce()
    {
        final KingdomsSavedData data = world(50, need(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200));
        final Contract contract = ContractService.refresh(data, TOWN, 1000L, SETTINGS, false).getFirst();
        assertTrue(ContractService.accept(data, PLAYER, contract, 1100L, SETTINGS).isEmpty());
        final FakeInventory inventory = new FakeInventory(new long[] {8, 5}, new long[] {64, 5});

        assertEquals(ContractService.Refusal.NOT_HOLDER,
            ContractService.deliver(data, OTHER, contract, inventory, 1200L, SETTINGS).refusal().orElseThrow());
        final ContractService.Delivery done = ContractService.deliver(data, PLAYER, contract, inventory, 1200L, SETTINGS);
        assertTrue(done.completed());
        assertEquals(96, done.credited());
        assertEquals(100, done.units(), "20 bread");
        assertEquals(52, inventory.count(0) + inventory.count(1), "72 - 20");
        assertEquals(100, stock(data, EconomicResource.FOOD), "every handed-over unit reaches the real stockpile");
        assertEquals(contract.agreedReward(), inventory.emeralds);
        assertTrue(contract.rewardIssued());
        assertTrue(contract.reputationApplied());
        assertEquals(5, ReputationService.standing(data, PLAYER, FACTION));

        assertEquals(ContractService.Refusal.NOT_ACCEPTED,
            ContractService.deliver(data, PLAYER, contract, inventory, 1300L, SETTINGS).refusal().orElseThrow(),
            "a second attempt changes nothing");
        assertEquals(contract.agreedReward(), inventory.emeralds);
        assertEquals(0, ContractService.claimPendingRewards(data, PLAYER, inventory, 1300L));

        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final Contract reloaded = restarted.contracts().get(contract.id()).orElseThrow();
        assertEquals(ContractService.Refusal.NOT_ACCEPTED,
            ContractService.deliver(restarted, PLAYER, reloaded, inventory, 1400L, SETTINGS).refusal().orElseThrow(),
            "completion is idempotent across save and restart");
        assertEquals(0, ContractService.claimPendingRewards(restarted, PLAYER, inventory, 1400L));
        assertEquals(contract.agreedReward(), inventory.emeralds);
        assertEquals(5, ReputationService.standing(restarted, PLAYER, FACTION));
    }

    @Test
    void partialDeliveriesAccumulateAndLateAttemptsFailTheContract()
    {
        final KingdomsSavedData data = world(50, need(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200));
        final Contract contract = ContractService.refresh(data, TOWN, 1000L, SETTINGS, false).getFirst();
        ContractService.accept(data, PLAYER, contract, 1100L, SETTINGS);
        final ContractService.Delivery part = ContractService.deliver(data, PLAYER, contract, new FakeInventory(new long[] {8, 5}),
            1200L, SETTINGS);
        assertFalse(part.completed());
        assertEquals(40, contract.delivered());
        assertEquals(1, contract.deliveries());
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final Contract reloaded = restarted.contracts().get(contract.id()).orElseThrow();
        assertEquals(40, reloaded.delivered(), "progress survives a restart");
        final FakeInventory more = new FakeInventory(new long[] {64, 5});
        assertEquals(ContractService.Refusal.EXPIRED,
            ContractService.deliver(restarted, PLAYER, reloaded, more, reloaded.deadline() + 1, SETTINGS).refusal().orElseThrow());
        assertEquals(64, more.count(0), "nothing is taken for a late attempt");
        assertEquals(ContractStatus.FAILED, reloaded.status());
        assertEquals(-6, ReputationService.standing(restarted, PLAYER, FACTION));
        assertEquals(40, restarted.colony(TOWN).orElseThrow().economy().resource(EconomicResource.FOOD).stockpile().amount(),
            "delivered goods stay with the settlement");
    }

    @Test
    void failuresMidwayNeitherDuplicateNorDestroyValue()
    {
        final KingdomsSavedData data = world(50, need(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200));
        final Contract contract = ContractService.refresh(data, TOWN, 1000L, SETTINGS, false).getFirst();
        ContractService.accept(data, PLAYER, contract, 1100L, SETTINGS);
        final long money = faction(data).treasury() + reserved(data);

        final FakeInventory changed = new FakeInventory(new long[] {64, 5});
        changed.failRemove = true;
        assertEquals(ContractService.Refusal.INVENTORY_CHANGED,
            ContractService.deliver(data, PLAYER, contract, changed, 1200L, SETTINGS).refusal().orElseThrow());
        assertEquals(64, changed.count(0), "nothing taken");
        assertEquals(0, contract.delivered());
        assertEquals(0, stock(data, EconomicResource.FOOD));

        assertEquals(ContractService.Refusal.NOTHING_TO_DELIVER,
            ContractService.deliver(data, PLAYER, contract, new FakeInventory(new long[] {10, 0}), 1200L, SETTINGS).refusal().orElseThrow());

        final FakeInventory cannotPay = new FakeInventory(new long[] {64, 5});
        cannotPay.failGive = true;
        assertThrows(IllegalStateException.class, () -> ContractService.deliver(data, PLAYER, contract, cannotPay, 1300L, SETTINGS),
            "the payout failure surfaces");
        assertEquals(ContractStatus.COMPLETED, contract.status(), "goods were delivered and stay delivered");
        assertEquals(100, stock(data, EconomicResource.FOOD));
        assertTrue(contract.rewardPending(), "the reward is pending, not lost");
        assertEquals(money, faction(data).treasury() + reserved(data), "and still reserved");
        assertEquals(1, data.contracts().pendingRewards(PLAYER).size());

        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final FakeInventory later = new FakeInventory();
        final int agreed = contract.agreedReward();
        assertEquals(agreed, ContractService.claimPendingRewards(restarted, PLAYER, later, 2000L));
        assertEquals(agreed, later.emeralds);
        assertEquals(0, ContractService.claimPendingRewards(restarted, PLAYER, later, 2001L), "exactly once");
        assertEquals(agreed, later.emeralds);
        assertEquals(5, ReputationService.standing(restarted, PLAYER, FACTION), "reputation applied once");
    }

    @Test
    void removedSettlementsCancelWithoutPenaltyAndReturnMoney()
    {
        final KingdomsSavedData data = world(50, need(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200),
            need(NeedType.STONE_SHORTAGE, NeedSeverity.MEDIUM, 0, 300));
        final List<Contract> offers = ContractService.refresh(data, TOWN, 1000L, SETTINGS, false);
        ContractService.accept(data, PLAYER, offers.get(0), 1100L, SETTINGS);
        final long money = faction(data).treasury() + reserved(data);
        data.removeColony(TOWN);
        final List<ContractService.Closure> closures = ContractService.sweep(data, 1200L, SETTINGS);
        assertEquals(1, closures.size(), "the accepted one is reported to its holder");
        assertTrue(offers.stream().allMatch(value -> value.status() == ContractStatus.CANCELLED));
        assertTrue(offers.stream().allMatch(value -> value.closeReason() == Contract.CloseReason.SETTLEMENT_REMOVED));
        assertEquals(money, faction(data).treasury());
        assertEquals(0, ReputationService.standing(data, PLAYER, FACTION));
    }

    @Test
    void registryRoundTripsAndPrunesOnlySettledHistory()
    {
        final KingdomsSavedData data = world(50, need(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200),
            need(NeedType.STONE_SHORTAGE, NeedSeverity.MEDIUM, 0, 300));
        final List<Contract> offers = ContractService.refresh(data, TOWN, 1000L, SETTINGS, false);
        ContractService.accept(data, PLAYER, offers.getFirst(), 1100L, SETTINGS);
        ContractService.deliver(data, PLAYER, offers.getFirst(), new FakeInventory(new long[] {2, 4}), 1200L, SETTINGS);
        final ContractRegistry loaded = ContractRegistry.load(data.contracts().save());
        assertEquals(data.contracts().save(), loaded.save(), "no drift");
        final Contract copy = loaded.get(offers.getFirst().id()).orElseThrow();
        assertEquals(PLAYER, copy.holder());
        assertEquals(8, copy.delivered());
        assertEquals(offers.getFirst().objective(), copy.objective());
        assertEquals(offers.getFirst().reservedReward(), copy.reservedReward());
        assertEquals(copy, loaded.resolve(copy.id().toString().substring(0, 8)).orElseThrow());
        assertTrue(loaded.resolve("abc").isEmpty());

        ContractService.sweep(data, 1000L + SETTINGS.offerLifetimeTicks() + 1, SETTINGS);
        assertEquals(ContractStatus.EXPIRED, offers.get(1).status());
        assertEquals(1, data.contracts().prune(10_000_000L, SETTINGS.historyRetentionTicks(), 256, Set.of(TOWN), Set.of(FACTION)),
            "old closed contracts are dropped; open ones stay");
        assertTrue(data.contracts().get(offers.getFirst().id()).isPresent());
    }

    @Test
    void reputationIsPerPlayerAndAudited()
    {
        final KingdomsSavedData data = world(50);
        ReputationService.adjust(data, PLAYER, FACTION, 7, ReputationRegistry.Cause.CONTRACT_COMPLETED, null, 10L);
        ReputationService.adjust(data, OTHER, FACTION, -10, ReputationRegistry.Cause.REPRESENTATIVE_KILLED, null, 11L);
        assertEquals(7, ReputationService.standing(data, PLAYER, FACTION));
        assertEquals(-10, ReputationService.standing(data, OTHER, FACTION), "multiplayer: independent standings");
        assertEquals(2, data.reputation().events().size());
        assertEquals(ReputationRegistry.Cause.REPRESENTATIVE_KILLED, data.reputation().events().getFirst().cause());
        assertEquals(1, data.reputation().record(OTHER, FACTION).orElseThrow().killed());
        assertEquals(data.reputation().save(), ReputationRegistry.load(data.reputation().save()).save());
    }
}
