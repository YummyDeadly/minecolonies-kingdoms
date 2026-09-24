package com.minecolonies.kingdoms.kingdom;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Kingdom
{
    private final UUID id;
    private final UUID factionId;
    private String name;
    private UUID capitalColonyId;
    private final Set<UUID> memberColonyIds = new LinkedHashSet<>();
    private final Set<UUID> outpostIds = new LinkedHashSet<>();
    private final Set<UUID> tradeRouteIds = new LinkedHashSet<>();
    private final Set<UUID> armyIds = new LinkedHashSet<>();
    private final Set<UUID> warIds = new LinkedHashSet<>();
    private final Set<UUID> allyFactionIds = new LinkedHashSet<>();
    private final Set<UUID> enemyFactionIds = new LinkedHashSet<>();
    private final Map<ResourceLocation, Long> resources = new LinkedHashMap<>();
    private final Map<String, String> policies = new LinkedHashMap<>();
    private long treasury;

    public Kingdom(final UUID id, final UUID factionId, final String name)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.factionId = Objects.requireNonNull(factionId, "factionId");
        this.name = requireText(name, "name");
    }

    public UUID id()
    {
        return id;
    }

    public UUID factionId()
    {
        return factionId;
    }

    public String name()
    {
        return name;
    }

    public void rename(final String name)
    {
        this.name = requireText(name, "name");
    }

    public UUID capitalColonyId()
    {
        return capitalColonyId;
    }

    public void setCapitalColonyId(final UUID colonyId)
    {
        this.capitalColonyId = colonyId;
        if (colonyId != null)
        {
            memberColonyIds.add(colonyId);
        }
    }

    public Set<UUID> memberColonyIds()
    {
        return Collections.unmodifiableSet(memberColonyIds);
    }

    public void addMemberColony(final UUID colonyId)
    {
        memberColonyIds.add(Objects.requireNonNull(colonyId, "colonyId"));
    }

    public Set<UUID> outpostIds()
    {
        return Collections.unmodifiableSet(outpostIds);
    }

    public Set<UUID> tradeRouteIds()
    {
        return Collections.unmodifiableSet(tradeRouteIds);
    }

    public Set<UUID> armyIds()
    {
        return Collections.unmodifiableSet(armyIds);
    }

    public Set<UUID> warIds()
    {
        return Collections.unmodifiableSet(warIds);
    }

    public Set<UUID> allyFactionIds()
    {
        return Collections.unmodifiableSet(allyFactionIds);
    }

    public Set<UUID> enemyFactionIds()
    {
        return Collections.unmodifiableSet(enemyFactionIds);
    }

    public long treasury()
    {
        return treasury;
    }

    public void setTreasury(final long treasury)
    {
        this.treasury = treasury;
    }

    public Map<ResourceLocation, Long> resources()
    {
        return Collections.unmodifiableMap(resources);
    }

    public void setResource(final ResourceLocation resource, final long amount)
    {
        if (amount < 0L)
        {
            throw new IllegalArgumentException("Resource amount must be non-negative");
        }
        resources.put(Objects.requireNonNull(resource, "resource"), amount);
    }

    public Map<String, String> policies()
    {
        return Collections.unmodifiableMap(policies);
    }

    public void setPolicy(final String key, final String value)
    {
        policies.put(requireText(key, "key"), requireText(value, "value"));
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("faction", factionId);
        tag.putString("name", name);
        if (capitalColonyId != null)
        {
            tag.putUUID("capital", capitalColonyId);
        }
        tag.put("memberColonies", writeUuidSet(memberColonyIds));
        tag.put("outposts", writeUuidSet(outpostIds));
        tag.put("tradeRoutes", writeUuidSet(tradeRouteIds));
        tag.put("armies", writeUuidSet(armyIds));
        tag.put("wars", writeUuidSet(warIds));
        tag.put("allies", writeUuidSet(allyFactionIds));
        tag.put("enemies", writeUuidSet(enemyFactionIds));
        tag.putLong("treasury", treasury);

        final ListTag resourceList = new ListTag();
        resources.forEach((resource, amount) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putString("resource", resource.toString());
            entry.putLong("amount", amount);
            resourceList.add(entry);
        });
        tag.put("resources", resourceList);

        final ListTag policyList = new ListTag();
        policies.forEach((key, value) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putString("key", key);
            entry.putString("value", value);
            policyList.add(entry);
        });
        tag.put("policies", policyList);
        return tag;
    }

    public static Kingdom load(final CompoundTag tag)
    {
        final Kingdom kingdom = new Kingdom(tag.getUUID("id"), tag.getUUID("faction"), tag.getString("name"));
        kingdom.capitalColonyId = tag.hasUUID("capital") ? tag.getUUID("capital") : null;
        readUuidSet(tag.getList("memberColonies", Tag.TAG_COMPOUND), kingdom.memberColonyIds);
        readUuidSet(tag.getList("outposts", Tag.TAG_COMPOUND), kingdom.outpostIds);
        readUuidSet(tag.getList("tradeRoutes", Tag.TAG_COMPOUND), kingdom.tradeRouteIds);
        readUuidSet(tag.getList("armies", Tag.TAG_COMPOUND), kingdom.armyIds);
        readUuidSet(tag.getList("wars", Tag.TAG_COMPOUND), kingdom.warIds);
        readUuidSet(tag.getList("allies", Tag.TAG_COMPOUND), kingdom.allyFactionIds);
        readUuidSet(tag.getList("enemies", Tag.TAG_COMPOUND), kingdom.enemyFactionIds);
        kingdom.treasury = tag.getLong("treasury");

        tag.getList("resources", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            final ResourceLocation resource = ResourceLocation.tryParse(entry.getString("resource"));
            if (resource != null)
            {
                kingdom.resources.put(resource, Math.max(0L, entry.getLong("amount")));
            }
        });
        tag.getList("policies", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            kingdom.policies.put(entry.getString("key"), entry.getString("value"));
        });
        return kingdom;
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

    private static String requireText(final String value, final String field)
    {
        final String trimmed = Objects.requireNonNull(value, field).trim();
        if (trimmed.isEmpty())
        {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
