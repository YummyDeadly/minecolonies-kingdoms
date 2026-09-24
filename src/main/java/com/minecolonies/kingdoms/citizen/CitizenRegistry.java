package com.minecolonies.kingdoms.citizen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted, bounded representative rosters per settlement. Physical entities, activities, routes, and entity UUIDs
 * are deliberately not stored here.
 */
public final class CitizenRegistry
{
    private final Map<UUID, List<SettlementRepresentative>> rosters = new LinkedHashMap<>();

    public List<SettlementRepresentative> roster(final UUID settlementId)
    {
        return rosters.getOrDefault(settlementId, List.of());
    }

    public void put(final UUID settlementId, final List<SettlementRepresentative> roster)
    {
        if (roster.isEmpty()) rosters.remove(settlementId);
        else rosters.put(settlementId, List.copyOf(roster));
    }

    public Collection<UUID> settlements() { return List.copyOf(rosters.keySet()); }

    public int representativeCount() { return rosters.values().stream().mapToInt(List::size).sum(); }

    public Optional<SettlementRepresentative> find(final UUID representativeId)
    {
        return rosters.values().stream().flatMap(List::stream).filter(value -> value.id().equals(representativeId)).findFirst();
    }

    /** Drops rosters of settlements that no longer exist (or are no longer NPC_ABSTRACT); returns the dropped ids. */
    public Set<UUID> retain(final Set<UUID> settlementIds)
    {
        final Set<UUID> removed = new java.util.LinkedHashSet<>();
        rosters.keySet().removeIf(id -> {
            if (settlementIds.contains(id)) return false;
            removed.add(id);
            return true;
        });
        return removed;
    }

    public CompoundTag save()
    {
        final CompoundTag root = new CompoundTag();
        final ListTag list = new ListTag();
        rosters.forEach((settlement, roster) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("settlement", settlement);
            final ListTag people = new ListTag();
            roster.forEach(value -> people.add(value.save()));
            entry.put("representatives", people);
            list.add(entry);
        });
        root.put("rosters", list);
        return root;
    }

    public static CitizenRegistry load(final CompoundTag root)
    {
        final CitizenRegistry registry = new CitizenRegistry();
        root.getList("rosters", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            final List<SettlementRepresentative> people = new java.util.ArrayList<>();
            entry.getList("representatives", Tag.TAG_COMPOUND).forEach(person -> {
                try { people.add(SettlementRepresentative.load((CompoundTag) person)); }
                catch (IllegalArgumentException ignored) { /* invalid entry: the planner rebuilds the slot */ }
            });
            registry.put(entry.getUUID("settlement"), people);
        });
        return registry;
    }
}
