package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecuritySettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.trade.TradeShipment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Schema 14 (Phase 9): v13 saves gain an empty military registry; garrisons start from the colonies' soldiers. */
class SchemaV14MigrationTest
{
    private static KingdomsSavedData phase81World()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "v14-ship", 100, 0L, 4_000L);
        final BanditEncounter ambush = ambush(data, shipment, 0L);
        activate(data, ambush, shipment);
        CampService.establish(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 10L, SETTINGS, true);
        return data;
    }

    @Test
    void versionThirteenSavesGainGarrisonsFromTheirSoldiersAndKeepEverythingElse()
    {
        final KingdomsSavedData data = phase81World();
        final CompoundTag v13 = data.save(new CompoundTag(), null);
        v13.putInt("dataVersion", 13);
        v13.remove("military");
        final ListTag encounters = v13.getCompound("bandits").getList("encounters", 10);
        for (int index = 0; index < encounters.size(); index++)
        {
            encounters.getCompound(index).remove("garrisonLosses");
            encounters.getCompound(index).remove("garrisonDefenders");
        }
        final KingdomsSavedData loaded = PersistenceTestAccess.load(v13);
        assertTrue(KingdomsSavedData.DATA_VERSION >= 14);
        assertTrue(loaded.military().garrisons().isEmpty(), "garrisons are created by the first evaluation, not by the migration");
        assertEquals(-1L, loaded.military().lastEvaluatedAt());
        assertEquals(data.bandits().encounters().size(), loaded.bandits().encounters().size());
        assertEquals(data.bandits().camps().size(), loaded.bandits().camps().size());
        loaded.bandits().encounters().forEach(encounter -> {
            assertTrue(encounter.garrisonLosses().isEmpty());
            assertTrue(encounter.garrisonDefenders().isEmpty());
        });
        MilitaryService.evaluate(loaded, 100L, SecuritySettings.defaults(), SETTINGS, CONTRACTS);
        assertEquals(2, loaded.military().garrison(A).orElseThrow().strength(), "the village keeps its recorded soldiers");
        assertEquals(2, loaded.military().garrison(B).orElseThrow().strength());
        assertEquals(data.tradeLedger().shipments().size(), loaded.tradeLedger().shipments().size());
        assertEquals(data.contracts().contracts().size(), loaded.contracts().contracts().size());
    }

    @Test
    void schemaFourteenRoundTripsGarrisonsAndEncounterLossesWithoutDrift()
    {
        final KingdomsSavedData data = phase81World();
        MilitaryService.evaluate(data, 100L, SecuritySettings.defaults(), SETTINGS, CONTRACTS);
        final BanditEncounter open = data.bandits().open().getFirst();
        EncounterService.recordGarrisonLoss(data, open, A);
        EncounterService.recordGarrisonDefender(data, open, B);
        final CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(KingdomsSavedData.DATA_VERSION, saved.getInt("dataVersion"));
        final KingdomsSavedData loaded = PersistenceTestAccess.load(saved);
        assertEquals(saved, loaded.save(new CompoundTag(), null), "save -> load -> save is stable");
        final BanditEncounter copy = loaded.bandits().encounter(open.id()).orElseThrow();
        assertEquals(1, copy.garrisonLosses().get(A));
        assertTrue(copy.garrisonDefenders().contains(A) && copy.garrisonDefenders().contains(B));
        assertEquals(data.military().garrison(A).orElseThrow().security(), loaded.military().garrison(A).orElseThrow().security());
    }
}
