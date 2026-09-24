package com.minecolonies.kingdoms.world.settlement.template;

import com.minecolonies.kingdoms.world.settlement.layout.LayoutPlanningDiagnostics;

import java.util.Objects;

public record SettlementDistrictPlanningResult(SettlementDistrictPlan plan, String blocker,
    LayoutPlanningDiagnostics diagnostics)
{
    public SettlementDistrictPlanningResult
    {
        blocker = blocker == null ? "" : blocker;
        diagnostics = Objects.requireNonNullElse(diagnostics, LayoutPlanningDiagnostics.empty());
    }

    public static SettlementDistrictPlanningResult accepted(final SettlementDistrictPlan plan)
    {
        return new SettlementDistrictPlanningResult(plan, "", plan.layout().diagnostics());
    }

    public static SettlementDistrictPlanningResult rejected(final String blocker)
    {
        return new SettlementDistrictPlanningResult(null, blocker, LayoutPlanningDiagnostics.empty());
    }

    public static SettlementDistrictPlanningResult rejected(final String blocker, final LayoutPlanningDiagnostics diagnostics)
    {
        return new SettlementDistrictPlanningResult(null, blocker, diagnostics);
    }

    public boolean accepted() { return plan != null; }

    /** True when rejection was caused by terrain the sampler could not see, rather than by the terrain itself. */
    public boolean unloadedTerrain()
    {
        return plan == null && (blocker.contains("UNLOADED_TERRAIN")
            || diagnostics.rejections().getOrDefault(
                com.minecolonies.kingdoms.world.settlement.layout.LayoutRejectionReason.UNLOADED_TERRAIN, 0) > 0);
    }
}
