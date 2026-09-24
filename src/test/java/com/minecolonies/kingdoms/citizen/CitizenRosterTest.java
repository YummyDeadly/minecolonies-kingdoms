package com.minecolonies.kingdoms.citizen;

import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CitizenRosterTest
{
    private static final UUID SETTLEMENT = UUID.nameUUIDFromBytes("roster-settlement".getBytes());

    private static RosterPlanner.Building building(final String name, final SettlementBuildingType type, final int sequence)
    {
        return new RosterPlanner.Building(UUID.nameUUIDFromBytes(name.getBytes()), type, sequence);
    }

    private static List<RosterPlanner.Building> town()
    {
        return List.of(building("civic", SettlementBuildingType.CIVIC, 0), building("house-a", SettlementBuildingType.HOUSE, 1),
            building("store", SettlementBuildingType.STOREHOUSE, 2), building("farm", SettlementBuildingType.FARM, 3),
            building("house-b", SettlementBuildingType.HOUSE, 4), building("smithy", SettlementBuildingType.SMITHY, 5),
            building("market", SettlementBuildingType.MARKET, 6));
    }

    @Test
    void rosterIsDeterministicBoundedAndNeverLargerThanThePopulation()
    {
        final var first = RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 37, town(), List.of(), 16);
        final var second = RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 37, new ArrayList<>(town()).reversed(), List.of(), 16);
        assertEquals(first, second, "insertion order of buildings does not matter");
        assertTrue(first.size() <= 16);
        assertEquals(first.size(), first.stream().map(SettlementRepresentative::id).distinct().count());
        assertEquals(2, RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 2, town(), List.of(), 16).size());
        assertTrue(RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 0, town(), List.of(), 16).isEmpty());
        assertEquals(3, RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 50, town(), List.of(), 3).size());
    }

    @Test
    void rolesComeFromCompletedBuildingsAndWorkplacesHaveTheRightType()
    {
        final var buildings = town();
        final Map<UUID, SettlementBuildingType> types = new HashMap<>();
        buildings.forEach(value -> types.put(value.id(), value.type()));
        final var roster = RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 60, buildings, List.of(), 16);
        final Set<CitizenRole> roles = roster.stream().map(SettlementRepresentative::role).collect(Collectors.toSet());
        assertTrue(roles.containsAll(Set.of(CitizenRole.OFFICIAL, CitizenRole.PORTER, CitizenRole.FARMER, CitizenRole.SMITH,
            CitizenRole.MERCHANT, CitizenRole.RESIDENT)));
        assertFalse(roles.contains(CitizenRole.LUMBERJACK), "no lumber yard, so no lumberjack");
        assertFalse(roles.contains(CitizenRole.GUARD), "guards only in castles and forts");
        for (final SettlementRepresentative value : roster)
        {
            if (value.role().works()) assertEquals(value.role().workplace(), types.get(value.workBuildingId()));
            else assertNull(value.workBuildingId());
            if (value.homeBuildingId() != null) assertEquals(SettlementBuildingType.HOUSE, types.get(value.homeBuildingId()));
        }
        final Map<UUID, Long> perHouse = roster.stream().filter(value -> value.homeBuildingId() != null)
            .collect(Collectors.groupingBy(SettlementRepresentative::homeBuildingId, Collectors.counting()));
        assertTrue(perHouse.values().stream().allMatch(count -> count <= RosterPlanner.HOUSE_CAPACITY));
        assertTrue(RosterPlanner.plan(SETTLEMENT, SettlementType.CASTLE, 60, buildings, List.of(), 16).stream()
            .anyMatch(value -> value.role() == CitizenRole.GUARD));
    }

    @Test
    void reconciliationKeepsIdentitiesAndDropsAssignmentsOfRemovedBuildings()
    {
        final var buildings = town();
        final var before = RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 40, buildings, List.of(), 16);
        final var again = RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 40, buildings, before, 16);
        assertEquals(before, again, "reconciling with unchanged state is idempotent");
        final UUID smithy = UUID.nameUUIDFromBytes("smithy".getBytes());
        final UUID houseB = UUID.nameUUIDFromBytes("house-b".getBytes());
        final var withoutSmithyAndHouse = buildings.stream()
            .filter(value -> !value.id().equals(smithy) && !value.id().equals(houseB)).toList();
        final var after = RosterPlanner.plan(SETTLEMENT, SettlementType.TOWN, 40, withoutSmithyAndHouse, before, 16);
        assertTrue(after.stream().noneMatch(value -> smithy.equals(value.workBuildingId())), "smith slot invalidated");
        assertTrue(after.stream().noneMatch(value -> houseB.equals(value.homeBuildingId())), "home reassigned");
        final Map<String, SettlementRepresentative> previous = new HashMap<>();
        before.forEach(value -> previous.put(value.slotKey(), value));
        for (final SettlementRepresentative value : after)
        {
            final SettlementRepresentative old = previous.get(value.slotKey());
            if (old == null) continue;
            assertEquals(old.id(), value.id());
            assertEquals(old.name(), value.name());
            assertEquals(old.cosmeticSeed(), value.cosmeticSeed());
        }
    }

    @Test
    void identitiesAreStableEvenWithoutPersistence()
    {
        final var planned = RosterPlanner.plan(SETTLEMENT, SettlementType.VILLAGE, 20, town(), List.of(), 10);
        final var recomputed = RosterPlanner.plan(SETTLEMENT, SettlementType.VILLAGE, 20, town(), List.of(), 10);
        assertEquals(planned, recomputed);
        assertTrue(planned.stream().allMatch(value -> !value.name().isBlank()));
    }
}
