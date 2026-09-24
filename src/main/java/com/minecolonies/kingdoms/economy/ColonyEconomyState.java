package com.minecolonies.kingdoms.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ColonyEconomyState
{
    private final EnumMap<EconomicResource, ResourceEconomy> resources = new EnumMap<>(EconomicResource.class);

    public ColonyEconomyState()
    {
        for (final EconomicResource resource : EconomicResource.values())
        {
            resources.put(resource, ResourceEconomy.empty());
        }
    }

    public ResourceEconomy resource(final EconomicResource resource)
    {
        return resources.get(Objects.requireNonNull(resource, "resource"));
    }

    public Map<EconomicResource, ResourceEconomy> resources()
    {
        return Collections.unmodifiableMap(resources);
    }

    public void set(final EconomicResource resource, final ResourceEconomy economy)
    {
        resources.put(Objects.requireNonNull(resource, "resource"), Objects.requireNonNull(economy, "economy"));
    }

    public void setStockpile(final EconomicResource resource, final long amount)
    {
        final ResourceEconomy current = resource(resource);
        set(resource, new ResourceEconomy(new ResourceStockpile(amount, current.stockpile().desiredReserve()), current.flow()));
    }

    public ColonyEconomySnapshot snapshot()
    {
        return new ColonyEconomySnapshot(resources);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag entries = new ListTag();
        resources.forEach((resource, economy) -> {
            final CompoundTag entry = new CompoundTag();
            entry.putString("resource", resource.name());
            entry.putLong("stockpile", economy.stockpile().amount());
            entry.putLong("desiredReserve", economy.stockpile().desiredReserve());
            entry.putDouble("productionPerDay", economy.flow().productionPerDay());
            entry.putDouble("consumptionPerDay", economy.flow().consumptionPerDay());
            entries.add(entry);
        });
        tag.put("resources", entries);
        return tag;
    }

    public static ColonyEconomyState load(final CompoundTag tag)
    {
        final ColonyEconomyState state = new ColonyEconomyState();
        tag.getList("resources", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            try
            {
                final EconomicResource resource = EconomicResource.valueOf(entry.getString("resource"));
                state.set(resource, new ResourceEconomy(
                    new ResourceStockpile(
                        Math.max(0L, entry.getLong("stockpile")),
                        Math.max(0L, entry.getLong("desiredReserve"))),
                    new ResourceFlow(
                        Math.max(0.0D, entry.getDouble("productionPerDay")),
                        Math.max(0.0D, entry.getDouble("consumptionPerDay")))));
            }
            catch (IllegalArgumentException ignored)
            {
                // Unknown resources are ignored to keep saves forward-compatible.
            }
        });
        return state;
    }
}
