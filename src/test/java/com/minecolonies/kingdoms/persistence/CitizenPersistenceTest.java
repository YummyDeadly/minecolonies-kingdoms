package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.citizen.CitizenRegistry;
import com.minecolonies.kingdoms.citizen.RosterPlanner;
import com.minecolonies.kingdoms.citizen.SettlementRepresentative;
import com.minecolonies.kingdoms.world.settlement.SettlementFixtures;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CitizenPersistenceTest
{
    private static final UUID SETTLEMENT = UUID.nameUUIDFromBytes("persisted-settlement".getBytes());

    private static List<SettlementRepresentative> roster(final int population)
    {
        return RosterPlanner.plan(SETTLEMENT, SettlementType.VILLAGE, population, List.of(
            new RosterPlanner.Building(UUID.nameUUIDFromBytes("h".getBytes()), SettlementBuildingType.HOUSE, 0),
            new RosterPlanner.Building(UUID.nameUUIDFromBytes("f".getBytes()), SettlementBuildingType.FARM, 1)), List.of(), 16);
    }

    @Test
    void rosterRoundTripsWithoutChangingTheLogicalPopulation()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(SETTLEMENT, SettlementFixtures.OVERWORLD,
            new BlockPos(0, 64, 0), "Persisted", UUID.randomUUID(), 0L);
        colony.updatePopulation(37, 20, 3);
        data.putColony(colony);
        data.citizens().put(SETTLEMENT, roster(37));
        final CompoundTag saved = data.save(new CompoundTag(), null);
        final KingdomsSavedData loaded = KingdomsSavedData.load(saved, null);
        assertEquals(roster(37), loaded.citizens().roster(SETTLEMENT));
        assertEquals(37, loaded.colony(SETTLEMENT).orElseThrow().population(), "representation never changes population");
        assertEquals(saved, loaded.save(new CompoundTag(), null), "no drift on a second save");
        final var someone = roster(37).getFirst();
        assertEquals(someone, loaded.citizens().find(someone.id()).orElseThrow());
    }

    @Test
    void versionEightSaveMigratesToAnEmptyCitizenRegistry()
    {
        final KingdomsSavedData data = new KingdomsSavedData();
        final NPCColonyData colony = NPCColonyData.createStrategicNpc(SETTLEMENT, SettlementFixtures.OVERWORLD,
            new BlockPos(0, 64, 0), "Old", UUID.randomUUID(), 0L);
        colony.updatePopulation(12, 6, 1);
        data.putColony(colony);
        final CompoundTag v8 = data.save(new CompoundTag(), null);
        v8.putInt("dataVersion", 8);
        v8.remove("citizens");
        final KingdomsSavedData loaded = KingdomsSavedData.load(v8, null);
        assertTrue(KingdomsSavedData.DATA_VERSION >= 9);
        assertEquals(0, loaded.citizens().representativeCount());
        assertEquals(12, loaded.colony(SETTLEMENT).orElseThrow().population());
        assertEquals(SETTLEMENT, loaded.colony(SETTLEMENT).orElseThrow().id());
    }

    @Test
    void deletedSettlementsLoseTheirRostersAndInvalidEntriesAreDropped()
    {
        final CitizenRegistry registry = new CitizenRegistry();
        final UUID other = UUID.nameUUIDFromBytes("other".getBytes());
        registry.put(SETTLEMENT, roster(20));
        registry.put(other, RosterPlanner.plan(other, SettlementType.TOWN, 5, List.of(), List.of(), 16));
        assertEquals(Set.of(other), registry.retain(Set.of(SETTLEMENT)));
        assertTrue(registry.roster(other).isEmpty());
        final CompoundTag saved = registry.save();
        final ListTag people = saved.getList("rosters", 10).getCompound(0).getList("representatives", 10);
        people.getCompound(0).putString("role", "FARMER");
        people.getCompound(0).remove("work"); // a working role without a workplace is invalid
        final CitizenRegistry loaded = CitizenRegistry.load(saved);
        assertEquals(roster(20).size() - 1, loaded.roster(SETTLEMENT).size());
    }
}
