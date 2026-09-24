package com.minecolonies.kingdoms.citizen;

import com.minecolonies.kingdoms.world.settlement.SettlementType;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure, deterministic roster planning. Roles come only from completed buildings; homes only from completed houses.
 * The roster never exceeds the configured cap or the logical population, and a representative keeps its identity
 * (id, name, cosmetics) and home for as long as its slot stays valid.
 */
public final class RosterPlanner
{
    public static final int HOUSE_CAPACITY = 4;

    /** A completed building as seen by the planner. */
    public record Building(UUID id, SettlementBuildingType type, int sequence)
    {
        public Building { Objects.requireNonNull(id); Objects.requireNonNull(type); }
    }

    private record Slot(CitizenRole role, UUID workBuildingId, int ordinal)
    {
        String key() { return role.name() + ':' + (workBuildingId == null ? "-" : workBuildingId.toString()) + ':' + ordinal; }
    }

    private RosterPlanner() {}

    public static List<SettlementRepresentative> plan(final UUID settlementId, final SettlementType settlementType,
        final int population, final List<Building> completed, final List<SettlementRepresentative> existing, final int cap)
    {
        final int size = Math.min(Math.max(0, cap), Math.max(0, population));
        if (size == 0) return List.of();
        final List<Building> buildings = completed.stream()
            .sorted(Comparator.comparingInt(Building::sequence).thenComparing(Building::id)).toList();
        // Slot priority: one worker per workplace first, then two residents, then the remaining slots.
        final List<Slot> first = new ArrayList<>();
        final List<Slot> rest = new ArrayList<>();
        for (final Building building : buildings)
            for (final CitizenRole role : CitizenRole.values())
            {
                if (role.workplace() != building.type()) continue;
                if (role == CitizenRole.GUARD && settlementType != SettlementType.CASTLE && settlementType != SettlementType.FORT) continue;
                for (int ordinal = 0; ordinal < role.slotsPerBuilding(); ordinal++)
                    (ordinal == 0 && role != CitizenRole.GUARD ? first : rest).add(new Slot(role, building.id(), ordinal));
            }
        final int residents = Math.max(2, Math.min(6, population / 6));
        final List<Slot> ordered = new ArrayList<>(first);
        for (int ordinal = 0; ordinal < Math.min(2, residents); ordinal++) ordered.add(new Slot(CitizenRole.RESIDENT, null, ordinal));
        ordered.addAll(rest);
        for (int ordinal = 2; ordinal < residents; ordinal++) ordered.add(new Slot(CitizenRole.RESIDENT, null, ordinal));

        final Map<String, SettlementRepresentative> previous = new HashMap<>();
        existing.forEach(value -> previous.put(value.slotKey(), value));
        final List<SettlementRepresentative> people = new ArrayList<>();
        for (final Slot slot : ordered.subList(0, Math.min(size, ordered.size())))
        {
            final SettlementRepresentative kept = previous.get(slot.key());
            if (kept != null) { people.add(kept); continue; }
            final UUID id = UUID.nameUUIDFromBytes(("kingdoms-representative:" + settlementId + ':' + slot.key())
                .getBytes(StandardCharsets.UTF_8));
            final boolean female = CitizenNames.female(id);
            people.add(new SettlementRepresentative(id, settlementId, slot.role(), slot.workBuildingId(), slot.ordinal(),
                null, CitizenNames.name(id, female), female, CitizenNames.cosmeticSeed(id)));
        }
        return assignHomes(people, buildings);
    }

    /** Houses only; an existing valid home is kept while the house has room, then houses fill in stable order. */
    static List<SettlementRepresentative> assignHomes(final List<SettlementRepresentative> people, final List<Building> buildings)
    {
        final Map<UUID, Integer> capacity = new LinkedHashMap<>();
        buildings.stream().filter(building -> building.type() == SettlementBuildingType.HOUSE)
            .forEach(building -> capacity.put(building.id(), HOUSE_CAPACITY));
        final SettlementRepresentative[] result = new SettlementRepresentative[people.size()];
        for (int index = 0; index < people.size(); index++)
        {
            final UUID home = people.get(index).homeBuildingId();
            if (home != null && capacity.getOrDefault(home, 0) > 0)
            {
                capacity.merge(home, -1, Integer::sum);
                result[index] = people.get(index);
            }
        }
        for (int index = 0; index < people.size(); index++)
        {
            if (result[index] != null) continue;
            final UUID free = capacity.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey).findFirst().orElse(null);
            if (free != null) capacity.merge(free, -1, Integer::sum);
            result[index] = people.get(index).withHome(free);
        }
        return List.of(result);
    }
}
