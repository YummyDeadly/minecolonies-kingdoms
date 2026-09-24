package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.EconomyManager;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecuritySettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.war.WarRecord;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.war.WarSettings;
import com.minecolonies.kingdoms.worldevent.WorldEventRecord;
import com.minecolonies.kingdoms.worldevent.WorldEventService;
import com.minecolonies.kingdoms.worldevent.WorldEventSettings;
import com.minecolonies.kingdoms.worldevent.WorldEventType;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Schema 16 (Phase 11): v15 saves gain empty world events and history; everything else is kept as it was. */
class SchemaV16MigrationTest
{
    private static KingdomsSavedData phase10World()
    {
        final KingdomsSavedData data = world();
        MilitaryService.evaluate(data, 0L, SecuritySettings.defaults(), SETTINGS, CONTRACTS);
        MilitaryService.setStrength(data, A, 12);
        DiplomacyService.set(data, data.faction(FA).orElseThrow(), data.faction(FB).orElseThrow(), -70, DiplomacyState.Cause.ADMIN_SET, 0L, 0L);
        WarService.declare(data, FA, FB, WarRecord.Cause.ADMIN, 0L, 0L, WarSettings.defaults(), false);
        new EconomyManager().setProduction(data.colony(A).orElseThrow(), EconomicResource.FOOD, 42.0D);
        return data;
    }

    @Test
    void versionFifteenSavesGainEmptyEventsAndHistoryAndKeepEverythingElse()
    {
        final KingdomsSavedData data = phase10World();
        final CompoundTag v15 = data.save(new CompoundTag(), null);
        v15.putInt("dataVersion", 15);
        v15.remove("worldEvents");
        v15.remove("history");
        final CompoundTag before = v15.copy();
        final KingdomsSavedData loaded = PersistenceTestAccess.load(v15);
        assertEquals(16, KingdomsSavedData.DATA_VERSION);
        assertTrue(loaded.worldEvents().events().isEmpty());
        assertEquals(0L, loaded.worldEvents().evaluations());
        assertFalse(loaded.worldEvents().seeded(), "the world seed is taken on the first update");
        assertEquals(0, loaded.history().size());
        final CompoundTag after = loaded.save(new CompoundTag(), null);
        for (final String key : before.getAllKeys())
            if (!key.equals("dataVersion")) assertEquals(before.get(key), after.get(key), "unchanged by the migration: " + key);
        assertEquals(16, after.getInt("dataVersion"));
        assertEquals(1, loaded.war().wars().size());
        assertEquals(12, loaded.military().garrison(A).orElseThrow().strength());
        assertEquals(42.0D, loaded.colony(A).orElseThrow().economy().resource(EconomicResource.FOOD).flow().productionPerDay());
    }

    @Test
    void schemaSixteenRoundTripsEventsCooldownsAndHistory()
    {
        final KingdomsSavedData data = world();
        data.worldEvents().seedIfUnset(7L);
        new EconomyManager().setStockpile(data.colony(A).orElseThrow(), EconomicResource.FOOD, 500L);
        new EconomyManager().setProduction(data.colony(A).orElseThrow(), EconomicResource.FOOD, 20.0D);
        final WorldEventSettings settings = WorldEventSettings.defaults();
        final WorldEventRecord event = WorldEventService.trigger(data, WorldEventType.HARVEST_FAILURE, A, null, 0L, settings, true).orElseThrow();
        WorldEventService.update(data, event.startsAt(), settings);
        final CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(16, saved.getInt("dataVersion"));
        final KingdomsSavedData loaded = PersistenceTestAccess.load(saved);
        assertEquals(saved, loaded.save(new CompoundTag(), null), "save -> load -> save is stable");
        final WorldEventRecord copy = loaded.worldEvents().event(event.id()).orElseThrow();
        assertEquals(WorldEventRecord.Status.ACTIVE, copy.status());
        assertTrue(copy.applied());
        assertEquals(event.productionDelta(), copy.productionDelta());
        assertEquals(event.stockLost(), copy.stockLost());
        assertEquals(7L, loaded.worldEvents().seed());
        assertEquals(1, loaded.worldEvents().cooldowns());
        assertEquals(data.history().entries(), loaded.history().entries());
    }
}
