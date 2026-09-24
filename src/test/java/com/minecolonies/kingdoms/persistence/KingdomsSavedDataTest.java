package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.kingdom.Kingdom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingdomsSavedDataTest
{
    @Test
    void completeRootDataRoundTrips()
    {
        final UUID factionId = UUID.randomUUID();
        final UUID colonyId = UUID.randomUUID();
        final Faction faction = new Faction(factionId, "Greyholm", FactionType.KINGDOM);
        faction.setCapitalColonyId(colonyId);

        final Kingdom kingdom = new Kingdom(UUID.randomUUID(), factionId, "The Grey Crown");
        kingdom.setCapitalColonyId(colonyId);

        final NPCColonyData colony = new NPCColonyData(
            colonyId,
            7,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            "Greyholm",
            factionId);

        final KingdomsSavedData original = new KingdomsSavedData();
        original.putFaction(faction);
        original.putKingdom(kingdom);
        original.putColony(colony);

        final CompoundTag serialized = original.save(new CompoundTag(), null);
        final KingdomsSavedData loaded = KingdomsSavedData.load(serialized, null);

        assertEquals(KingdomsSavedData.DATA_VERSION, serialized.getInt("dataVersion"));
        assertEquals(1, loaded.factions().size());
        assertEquals(1, loaded.kingdoms().size());
        assertEquals(1, loaded.colonies().size());
        assertTrue(loaded.colony(colonyId).isPresent());
    }

    @Test
    void versionOneSaveMigratesWithEconomyDefaults()
    {
        final UUID colonyId = UUID.randomUUID();
        final NPCColonyData colony = new NPCColonyData(
            colonyId,
            9,
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            "Legacy",
            UUID.randomUUID());
        final CompoundTag legacyColony = colony.save();
        legacyColony.remove("economy");
        legacyColony.remove("needs");
        legacyColony.remove("lastDecision");
        final ListTag colonies = new ListTag();
        colonies.add(legacyColony);
        final CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.putInt("dataVersion", 1);
        legacyRoot.put("factions", new ListTag());
        legacyRoot.put("kingdoms", new ListTag());
        legacyRoot.put("colonies", colonies);

        final KingdomsSavedData loaded = KingdomsSavedData.load(legacyRoot, null);
        final NPCColonyData migrated = loaded.colony(colonyId).orElseThrow();

        assertEquals(0L, migrated.economy().resource(com.minecolonies.kingdoms.economy.EconomicResource.FOOD)
            .stockpile().amount());
        assertTrue(migrated.needs().isEmpty());
        assertEquals(com.minecolonies.kingdoms.colony.decision.StrategicActionType.NONE,
            migrated.lastDecision().action());
    }

    @Test
    void versionTwoSaveMigratesToEmptyTradeLedger()
    {
        final CompoundTag versionTwo = new CompoundTag();
        versionTwo.putInt("dataVersion", 2);
        versionTwo.put("factions", new ListTag());
        versionTwo.put("kingdoms", new ListTag());
        versionTwo.put("colonies", new ListTag());

        final KingdomsSavedData loaded = KingdomsSavedData.load(versionTwo, null);
        final CompoundTag reserialized = loaded.save(new CompoundTag(), null);

        assertEquals(KingdomsSavedData.DATA_VERSION, reserialized.getInt("dataVersion"));
        assertTrue(loaded.tradeLedger().routes().isEmpty());
        assertTrue(loaded.tradeLedger().shipments().isEmpty());
        assertTrue(loaded.settlements().records().isEmpty());
        assertTrue(loaded.roads().roads().isEmpty());
        assertTrue(loaded.growth().buildings().isEmpty());
    }
}
