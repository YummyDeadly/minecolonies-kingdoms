package com.minecolonies.kingdoms.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Faction
{
    private static final int MIN_RELATION = -100;
    private static final int MAX_RELATION = 100;

    private final UUID id;
    private String name;
    private final FactionType type;
    private UUID leaderId;
    private UUID capitalColonyId;
    private final Set<UUID> settlementIds = new LinkedHashSet<>();
    private final Set<UUID> outpostIds = new LinkedHashSet<>();
    private final Map<UUID, Integer> relations = new LinkedHashMap<>();
    private final Set<String> personalities = new LinkedHashSet<>();
    private long treasury;
    private double militaryStrength;
    private double economicStrength;

    public Faction(final UUID id, final String name, final FactionType type)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.name = requireName(name);
        this.type = Objects.requireNonNull(type, "type");
    }

    public UUID id()
    {
        return id;
    }

    public String name()
    {
        return name;
    }

    public void rename(final String name)
    {
        this.name = requireName(name);
    }

    public FactionType type()
    {
        return type;
    }

    public UUID leaderId()
    {
        return leaderId;
    }

    public void setLeaderId(final UUID leaderId)
    {
        this.leaderId = leaderId;
    }

    public UUID capitalColonyId()
    {
        return capitalColonyId;
    }

    public void setCapitalColonyId(final UUID capitalColonyId)
    {
        this.capitalColonyId = capitalColonyId;
        if (capitalColonyId != null)
        {
            settlementIds.add(capitalColonyId);
        }
    }

    public Set<UUID> settlementIds()
    {
        return Collections.unmodifiableSet(settlementIds);
    }

    public void addSettlement(final UUID settlementId)
    {
        settlementIds.add(Objects.requireNonNull(settlementId, "settlementId"));
    }

    public void removeSettlement(final UUID settlementId)
    {
        settlementIds.remove(Objects.requireNonNull(settlementId, "settlementId"));
        if (settlementId.equals(capitalColonyId))
        {
            capitalColonyId = null;
        }
    }

    public Set<UUID> outpostIds()
    {
        return Collections.unmodifiableSet(outpostIds);
    }

    public void addOutpost(final UUID outpostId)
    {
        outpostIds.add(Objects.requireNonNull(outpostId, "outpostId"));
    }

    public long treasury()
    {
        return treasury;
    }

    public void setTreasury(final long treasury)
    {
        this.treasury = treasury;
    }

    /**
     * Relation values towards other factions (-100..100). Since Phase 7 the only writer is
     * {@code DiplomacyService}, which keeps both directions equal and logs every change. Player reputation is
     * a separate domain ({@code ReputationRegistry}); the Phase 1 "reputation" map was removed in schema 11.
     */
    public Map<UUID, Integer> relations()
    {
        return Collections.unmodifiableMap(relations);
    }

    public void setRelation(final UUID factionId, final int value)
    {
        relations.put(Objects.requireNonNull(factionId, "factionId"), clampRelation(value));
    }

    public double militaryStrength()
    {
        return militaryStrength;
    }

    public void setMilitaryStrength(final double militaryStrength)
    {
        this.militaryStrength = requireNonNegative(militaryStrength, "militaryStrength");
    }

    public double economicStrength()
    {
        return economicStrength;
    }

    public void setEconomicStrength(final double economicStrength)
    {
        this.economicStrength = requireNonNegative(economicStrength, "economicStrength");
    }

    public Set<String> personalities()
    {
        return Collections.unmodifiableSet(personalities);
    }

    public void addPersonality(final String personality)
    {
        personalities.add(requireName(personality).toUpperCase(java.util.Locale.ROOT));
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putString("type", type.name());
        putOptionalUuid(tag, "leader", leaderId);
        putOptionalUuid(tag, "capital", capitalColonyId);
        tag.put("settlements", writeUuidSet(settlementIds));
        tag.put("outposts", writeUuidSet(outpostIds));
        tag.put("relations", writeIntMap(relations));
        final ListTag personalityTags = new ListTag();
        personalities.forEach(value -> personalityTags.add(StringTag.valueOf(value)));
        tag.put("personalities", personalityTags);
        tag.putLong("treasury", treasury);
        tag.putDouble("militaryStrength", militaryStrength);
        tag.putDouble("economicStrength", economicStrength);
        return tag;
    }

    public static Faction load(final CompoundTag tag)
    {
        final Faction faction = new Faction(tag.getUUID("id"), tag.getString("name"),
            FactionType.valueOf(tag.getString("type")));
        faction.leaderId = getOptionalUuid(tag, "leader");
        faction.capitalColonyId = getOptionalUuid(tag, "capital");
        readUuidSet(tag.getList("settlements", Tag.TAG_COMPOUND), faction.settlementIds);
        readUuidSet(tag.getList("outposts", Tag.TAG_COMPOUND), faction.outpostIds);
        readIntMap(tag.getList("relations", Tag.TAG_COMPOUND), faction.relations);
        tag.getList("personalities", Tag.TAG_STRING).forEach(value -> faction.personalities.add(value.getAsString()));
        faction.treasury = tag.getLong("treasury");
        faction.militaryStrength = requireNonNegative(tag.getDouble("militaryStrength"), "militaryStrength");
        faction.economicStrength = requireNonNegative(tag.getDouble("economicStrength"), "economicStrength");
        return faction;
    }

    private static ListTag writeUuidSet(final Set<UUID> values)
    {
        final ListTag list = new ListTag();
        values.forEach(value -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", value);
            list.add(entry);
        });
        return list;
    }

    private static void readUuidSet(final ListTag list, final Set<UUID> destination)
    {
        list.forEach(value -> destination.add(((CompoundTag) value).getUUID("id")));
    }

    private static ListTag writeIntMap(final Map<UUID, Integer> values)
    {
        final ListTag list = new ListTag();
        values.forEach((id, value) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            entry.putInt("value", value);
            list.add(entry);
        });
        return list;
    }

    private static void readIntMap(final ListTag list, final Map<UUID, Integer> destination)
    {
        list.forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            destination.put(entry.getUUID("id"), clampRelation(entry.getInt("value")));
        });
    }

    private static void putOptionalUuid(final CompoundTag tag, final String key, final UUID value)
    {
        if (value != null)
        {
            tag.putUUID(key, value);
        }
    }

    private static UUID getOptionalUuid(final CompoundTag tag, final String key)
    {
        return tag.hasUUID(key) ? tag.getUUID(key) : null;
    }

    private static String requireName(final String value)
    {
        final String trimmed = Objects.requireNonNull(value, "value").trim();
        if (trimmed.isEmpty())
        {
            throw new IllegalArgumentException("Name must not be blank");
        }
        return trimmed;
    }

    private static int clampRelation(final int value)
    {
        return Math.max(MIN_RELATION, Math.min(MAX_RELATION, value));
    }

    private static double requireNonNegative(final double value, final String field)
    {
        if (!Double.isFinite(value) || value < 0.0D)
        {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
        return value;
    }
}
