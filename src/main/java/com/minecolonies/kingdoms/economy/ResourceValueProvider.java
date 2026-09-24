package com.minecolonies.kingdoms.economy;

import java.util.Map;

@FunctionalInterface
public interface ResourceValueProvider<T>
{
    Map<EconomicResource, Double> values(T value);
}
