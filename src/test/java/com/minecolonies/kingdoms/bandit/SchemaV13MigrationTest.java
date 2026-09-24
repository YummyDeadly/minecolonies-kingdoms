package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.trade.TradeShipment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Schema 13 (Phase 8.1): v12 saves gain an empty camp list; threat records read camp fields as none. */
class SchemaV13MigrationTest
{
    /** A realistic Phase 8 world: a tracked, raided road, a live ambush, and a roadblock. */
    private static KingdomsSavedData phase8World()
    {
        final KingdomsSavedData data = world();
        final TradeShipment shipment = shipment(data, "v13-ship", 100, 0L, 4_000L);
        final BanditEncounter ambush = ambush(data, shipment, 0L);
        activate(data, ambush, shipment);
        EncounterService.createRoadblock(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 10L, SETTINGS, true);
        data.bandits().threatFor(ROAD).setThreat(55.0D);
        data.bandits().threatFor(ROAD).raided(20L, 100L);
        return data;
    }

    @Test
    void versionTwelveSavesGainAnEmptyCampListAndKeepEverythingElse()
    {
        final KingdomsSavedData data = phase8World();
        final CompoundTag v12 = data.save(new CompoundTag(), null);
        v12.putInt("dataVersion", 12);
        final CompoundTag bandits = v12.getCompound("bandits");
        bandits.remove("camps");
        final ListTag threats = bandits.getList("threats", 10);
        for (int index = 0; index < threats.size(); index++)
        {
            final CompoundTag threat = threats.getCompound(index);
            threat.remove("campPressure");
            threat.remove("campCooldownUntil");
            threat.remove("camps");
            threat.remove("cCamp");
        }
        final KingdomsSavedData loaded = PersistenceTestAccess.load(v12);
        assertTrue(KingdomsSavedData.DATA_VERSION >= 13);
        assertTrue(loaded.bandits().camps().isEmpty());
        final RoadThreat threat = loaded.bandits().threat(ROAD).orElseThrow();
        assertEquals(0, threat.campPressure());
        assertEquals(0, threat.camps());
        assertFalse(threat.campCoolingDownAt(0L));
        assertEquals(0.0D, threat.lastContributors().camp());
        assertEquals(55.0D, threat.threat(), 1.0E-9, "the Phase 8 threat is unchanged");
        assertEquals(1, threat.raids());
        assertEquals(data.bandits().encounters().size(), loaded.bandits().encounters().size(), "encounters are kept");
        for (final BanditEncounter original : data.bandits().encounters())
        {
            final BanditEncounter copy = loaded.bandits().encounter(original.id()).orElseThrow();
            assertEquals(original.status(), copy.status());
            assertEquals(original.position(), copy.position());
            assertEquals(original.remainingStrength(), copy.remainingStrength());
        }
        assertEquals(data.tradeLedger().shipments().size(), loaded.tradeLedger().shipments().size());
        assertEquals(data.contracts().contracts().size(), loaded.contracts().contracts().size());
        assertEquals(data.bandits().assessments(), loaded.bandits().assessments());
    }

    @Test
    void schemaThirteenRoundTripsCampsWithoutDrift()
    {
        final KingdomsSavedData data = phase8World();
        final BanditCamp camp = CampService.establish(data, data.roads().get(ROAD).orElseThrow(), anchors(data), 30L, SETTINGS, true)
            .orElseThrow();
        CampService.built(data, camp, java.util.List.of(new BanditCamp.PlacedBlock(camp.position(), "minecraft:campfire"),
            new BanditCamp.PlacedBlock(camp.position().east(2), "minecraft:brown_wool")), 40L);
        final CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(KingdomsSavedData.DATA_VERSION, saved.getInt("dataVersion"));
        final KingdomsSavedData loaded = PersistenceTestAccess.load(saved);
        assertEquals(saved, loaded.save(new CompoundTag(), null), "save -> load -> save is stable");
        final BanditCamp copy = loaded.bandits().camp(camp.id()).orElseThrow();
        assertEquals(camp.sites(), copy.sites());
        assertEquals(camp.placed(), copy.placed());
        assertEquals(camp.strength(), copy.strength());
        assertEquals(camp.expiresAt(), copy.expiresAt());
        assertEquals(1, loaded.bandits().threat(ROAD).orElseThrow().camps());
    }
}
