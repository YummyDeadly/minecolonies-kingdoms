package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractRegistry;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.diplomacy.DiplomacyEvaluator;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SchemaV11MigrationTest
{
    private static final UUID TOWN = UUID.nameUUIDFromBytes("v11-town".getBytes());
    private static final UUID FACTION = UUID.nameUUIDFromBytes("v11-faction".getBytes());
    private static final UUID NEIGHBOUR = UUID.nameUUIDFromBytes("v11-neighbour".getBytes());
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("v11-player".getBytes());

    private static KingdomsSavedData data()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final Faction faction = new Faction(FACTION, "Town Council", FactionType.CITY_STATE);
        faction.setCapitalColonyId(TOWN);
        faction.setRelation(NEIGHBOUR, 33);
        faction.setTreasury(40);
        data.putFaction(faction);
        final Faction neighbour = new Faction(NEIGHBOUR, "Neighbour", FactionType.CITY_STATE);
        neighbour.setRelation(FACTION, 33);
        data.putFaction(neighbour);
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(TOWN, SettlementFixtures.OVERWORLD, new BlockPos(0, 64, 0),
            "Town", FACTION, 0L);
        colony.updatePopulation(30, 15, 2);
        colony.replaceNeeds(List.of(new ColonyNeed(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200, 0L)));
        data.putColony(colony);
        return data;
    }

    private static CompoundTag faction(final CompoundTag root, final UUID id)
    {
        final ListTag factions = root.getList("factions", 10);
        for (int index = 0; index < factions.size(); index++)
            if (factions.getCompound(index).getUUID("id").equals(id)) return factions.getCompound(index);
        throw new AssertionError("no faction " + id);
    }

    @Test
    void versionNineSavesMigrateThroughTenToEleven()
    {
        final CompoundTag v9 = data().save(new CompoundTag(), null);
        v9.putInt("dataVersion", 9);
        v9.remove("contracts");
        v9.remove("diplomacy");
        v9.remove("reputation");
        faction(v9, FACTION).put("reputation", new ListTag()); // Phase 1 field, always empty before Phase 7
        final KingdomsSavedData loaded = PersistenceTestAccess.load(v9);
        assertTrue(KingdomsSavedData.DATA_VERSION >= 11);
        assertTrue(loaded.contracts().contracts().isEmpty());
        assertEquals(0, loaded.reputation().entries());
        assertEquals(33, loaded.faction(FACTION).orElseThrow().relations().get(NEIGHBOUR), "relation values are kept");
        assertEquals(1, loaded.diplomacy().events().size(), "and logged once as migrated");
        assertEquals(DiplomacyState.Cause.MIGRATED, loaded.diplomacy().events().getFirst().cause());
        assertEquals(40, loaded.faction(FACTION).orElseThrow().treasury());
        assertEquals(30, loaded.colony(TOWN).orElseThrow().population());
        assertFalse(loaded.save(new CompoundTag(), null).getList("factions", 10).getCompound(0).contains("reputation"),
            "the ambiguous faction field is gone");
    }

    /** A schema-10 save as written by the preliminary Phase 7 build. */
    private static CompoundTag preliminaryV10()
    {
        final CompoundTag root = data().save(new CompoundTag(), null);
        root.putInt("dataVersion", 10);
        root.remove("reputation");
        final ListTag standings = new ListTag();
        final CompoundTag standing = new CompoundTag();
        standing.putUUID("id", PLAYER);
        standing.putInt("value", 12);
        standings.add(standing);
        faction(root, FACTION).put("reputation", standings);
        faction(root, FACTION).putLong("treasury", 20); // after escrowing 12 (offered food) + 8 (accepted wood)
        final ListTag contracts = new ListTag();
        contracts.add(oldContract("offered", "OFFERED", null, 12));
        contracts.add(oldContract("active", "ACTIVE", PLAYER, 8));
        contracts.add(oldContract("done", "COMPLETED", PLAYER, 5));
        contracts.add(oldContract("gone", "WITHDRAWN", null, 3));
        contracts.add(oldContract("quit", "ABANDONED", PLAYER, 4));
        final CompoundTag registry = new CompoundTag();
        registry.put("contracts", contracts);
        root.put("contracts", registry);
        final CompoundTag diplomacy = new CompoundTag();
        diplomacy.putLong("lastEvaluatedAt", 50_000L);
        diplomacy.put("events", new ListTag());
        root.put("diplomacy", diplomacy);
        return root;
    }

    private static CompoundTag oldContract(final String name, final String status, final UUID holder, final int reward)
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", UUID.nameUUIDFromBytes(name.getBytes()));
        tag.putUUID("settlement", TOWN);
        tag.putUUID("faction", FACTION);
        tag.putString("resource", name.equals("active") ? "WOOD" : "FOOD");
        tag.putLong("amount", 96);
        tag.putLong("delivered", name.equals("done") ? 96 : name.equals("active") ? 16 : 0);
        tag.putInt("reward", reward);
        tag.putInt("reputation", 5);
        tag.putString("severity", "HIGH");
        tag.putLong("createdAt", 1_000L);
        tag.putLong("offerExpiresAt", 49_000L);
        tag.putString("status", status);
        if (holder != null) tag.putUUID("holder", holder);
        tag.putLong("acceptedAt", holder == null ? -1L : 2_000L);
        tag.putLong("deadline", holder == null ? -1L : 74_000L);
        tag.putLong("closedAt", status.equals("OFFERED") || status.equals("ACTIVE") ? -1L : 3_000L);
        return tag;
    }

    @Test
    void preliminaryPhaseSevenSavesConvertWithoutLosingValue()
    {
        final KingdomsSavedData loaded = PersistenceTestAccess.load(preliminaryV10());
        assertEquals(12, loaded.reputation().value(PLAYER, FACTION), "standing moved to the reputation registry");
        final ContractRegistry contracts = loaded.contracts();
        final Contract offered = contracts.get(UUID.nameUUIDFromBytes("offered".getBytes())).orElseThrow();
        final Contract active = contracts.get(UUID.nameUUIDFromBytes("active".getBytes())).orElseThrow();
        final Contract done = contracts.get(UUID.nameUUIDFromBytes("done".getBytes())).orElseThrow();
        assertEquals(ContractStatus.OFFERED, offered.status());
        assertEquals(0, offered.reservedReward(), "offers reserve nothing any more");
        assertEquals(ContractStatus.ACCEPTED, active.status());
        assertEquals(8, active.reservedReward(), "the accepted contract keeps its escrow as the reservation");
        assertEquals(16, active.delivered());
        assertEquals(ContractStatus.COMPLETED, done.status());
        assertTrue(done.rewardIssued() && done.reputationApplied(), "already paid: never paid again");
        assertEquals(ContractStatus.EXPIRED, contracts.get(UUID.nameUUIDFromBytes("gone".getBytes())).orElseThrow().status());
        final Contract quit = contracts.get(UUID.nameUUIDFromBytes("quit".getBytes())).orElseThrow();
        assertEquals(ContractStatus.CANCELLED, quit.status());
        assertEquals(Contract.CloseReason.PLAYER_ABANDONED, quit.closeReason());
        assertEquals(32, loaded.faction(FACTION).orElseThrow().treasury(), "20 + the 12 that the old build escrowed for an offer");
        assertEquals(0, ContractService.claimPendingRewards(loaded, PLAYER, null, 60_000L), "nothing pending after migration");

        final CompoundTag saved = loaded.save(new CompoundTag(), null);
        final KingdomsSavedData again = PersistenceTestAccess.load(saved);
        assertEquals(saved, again.save(new CompoundTag(), null), "schema 11 round trip without drift");
    }

    @Test
    void schemaElevenRoundTripsContractsReputationAndDiplomacy()
    {
        final KingdomsSavedData data = data();
        final List<Contract> offers = ContractService.refresh(data, TOWN, 500L, ContractSettings.defaults(), false);
        assertEquals(1, offers.size());
        assertTrue(ContractService.accept(data, PLAYER, offers.getFirst(), 600L, ContractSettings.defaults()).isEmpty());
        DiplomacyEvaluator.evaluate(data, 24_000L);
        final CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(ContractRegistry.FORMAT, saved.getCompound("contracts").getInt("format"));
        final KingdomsSavedData loaded = PersistenceTestAccess.load(saved);
        assertEquals(saved, loaded.save(new CompoundTag(), null));
        assertEquals(PLAYER, loaded.contracts().get(offers.getFirst().id()).orElseThrow().holder());
        assertEquals(offers.getFirst().reservedReward(), loaded.contracts().get(offers.getFirst().id()).orElseThrow().reservedReward());
        assertEquals(24_000L, loaded.diplomacy().lastEvaluatedAt());
        assertEquals(data.faction(FACTION).orElseThrow().treasury(), loaded.faction(FACTION).orElseThrow().treasury());
    }
}
