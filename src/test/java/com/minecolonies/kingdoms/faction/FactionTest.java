package com.minecolonies.kingdoms.faction;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactionTest
{
    @Test
    void relationValuesAreClampedAndRoundTripThroughNbt()
    {
        final UUID factionId = UUID.randomUUID();
        final UUID otherFactionId = UUID.randomUUID();
        final UUID capitalId = UUID.randomUUID();
        final Faction faction = new Faction(factionId, "Greyholm", FactionType.CITY_STATE);
        faction.setCapitalColonyId(capitalId);
        faction.setRelation(otherFactionId, 250);
        faction.setTreasury(3_200L);
        faction.addPersonality("trader");

        final Faction loaded = Faction.load(faction.save());

        assertEquals(factionId, loaded.id());
        assertEquals("Greyholm", loaded.name());
        assertEquals(100, loaded.relations().get(otherFactionId));
        assertEquals(3_200L, loaded.treasury());
        assertTrue(loaded.settlementIds().contains(capitalId));
        assertTrue(loaded.personalities().contains("TRADER"));
    }

    @Test
    void blankNameIsRejected()
    {
        assertThrows(IllegalArgumentException.class,
            () -> new Faction(UUID.randomUUID(), "  ", FactionType.NEUTRAL));
    }
}
