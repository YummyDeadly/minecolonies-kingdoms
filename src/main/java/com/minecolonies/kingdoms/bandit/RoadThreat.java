package com.minecolonies.kingdoms.bandit;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * Strategic threat of one road edge (the coarse unit of danger; there is no per-block map). The last evaluation's
 * contributors are kept so diagnostics can explain the value.
 */
public final class RoadThreat
{
    private final UUID roadId;
    private double threat;
    private double recentTraffic;
    private double raidMomentum;
    private long updatedAt = -1L;
    private long suppressedUntil = Long.MIN_VALUE;
    private long cooldownUntil = Long.MIN_VALUE;
    private int raids;
    private int defeats;
    private int roadblocks;
    private ThreatRules.Contributors lastContributors = new ThreatRules.Contributors(0, 0, 0, 0, 0, 0);

    public RoadThreat(final UUID roadId)
    {
        this.roadId = Objects.requireNonNull(roadId, "roadId");
    }

    public UUID roadId() { return roadId; }
    public double threat() { return threat; }
    public double recentTraffic() { return recentTraffic; }
    public double raidMomentum() { return raidMomentum; }
    public long updatedAt() { return updatedAt; }
    public long suppressedUntil() { return suppressedUntil; }
    public long cooldownUntil() { return cooldownUntil; }
    public int raids() { return raids; }
    public int defeats() { return defeats; }
    public int roadblocks() { return roadblocks; }
    public ThreatRules.Contributors lastContributors() { return lastContributors; }
    public boolean suppressedAt(final long gameTime) { return suppressedUntil > gameTime; }
    public boolean coolingDownAt(final long gameTime) { return cooldownUntil > gameTime; }

    /** One evaluation: decays traffic and momentum, then steps the threat towards the target. */
    void evaluate(final ThreatRules.Contributors contributors, final double step, final long gameTime)
    {
        lastContributors = contributors;
        threat = ThreatRules.step(threat, contributors.target(), step);
        recentTraffic *= ThreatRules.TRAFFIC_DECAY;
        raidMomentum *= ThreatRules.MOMENTUM_DECAY;
        updatedAt = gameTime;
    }

    void addTraffic(final double amount) { recentTraffic = Math.min(100.0D, recentTraffic + Math.max(0.0D, amount)); }

    void raided(final long gameTime, final long cooldown)
    {
        raids++;
        raidMomentum = Math.min(100.0D, raidMomentum + ThreatRules.RAID_MOMENTUM_GAIN);
        cooldownUntil = Math.max(cooldownUntil, gameTime + cooldown);
    }

    void cleared(final long gameTime, final long suppression, final long cooldown)
    {
        defeats++;
        threat = ThreatRules.clamp(threat - ThreatRules.CLEAR_THREAT_DROP);
        raidMomentum = 0.0D;
        suppressedUntil = Math.max(suppressedUntil, gameTime + suppression);
        cooldownUntil = Math.max(cooldownUntil, gameTime + cooldown);
    }

    void encountered(final long gameTime, final long cooldown) { cooldownUntil = Math.max(cooldownUntil, gameTime + cooldown); }

    int nextRoadblockOrdinal() { return ++roadblocks; }

    /** Operator/debug override. */
    public void setThreat(final double value) { threat = ThreatRules.clamp(value); }

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("road", roadId);
        tag.putDouble("threat", threat);
        tag.putDouble("traffic", recentTraffic);
        tag.putDouble("momentum", raidMomentum);
        tag.putLong("updatedAt", updatedAt);
        tag.putLong("suppressedUntil", suppressedUntil);
        tag.putLong("cooldownUntil", cooldownUntil);
        tag.putInt("raids", raids);
        tag.putInt("defeats", defeats);
        tag.putInt("roadblocks", roadblocks);
        final ThreatRules.Contributors c = lastContributors;
        tag.putDouble("cBase", c.base());
        tag.putDouble("cTraffic", c.traffic());
        tag.putDouble("cRemoteness", c.remoteness());
        tag.putDouble("cMomentum", c.momentum());
        tag.putDouble("cSecurity", c.security());
        tag.putDouble("cSuppression", c.suppression());
        return tag;
    }

    static RoadThreat load(final CompoundTag tag)
    {
        final RoadThreat record = new RoadThreat(tag.getUUID("road"));
        record.threat = ThreatRules.clamp(tag.getDouble("threat"));
        record.recentTraffic = Math.max(0.0D, tag.getDouble("traffic"));
        record.raidMomentum = Math.max(0.0D, tag.getDouble("momentum"));
        record.updatedAt = tag.getLong("updatedAt");
        record.suppressedUntil = tag.getLong("suppressedUntil");
        record.cooldownUntil = tag.getLong("cooldownUntil");
        record.raids = Math.max(0, tag.getInt("raids"));
        record.defeats = Math.max(0, tag.getInt("defeats"));
        record.roadblocks = Math.max(0, tag.getInt("roadblocks"));
        record.lastContributors = new ThreatRules.Contributors(tag.getDouble("cBase"), tag.getDouble("cTraffic"),
            tag.getDouble("cRemoteness"), tag.getDouble("cMomentum"), tag.getDouble("cSecurity"), tag.getDouble("cSuppression"));
        return record;
    }
}
