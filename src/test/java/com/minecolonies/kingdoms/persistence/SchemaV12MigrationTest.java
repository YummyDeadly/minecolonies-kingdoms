package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.colony.need.NeedType;
import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SchemaV12MigrationTest
{
    private static final UUID TOWN = UUID.nameUUIDFromBytes("v12-town".getBytes());
    private static final UUID OTHER = UUID.nameUUIDFromBytes("v12-other".getBytes());
    private static final UUID FACTION = UUID.nameUUIDFromBytes("v12-faction".getBytes());

    private static KingdomsSavedData data()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final Faction faction = new Faction(FACTION, "Town Council", FactionType.CITY_STATE);
        faction.setCapitalColonyId(TOWN);
        data.putFaction(faction);
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(TOWN, SettlementFixtures.OVERWORLD, BlockPos.ZERO, "Town", FACTION, 0L);
        colony.updatePopulation(30, 15, 2);
        colony.replaceNeeds(List.of(new ColonyNeed(NeedType.FOOD_SHORTAGE, NeedSeverity.HIGH, 0, 200, 0L)));
        data.putColony(colony);
        data.putColony(NPCColonyData.createStrategicNpc(OTHER, SettlementFixtures.OVERWORLD, new BlockPos(900, 64, 0), "Other", FACTION, 0L));
        final TradeShipment shipment = new TradeShipment(UUID.nameUUIDFromBytes("v12-ship".getBytes()), UUID.randomUUID(), TOWN, OTHER,
            EconomicResource.FOOD, 80, 1L);
        shipment.depart(10L, 1_000L);
        data.tradeLedger().putShipment(shipment);
        ContractService.refresh(data, TOWN, 20L, ContractSettings.defaults(), false);
        return data;
    }

    @Test
    void versionElevenSavesGainAnEmptyBanditRegistryAndKeepEverythingElse()
    {
        final CompoundTag v11 = data().save(new CompoundTag(), null);
        v11.putInt("dataVersion", 11);
        v11.remove("bandits");
        final ListTag contracts = v11.getCompound("contracts").getList("contracts", 10);
        for (int index = 0; index < contracts.size(); index++) contracts.getCompound(index).remove("kind"); // format 2 records
        v11.getCompound("contracts").putInt("format", 2);
        final KingdomsSavedData loaded = PersistenceTestAccess.load(v11);
        assertTrue(KingdomsSavedData.DATA_VERSION >= 12);
        assertTrue(loaded.bandits().encounters().isEmpty());
        assertTrue(loaded.bandits().threats().isEmpty());
        assertEquals(-1L, loaded.bandits().lastEvaluatedAt());
        assertFalse(loaded.contracts().contracts().isEmpty());
        assertTrue(loaded.contracts().contracts().stream().allMatch(value -> value.kind() == Contract.Kind.DELIVERY),
            "format-2 contracts read as deliveries");
        final TradeShipment shipment = loaded.tradeLedger().shipments().iterator().next();
        assertEquals(0L, shipment.lostAmount(), "shipments without bandit fields have lost nothing");
        assertEquals(80L, shipment.deliverableAmount());
        assertNull(shipment.banditEncounterId());
    }

    @Test
    void schemaTwelveRoundTripsWithoutDrift()
    {
        final KingdomsSavedData data = data();
        final TradeShipment shipment = data.tradeLedger().shipments().iterator().next();
        assertTrue(shipment.recordBanditLoss(UUID.nameUUIDFromBytes("v12-encounter".getBytes()), 30L));
        final CompoundTag saved = data.save(new CompoundTag(), null);
        final KingdomsSavedData loaded = PersistenceTestAccess.load(saved);
        assertEquals(saved, loaded.save(new CompoundTag(), null));
        final TradeShipment copy = loaded.tradeLedger().shipment(shipment.id()).orElseThrow();
        assertEquals(30L, copy.lostAmount());
        assertEquals(50L, copy.deliverableAmount());
        assertFalse(copy.recordBanditLoss(UUID.randomUUID(), 10L), "the persisted loss still blocks a second one");
    }
}
