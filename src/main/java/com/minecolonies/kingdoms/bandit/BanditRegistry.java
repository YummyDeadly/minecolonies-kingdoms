package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.KingdomsMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted bandit state (schema 12, camps since schema 13): per-road threat, encounters, one assessment decision per
 * in-transit shipment (so a shipment is judged once and a restart cannot re-roll it), and bandit camps. Physical
 * entities are never persisted.
 */
public final class BanditRegistry
{
    private final Map<UUID, RoadThreat> threats = new LinkedHashMap<>();
    private final Map<UUID, BanditEncounter> encounters = new LinkedHashMap<>();
    private final Map<UUID, Long> assessed = new LinkedHashMap<>();
    private final Map<UUID, BanditCamp> camps = new LinkedHashMap<>();
    private long lastEvaluatedAt = -1L;

    /** Ended camps kept for diagnostics (those with placed blocks are always kept until the blocks are removed). */
    public static final int MAX_ENDED_CAMPS = 64;

    public long lastEvaluatedAt() { return lastEvaluatedAt; }
    void setLastEvaluatedAt(final long gameTime) { lastEvaluatedAt = gameTime; }

    public Collection<RoadThreat> threats() { return Collections.unmodifiableCollection(threats.values()); }
    public Optional<RoadThreat> threat(final UUID roadId) { return Optional.ofNullable(threats.get(roadId)); }
    RoadThreat threatFor(final UUID roadId) { return threats.computeIfAbsent(roadId, RoadThreat::new); }
    public double threatOf(final UUID roadId) { return threat(roadId).map(RoadThreat::threat).orElse(0.0D); }

    public Collection<BanditEncounter> encounters() { return Collections.unmodifiableCollection(encounters.values()); }
    public Optional<BanditEncounter> encounter(final UUID id) { return Optional.ofNullable(encounters.get(id)); }
    void put(final BanditEncounter encounter) { encounters.put(encounter.id(), encounter); }

    public List<BanditEncounter> open()
    {
        return encounters.values().stream().filter(BanditEncounter::open).toList();
    }

    /** The open ambush of a shipment, if any (at most one per shipment by construction). */
    public Optional<BanditEncounter> openForShipment(final UUID shipmentId)
    {
        return encounters.values().stream().filter(value -> value.open() && shipmentId.equals(value.shipmentId())).findFirst();
    }

    public Optional<BanditEncounter> activeForShipment(final UUID shipmentId)
    {
        return openForShipment(shipmentId).filter(value -> value.status() == BanditEncounter.Status.ACTIVE);
    }

    public boolean openRoadblockOn(final UUID roadId)
    {
        return encounters.values().stream().anyMatch(value -> value.open() && value.kind() == BanditEncounter.Kind.ROADBLOCK
            && value.roadId().equals(roadId));
    }

    public Collection<BanditCamp> camps() { return Collections.unmodifiableCollection(camps.values()); }
    public Optional<BanditCamp> camp(final UUID id) { return Optional.ofNullable(camps.get(id)); }
    void put(final BanditCamp camp) { camps.put(camp.id(), camp); }
    public List<BanditCamp> activeCamps() { return camps.values().stream().filter(BanditCamp::active).toList(); }
    public Optional<BanditCamp> activeCampOn(final UUID roadId)
    {
        return camps.values().stream().filter(value -> value.active() && value.roadId().equals(roadId)).findFirst();
    }
    public Optional<BanditCamp> campByEncounter(final UUID encounterId)
    {
        return camps.values().stream().filter(value -> value.encounterId().equals(encounterId)).findFirst();
    }

    public boolean assessed(final UUID shipmentId) { return assessed.containsKey(shipmentId); }
    void markAssessed(final UUID shipmentId, final long gameTime) { assessed.putIfAbsent(shipmentId, gameTime); }
    public int assessments() { return assessed.size(); }

    /**
     * Drops assessment markers of shipments that are no longer in transit, threat records of vanished roads, and
     * resolved encounters older than the retention (keeping at most {@code maxHistory}).
     */
    public int prune(final Set<UUID> inTransit, final Set<UUID> roads, final long gameTime, final long retention, final int maxHistory)
    {
        final int before = encounters.size() + assessed.size() + threats.size() + camps.size();
        assessed.keySet().retainAll(inTransit);
        threats.keySet().retainAll(roads);
        encounters.values().removeIf(value -> value.status().terminal() && gameTime - value.resolvedAt() > retention);
        final List<BanditEncounter> closed = new ArrayList<>(encounters.values().stream().filter(value -> value.status().terminal()).toList());
        if (closed.size() > maxHistory)
        {
            closed.sort(Comparator.comparingLong(BanditEncounter::resolvedAt));
            closed.subList(0, closed.size() - maxHistory).forEach(value -> encounters.remove(value.id()));
        }
        // an ended camp stays while its blocks are in the world (so they can be taken down) and while its encounter is kept
        final List<BanditCamp> ended = new ArrayList<>(camps.values().stream()
            .filter(value -> !value.active() && value.structure() != BanditCamp.Structure.BUILT && !encounters.containsKey(value.encounterId()))
            .toList());
        ended.forEach(value -> camps.remove(value.id()));
        // bounded history: beyond the cap the oldest ended camps are forgotten; blocks of one that was never taken down
        // (nobody came near again) simply stay as a harmless abandoned camp
        final List<BanditCamp> kept = new ArrayList<>(camps.values().stream().filter(value -> !value.active()).toList());
        if (kept.size() > MAX_ENDED_CAMPS)
        {
            kept.sort(Comparator.comparingLong(BanditCamp::endedAt));
            kept.subList(0, kept.size() - MAX_ENDED_CAMPS).forEach(value -> camps.remove(value.id()));
        }
        return before - (encounters.size() + assessed.size() + threats.size() + camps.size());
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag threatList = new ListTag();
        threats.values().forEach(value -> threatList.add(value.save()));
        tag.put("threats", threatList);
        final ListTag encounterList = new ListTag();
        encounters.values().forEach(value -> encounterList.add(value.save()));
        tag.put("encounters", encounterList);
        final ListTag assessedList = new ListTag();
        assessed.forEach((shipment, time) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("shipment", shipment);
            entry.putLong("time", time);
            assessedList.add(entry);
        });
        tag.put("assessed", assessedList);
        final ListTag campList = new ListTag();
        camps.values().forEach(value -> campList.add(value.save()));
        tag.put("camps", campList);
        tag.putLong("lastEvaluatedAt", lastEvaluatedAt);
        return tag;
    }

    public static BanditRegistry load(final CompoundTag tag)
    {
        final BanditRegistry registry = new BanditRegistry();
        tag.getList("threats", Tag.TAG_COMPOUND).forEach(value -> {
            final RoadThreat threat = RoadThreat.load((CompoundTag) value);
            registry.threats.put(threat.roadId(), threat);
        });
        tag.getList("encounters", Tag.TAG_COMPOUND).forEach(value -> {
            try
            {
                final BanditEncounter encounter = BanditEncounter.load((CompoundTag) value);
                registry.encounters.put(encounter.id(), encounter);
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Dropping unreadable bandit encounter {}", value, exception);
            }
        });
        tag.getList("assessed", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            if (entry.hasUUID("shipment")) registry.assessed.put(entry.getUUID("shipment"), entry.getLong("time"));
        });
        tag.getList("camps", Tag.TAG_COMPOUND).forEach(value -> {
            try
            {
                final BanditCamp camp = BanditCamp.load((CompoundTag) value);
                registry.camps.put(camp.id(), camp);
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Dropping unreadable bandit camp {}", value, exception);
            }
        });
        registry.lastEvaluatedAt = tag.contains("lastEvaluatedAt") ? tag.getLong("lastEvaluatedAt") : -1L;
        return registry;
    }
}
