package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.diplomacy.DiplomacyService;
import com.minecolonies.kingdoms.diplomacy.DiplomacyState;
import com.minecolonies.kingdoms.military.MilitaryService;
import com.minecolonies.kingdoms.military.SecuritySettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.war.BattleRecord;
import com.minecolonies.kingdoms.war.CampaignService;
import com.minecolonies.kingdoms.war.WarRecord;
import com.minecolonies.kingdoms.war.WarService;
import com.minecolonies.kingdoms.war.WarSettings;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Schema 15 (Phase 10): v14 saves gain an empty war registry; relations never turn into wars by migration. */
class SchemaV15MigrationTest
{
    private static KingdomsSavedData phase9World()
    {
        final KingdomsSavedData data = world();
        MilitaryService.evaluate(data, 0L, SecuritySettings.defaults(), SETTINGS, CONTRACTS);
        MilitaryService.setStrength(data, A, 12);
        return data;
    }

    @Test
    void versionFourteenSavesGainAnEmptyWarRegistryAndKeepHostileRelationsAsRelations()
    {
        final KingdomsSavedData data = phase9World();
        DiplomacyService.set(data, data.faction(FA).orElseThrow(), data.faction(FB).orElseThrow(), -90, DiplomacyState.Cause.ADMIN_SET, 0L, 0L);
        final CompoundTag v14 = data.save(new CompoundTag(), null);
        v14.putInt("dataVersion", 14);
        v14.remove("war");
        final KingdomsSavedData loaded = PersistenceTestAccess.load(v14);
        assertTrue(KingdomsSavedData.DATA_VERSION >= 15);
        assertTrue(loaded.war().wars().isEmpty(), "a hostile relation is not a war");
        assertTrue(loaded.war().armies().isEmpty());
        assertTrue(loaded.war().battles().isEmpty());
        assertEquals(-1L, loaded.war().lastEvaluatedAt());
        assertEquals(-90, DiplomacyService.relation(loaded.faction(FA).orElseThrow(), loaded.faction(FB).orElseThrow()));
        assertEquals(12, loaded.military().garrison(A).orElseThrow().strength());
        assertEquals(0, loaded.military().garrison(A).orElseThrow().detached());
        assertEquals(data.settlements().records().size(), loaded.settlements().records().size());
    }

    @Test
    void schemaFifteenRoundTripsWarsArmiesAndBattlesWithoutDrift()
    {
        final KingdomsSavedData data = phase9World();
        final WarSettings settings = WarSettings.defaults();
        final WarRecord war = WarService.declare(data, FA, FB, WarRecord.Cause.ADMIN, 0L, 0L, settings, false).war().orElseThrow();
        WarService.mobilizeNow(data, war);
        CampaignService.update(data, 100L, settings, CONTRACTS);
        final var army = data.war().armies().iterator().next();
        final BattleRecord battle = CampaignService.update(data, 100L + army.fullTravelTicks(), settings, CONTRACTS).started().getFirst();
        CampaignService.attackerFell(data, battle, PLAYER);
        CampaignService.defenderFell(data, battle);
        final CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(KingdomsSavedData.DATA_VERSION, saved.getInt("dataVersion"));
        final KingdomsSavedData loaded = PersistenceTestAccess.load(saved);
        assertEquals(saved, loaded.save(new CompoundTag(), null), "save -> load -> save is stable");
        final BattleRecord copy = loaded.war().battle(battle.id()).orElseThrow();
        assertEquals(1, copy.attackerPhysicalLosses());
        assertEquals(1, copy.defenderPhysicalLosses());
        assertTrue(copy.defenders().contains(PLAYER));
        assertEquals(army.strength(), loaded.war().army(army.id()).orElseThrow().strength());
        assertEquals(1, loaded.war().war(war.id()).orElseThrow().armiesRaised());
        assertEquals(army.detached(), loaded.military().garrison(A).orElseThrow().detached());
    }
}
