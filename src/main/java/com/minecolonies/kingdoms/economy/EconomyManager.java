package com.minecolonies.kingdoms.economy;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.SimulationMode;

import java.util.Optional;

public final class EconomyManager
{
    public static final double MINECRAFT_DAY_TICKS = 24_000.0D;

    public EconomyUpdateResult update(
        final NPCColonyData colony,
        final long gameTime,
        final int intervalTicks,
        final Optional<ColonyEconomySnapshot> physicalSnapshot)
    {
        final long lastUpdate = colony.lastEconomyUpdate();
        if (lastUpdate > 0L && gameTime - lastUpdate < intervalTicks)
        {
            return EconomyUpdateResult.skipped();
        }

        ensureBaseline(colony);
        final boolean applyPhysical = colony.simulationMode() == SimulationMode.ACTIVE && physicalSnapshot.isPresent();
        if (applyPhysical)
        {
            applyObservedStockpiles(colony.economy(), physicalSnapshot.orElseThrow());
        }
        else
        {
            final long elapsedTicks = lastUpdate == 0L ? intervalTicks : Math.max(0L, gameTime - lastUpdate);
            simulateFlows(colony.economy(), elapsedTicks / MINECRAFT_DAY_TICKS);
        }

        colony.setLastEconomyUpdate(gameTime);
        return new EconomyUpdateResult(true, applyPhysical);
    }

    public long availableForExport(
        final NPCColonyData colony,
        final EconomicResource resource,
        final long committedAmount,
        final double safetyBufferPercent)
    {
        if (committedAmount < 0L || !Double.isFinite(safetyBufferPercent) || safetyBufferPercent < 0.0D)
        {
            throw new IllegalArgumentException("Export commitments and safety buffer must be non-negative");
        }
        final ResourceEconomy economy = colony.economy().resource(resource);
        if (economy.flow().surplusPerDay() <= 0.0D)
        {
            return 0L;
        }
        final long safetyBuffer = safeCeil(economy.stockpile().desiredReserve() * safetyBufferPercent / 100.0D);
        return subtractFloorZero(
            economy.stockpile().amount(),
            economy.stockpile().desiredReserve(),
            safetyBuffer,
            committedAmount);
    }

    public long importNeed(final NPCColonyData colony, final EconomicResource resource)
    {
        final ResourceEconomy economy = colony.economy().resource(resource);
        final long dailyDeficit = safeCeil(economy.flow().deficitPerDay());
        return Math.max(economy.stockpile().reserveShortage(), dailyDeficit);
    }

    public boolean withdrawForShipment(
        final NPCColonyData colony,
        final EconomicResource resource,
        final long amount)
    {
        if (amount <= 0L)
        {
            throw new IllegalArgumentException("Shipment amount must be positive");
        }
        final ResourceEconomy current = colony.economy().resource(resource);
        if (current.stockpile().amount() < amount)
        {
            return false;
        }
        setStockpile(colony, resource, current.stockpile().amount() - amount);
        return true;
    }

    public void depositShipment(
        final NPCColonyData colony,
        final EconomicResource resource,
        final long amount)
    {
        if (amount <= 0L)
        {
            throw new IllegalArgumentException("Shipment amount must be positive");
        }
        final long current = colony.economy().resource(resource).stockpile().amount();
        final long updated = current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
        setStockpile(colony, resource, updated);
    }

    public void setStockpile(final NPCColonyData colony, final EconomicResource resource, final long amount)
    {
        if (amount < 0L)
        {
            throw new IllegalArgumentException("Stockpile must be non-negative");
        }
        final ResourceEconomy current = colony.economy().resource(resource);
        colony.economy().set(resource, new ResourceEconomy(
            new ResourceStockpile(amount, current.stockpile().desiredReserve()),
            current.flow()));
    }

    public void setDesiredReserve(final NPCColonyData colony, final EconomicResource resource, final long reserve)
    {
        if (reserve < 0L)
        {
            throw new IllegalArgumentException("Reserve must be non-negative");
        }
        final ResourceEconomy current = colony.economy().resource(resource);
        colony.economy().set(resource, new ResourceEconomy(
            new ResourceStockpile(current.stockpile().amount(), reserve),
            current.flow()));
    }

    public void setProduction(final NPCColonyData colony, final EconomicResource resource, final double production)
    {
        final ResourceEconomy current = colony.economy().resource(resource);
        colony.economy().set(resource, new ResourceEconomy(
            current.stockpile(),
            new ResourceFlow(production, current.flow().consumptionPerDay())));
    }

    public void setConsumption(final NPCColonyData colony, final EconomicResource resource, final double consumption)
    {
        final ResourceEconomy current = colony.economy().resource(resource);
        colony.economy().set(resource, new ResourceEconomy(
            current.stockpile(),
            new ResourceFlow(current.flow().productionPerDay(), consumption)));
    }

    public void adjustProduction(final NPCColonyData colony, final EconomicResource resource, final double delta)
    {
        if (!Double.isFinite(delta))
        {
            throw new IllegalArgumentException("Production adjustment must be finite");
        }
        final ResourceEconomy current = colony.economy().resource(resource);
        final double updated = Math.max(0.0D, current.flow().productionPerDay() + delta);
        setProduction(colony, resource, updated);
    }

    private static void ensureBaseline(final NPCColonyData colony)
    {
        final int population = colony.population();
        final int workers = colony.workers();
        setBaseline(colony, EconomicResource.FOOD, population * 20L, population * 2.0D);
        setBaseline(colony, EconomicResource.WOOD, population * 8L, population * 0.25D);
        setBaseline(colony, EconomicResource.STONE, population * 8L, population * 0.20D);
        setBaseline(colony, EconomicResource.IRON, population * 2L, population * 0.08D);
        setBaseline(colony, EconomicResource.TOOLS, workers, workers * 0.02D);
    }

    private static void setBaseline(
        final NPCColonyData colony,
        final EconomicResource resource,
        final long desiredReserve,
        final double defaultConsumption)
    {
        final ResourceEconomy current = colony.economy().resource(resource);
        final long reserve = Math.max(current.stockpile().desiredReserve(), desiredReserve);
        final double consumption = current.flow().consumptionPerDay() == 0.0D
            ? defaultConsumption
            : current.flow().consumptionPerDay();
        colony.economy().set(resource, new ResourceEconomy(
            new ResourceStockpile(current.stockpile().amount(), reserve),
            new ResourceFlow(current.flow().productionPerDay(), consumption)));
    }

    private static void applyObservedStockpiles(final ColonyEconomyState state, final ColonyEconomySnapshot snapshot)
    {
        for (final EconomicResource resource : EconomicResource.values())
        {
            final ResourceEconomy current = state.resource(resource);
            final ResourceEconomy observed = snapshot.resource(resource);
            state.set(resource, new ResourceEconomy(
                new ResourceStockpile(observed.stockpile().amount(), current.stockpile().desiredReserve()),
                current.flow()));
        }
    }

    private static void simulateFlows(final ColonyEconomyState state, final double elapsedDays)
    {
        for (final EconomicResource resource : EconomicResource.values())
        {
            final ResourceEconomy current = state.resource(resource);
            final long delta = Math.round(current.flow().netFlowPerDay() * elapsedDays);
            final long updatedAmount = Math.max(0L, current.stockpile().amount() + delta);
            state.set(resource, new ResourceEconomy(
                new ResourceStockpile(updatedAmount, current.stockpile().desiredReserve()),
                current.flow()));
        }
    }

    private static long safeCeil(final double value)
    {
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, (long) Math.ceil(value));
    }

    private static long subtractFloorZero(final long initial, final long... deductions)
    {
        long result = initial;
        for (final long deduction : deductions)
        {
            if (deduction >= result)
            {
                return 0L;
            }
            result -= deduction;
        }
        return result;
    }
}
