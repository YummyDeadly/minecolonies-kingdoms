package com.minecolonies.kingdoms.economy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record ColonyEconomySnapshot(Map<EconomicResource, ResourceEconomy> resources)
{
    public ColonyEconomySnapshot
    {
        final EnumMap<EconomicResource, ResourceEconomy> copy = new EnumMap<>(EconomicResource.class);
        copy.putAll(resources);
        resources = Collections.unmodifiableMap(copy);
    }

    public ResourceEconomy resource(final EconomicResource resource)
    {
        return resources.getOrDefault(resource, ResourceEconomy.empty());
    }
}
