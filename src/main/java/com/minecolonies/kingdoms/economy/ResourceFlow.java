package com.minecolonies.kingdoms.economy;

public record ResourceFlow(double productionPerDay, double consumptionPerDay)
{
    public ResourceFlow
    {
        requireNonNegative(productionPerDay, "productionPerDay");
        requireNonNegative(consumptionPerDay, "consumptionPerDay");
    }

    public double netFlowPerDay()
    {
        return productionPerDay - consumptionPerDay;
    }

    public double surplusPerDay()
    {
        return Math.max(0.0D, netFlowPerDay());
    }

    public double deficitPerDay()
    {
        return Math.max(0.0D, -netFlowPerDay());
    }

    private static void requireNonNegative(final double value, final String field)
    {
        if (!Double.isFinite(value) || value < 0.0D)
        {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }
}
