package com.minecolonies.kingdoms.contract;

import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.economy.EconomicResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pure contract terms, delivery planning, and treasury arithmetic. Amounts are strategic units as defined by the
 * item valuation (food = nutrition points, wood = planks with a log worth 4, stone = blocks, iron = ingots).
 */
public final class ContractRules
{
    public static final double TREASURY_INCOME_PER_CITIZEN_PER_DAY = 0.2D;
    public static final long DAY_TICKS = 24_000L;

    /** Size band, rounding step, price and a hint item for players. */
    public record Terms(long minimum, long maximum, long step, int unitsPerEmerald, String hintItem, int unitsPerHintItem) {}

    private static final Map<EconomicResource, Terms> TERMS = new EnumMap<>(EconomicResource.class);

    static
    {
        TERMS.put(EconomicResource.FOOD, new Terms(48, 192, 8, 24, "bread", 5));
        TERMS.put(EconomicResource.WOOD, new Terms(32, 160, 8, 32, "logs", 4));
        TERMS.put(EconomicResource.STONE, new Terms(64, 256, 16, 48, "cobblestone", 1));
        TERMS.put(EconomicResource.IRON, new Terms(8, 32, 2, 4, "iron ingots", 1));
    }

    private ContractRules() {}

    public static boolean contractable(final EconomicResource resource) { return TERMS.containsKey(resource); }
    public static Terms terms(final EconomicResource resource) { return TERMS.get(resource); }

    /** Half of the shortage, clamped to the band and rounded down to the step (never below the band minimum). */
    public static long amount(final EconomicResource resource, final double shortage)
    {
        final Terms terms = TERMS.get(resource);
        final long raw = Math.round(Math.max(0.0D, shortage) * 0.5D);
        final long clamped = Math.max(terms.minimum(), Math.min(terms.maximum(), raw));
        return Math.max(terms.minimum(), clamped / terms.step() * terms.step());
    }

    public static double severityMultiplier(final NeedSeverity severity)
    {
        return switch (severity)
        {
            case LOW, MEDIUM -> 1.0D;
            case HIGH -> 1.25D;
            case CRITICAL -> 1.5D;
        };
    }

    /** Base reward in emeralds before the player's reputation multiplier. */
    public static int reward(final EconomicResource resource, final long amount, final NeedSeverity severity)
    {
        final double base = Math.ceil((double) amount / TERMS.get(resource).unitsPerEmerald());
        return Math.max(1, (int) Math.round(base * severityMultiplier(severity)));
    }

    public static int reputationReward(final NeedSeverity severity)
    {
        return switch (severity)
        {
            case LOW, MEDIUM -> 3;
            case HIGH -> 5;
            case CRITICAL -> 7;
        };
    }

    public static int agreedReward(final int reward, final double reputationMultiplier)
    {
        return Math.max(1, (int) Math.round(reward * reputationMultiplier));
    }

    /** How many whole hint items cover an amount, for display only. */
    public static long hintItems(final EconomicResource resource, final long amount)
    {
        final Terms terms = TERMS.get(resource);
        return (amount + terms.unitsPerHintItem() - 1) / terms.unitsPerHintItem();
    }

    public static long treasuryCap(final int population)
    {
        return 64L + 2L * Math.max(0, population);
    }

    /** Whole emeralds earned by taxes over the elapsed time (never negative). */
    public static long treasuryIncome(final int population, final long elapsedTicks)
    {
        if (population <= 0 || elapsedTicks <= 0L) return 0L;
        return (long) Math.floor(population * TREASURY_INCOME_PER_CITIZEN_PER_DAY * elapsedTicks / DAY_TICKS);
    }

    /** Ticks that correspond to the given income, so fractional income is not lost between accruals. */
    public static long ticksFor(final int population, final long income)
    {
        return (long) Math.floor(income * DAY_TICKS / (population * TREASURY_INCOME_PER_CITIZEN_PER_DAY));
    }

    // ------------------------------------------------------------------------------------------------ delivery

    /** One inventory slot holding items worth {@code unitsPerItem} each (0 = not accepted). */
    public record Slot(int index, int count, long unitsPerItem) {}
    public record Removal(int index, int count) {}
    public record Plan(List<Removal> removals, long units) {}

    /**
     * Chooses items to hand over for {@code remaining} units with the smallest possible surplus: the most valuable
     * items that still fit first, then at most one extra item (the cheapest available) to finish. The surplus is
     * therefore smaller than one item.
     */
    public static Plan plan(final long remaining, final List<Slot> slots)
    {
        if (remaining <= 0L) return new Plan(List.of(), 0L);
        final List<Slot> usable = new ArrayList<>(slots.stream().filter(slot -> slot.count() > 0 && slot.unitsPerItem() > 0L).toList());
        usable.sort(Comparator.comparingLong(Slot::unitsPerItem).reversed().thenComparingInt(Slot::index));
        final int[] taken = new int[usable.size()];
        long needed = remaining;
        long units = 0L;
        for (int index = 0; index < usable.size() && needed > 0L; index++)
        {
            final Slot slot = usable.get(index);
            final int take = (int) Math.min(slot.count(), needed / slot.unitsPerItem());
            taken[index] = take;
            needed -= take * slot.unitsPerItem();
            units += take * slot.unitsPerItem();
        }
        if (needed > 0L)
        {
            int cheapest = -1;
            for (int index = 0; index < usable.size(); index++)
                if (taken[index] < usable.get(index).count()
                    && (cheapest < 0 || usable.get(index).unitsPerItem() < usable.get(cheapest).unitsPerItem())) cheapest = index;
            if (cheapest >= 0)
            {
                taken[cheapest]++;
                units += usable.get(cheapest).unitsPerItem();
            }
        }
        final List<Removal> removals = new ArrayList<>();
        for (int index = 0; index < usable.size(); index++)
            if (taken[index] > 0) removals.add(new Removal(usable.get(index).index(), taken[index]));
        removals.sort(Comparator.comparingInt(Removal::index));
        return new Plan(List.copyOf(removals), units);
    }
}
