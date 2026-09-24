package com.minecolonies.kingdoms.world.settlement.growth;

import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.world.settlement.layout.LayoutPlanningDiagnostics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SettlementGrowthState
{
    private final UUID settlementId;
    private SettlementGrowthStage stage = SettlementGrowthStage.STARTER;
    private int nextSequence;
    private long lastEvaluationAt;
    private long lastPopulationEvaluationAt;
    private String blocker;
    private String lastDecision = "not evaluated";
    private int appliedHousing;
    private int appliedStorage;
    private final EnumMap<EconomicResource, Double> appliedProduction = new EnumMap<>(EconomicResource.class);
    private StarterDistrictStatus starterDistrictStatus = StarterDistrictStatus.UNPLANNED;
    private int starterPlannerVersion;
    private LayoutPlanningDiagnostics starterDiagnostics = LayoutPlanningDiagnostics.empty();

    public SettlementGrowthState(final UUID settlementId)
    {
        this.settlementId = Objects.requireNonNull(settlementId);
    }

    public UUID settlementId() { return settlementId; }
    public SettlementGrowthStage stage() { return stage; }
    public int nextSequence() { return nextSequence; }
    public long lastEvaluationAt() { return lastEvaluationAt; }
    public long lastPopulationEvaluationAt() { return lastPopulationEvaluationAt; }
    public String blocker() { return blocker; }
    public String lastDecision() { return lastDecision; }
    public int appliedHousing() { return appliedHousing; }
    public int appliedStorage() { return appliedStorage; }
    public double appliedProduction(final EconomicResource resource) { return appliedProduction.getOrDefault(resource, 0.0D); }
    public Map<EconomicResource, Double> appliedProduction() { return Map.copyOf(appliedProduction); }
    public StarterDistrictStatus starterDistrictStatus() { return starterDistrictStatus; }
    public int starterPlannerVersion() { return starterPlannerVersion; }
    public LayoutPlanningDiagnostics starterDiagnostics() { return starterDiagnostics; }
    public void starterDiagnostics(final LayoutPlanningDiagnostics value)
    {
        starterDiagnostics = Objects.requireNonNull(value);
    }
    /** Allows a starter that was blocked by an older planner implementation to be planned once more. */
    public boolean resetStarterIfPlannedBefore(final int currentPlannerVersion)
    {
        if (starterDistrictStatus != StarterDistrictStatus.BLOCKED || starterPlannerVersion >= currentPlannerVersion) return false;
        starterDistrictStatus = StarterDistrictStatus.UNPLANNED;
        blocker = null;
        lastDecision = "starter re-planning after planner upgrade";
        return true;
    }
    public int allocateSequence() { return nextSequence++; }
    public void stage(final SettlementGrowthStage value) { stage = Objects.requireNonNull(value); }
    public void evaluated(final long gameTime) { lastEvaluationAt = Math.max(0L, gameTime); }
    public void populationEvaluated(final long gameTime) { lastPopulationEvaluationAt = Math.max(0L, gameTime); }
    public void decision(final String value) { lastDecision = requireText(value); blocker = null; }
    public void blocked(final String value) { blocker = requireText(value); lastDecision = blocker; }
    public void starterDistrictReady(final int plannerVersion)
    {
        starterDistrictStatus = StarterDistrictStatus.READY;
        starterPlannerVersion = plannerVersion;
    }
    public void starterDistrictBlocked(final String value, final int plannerVersion)
    {
        starterDistrictStatus = StarterDistrictStatus.BLOCKED;
        starterPlannerVersion = plannerVersion;
        blocked(value);
    }
    public void appliedEffects(final int housing, final int storage, final Map<EconomicResource, Double> production)
    {
        appliedHousing = Math.max(0, housing);
        appliedStorage = Math.max(0, storage);
        appliedProduction.clear();
        appliedProduction.putAll(production);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("settlement", settlementId); tag.putString("stage", stage.name()); tag.putInt("nextSequence", nextSequence);
        tag.putLong("lastEvaluationAt", lastEvaluationAt); tag.putLong("lastPopulationEvaluationAt", lastPopulationEvaluationAt);
        tag.putString("lastDecision", lastDecision); if (blocker != null) tag.putString("blocker", blocker);
        tag.putString("starterDistrictStatus", starterDistrictStatus.name());
        tag.putInt("starterPlannerVersion", starterPlannerVersion);
        tag.put("starterDiagnostics", starterDiagnostics.save());
        tag.putInt("appliedHousing", appliedHousing); tag.putInt("appliedStorage", appliedStorage);
        final ListTag production = new ListTag();
        appliedProduction.forEach((resource, amount) -> { final CompoundTag entry = new CompoundTag(); entry.putString("resource", resource.name()); entry.putDouble("amount", amount); production.add(entry); });
        tag.put("appliedProduction", production);
        return tag;
    }

    public static SettlementGrowthState load(final CompoundTag tag)
    {
        final SettlementGrowthState state = new SettlementGrowthState(tag.getUUID("settlement"));
        final String stage = tag.getString("stage");
        state.stage = stage.isEmpty() ? SettlementGrowthStage.STARTER : SettlementGrowthStage.valueOf(stage);
        state.nextSequence = Math.max(0, tag.getInt("nextSequence"));
        state.lastEvaluationAt = Math.max(0L, tag.getLong("lastEvaluationAt"));
        state.lastPopulationEvaluationAt = Math.max(0L, tag.getLong("lastPopulationEvaluationAt"));
        state.lastDecision = tag.contains("lastDecision", Tag.TAG_STRING) ? tag.getString("lastDecision") : "not evaluated";
        state.blocker = tag.contains("blocker", Tag.TAG_STRING) ? tag.getString("blocker") : null;
        state.starterDistrictStatus = tag.contains("starterDistrictStatus", Tag.TAG_STRING)
            ? StarterDistrictStatus.valueOf(tag.getString("starterDistrictStatus")) : StarterDistrictStatus.UNPLANNED;
        state.starterPlannerVersion = Math.max(0, tag.getInt("starterPlannerVersion"));
        if (tag.contains("starterDiagnostics", Tag.TAG_COMPOUND))
            state.starterDiagnostics = LayoutPlanningDiagnostics.load(tag.getCompound("starterDiagnostics"));
        state.appliedHousing = Math.max(0, tag.getInt("appliedHousing"));
        state.appliedStorage = Math.max(0, tag.getInt("appliedStorage"));
        tag.getList("appliedProduction", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            state.appliedProduction.put(EconomicResource.valueOf(entry.getString("resource")), entry.getDouble("amount"));
        });
        return state;
    }

    private static String requireText(final String value)
    {
        final String checked = Objects.requireNonNull(value).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException("Text must not be blank");
        return checked;
    }
}
