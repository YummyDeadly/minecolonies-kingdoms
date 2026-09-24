package com.minecolonies.kingdoms.entity.citizen;

import com.minecolonies.kingdoms.citizen.SettlementCitizenManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Temporary physical representative of a settlement resident. It carries only association ids and a cosmetic key:
 * no population, inventory, or strategic state. It is never written to chunk storage and discards itself when the
 * citizen manager no longer lists it, so restarts and chunk reloads cannot duplicate residents.
 */
public final class SettlementCitizenEntity extends PathfinderMob
{
    private static final EntityDataAccessor<String> TEXTURE =
        SynchedEntityData.defineId(SettlementCitizenEntity.class, EntityDataSerializers.STRING);
    public static final double WALK_SPEED = 0.5D;
    private UUID settlementId;
    private UUID representativeId;
    private boolean managerDiscard;
    private Vec3 planTarget;

    public SettlementCitizenEntity(final EntityType<? extends SettlementCitizenEntity> type, final Level level)
    {
        super(type, level);
        setPersistenceRequired();
    }

    public void configure(final UUID settlementId, final UUID representativeId, final String name, final String textureKey)
    {
        this.settlementId = settlementId;
        this.representativeId = representativeId;
        entityData.set(TEXTURE, textureKey);
        setCustomName(Component.literal(name));
        setCustomNameVisible(false);
    }

    public UUID settlementId() { return settlementId; }
    public UUID representativeId() { return representativeId; }
    public String textureKey() { return entityData.get(TEXTURE); }
    public Vec3 planTarget() { return planTarget; }

    /** Set by the citizen manager; the movement goal walks there with vanilla pathfinding. */
    public void planTarget(final Vec3 target) { planTarget = target; }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder builder)
    {
        super.defineSynchedData(builder);
        builder.define(TEXTURE, "");
    }

    @Override
    protected void registerGoals()
    {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 0.9D));
        goalSelector.addGoal(2, new FollowPlanGoal(this));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    public void discardByManager()
    {
        managerDiscard = true;
        discard();
    }

    @Override
    public void tick()
    {
        super.tick();
        if (!level().isClientSide && tickCount % 40 == 0 && !SettlementCitizenManager.getInstance().isCurrent(this))
        {
            managerDiscard = true;
            discard();
        }
    }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean removeWhenFarAway(final double distance) { return false; }

    @Override
    public void die(final DamageSource source)
    {
        final boolean wasDead = dead;
        super.die(source);
        if (wasDead || !dead) return; // a cancelled death (LivingDeathEvent) or a repeated call reports nothing
        if (!managerDiscard && !level().isClientSide)
            SettlementCitizenManager.getInstance().onDeath(this, source.getEntity() instanceof ServerPlayer killer ? killer : null);
    }

    @Override
    protected InteractionResult mobInteract(final Player player, final InteractionHand hand)
    {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer)
        {
            getNavigation().stop();
            getLookControl().setLookAt(player);
            SettlementCitizenManager.getInstance().interact(serverPlayer, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void addAdditionalSaveData(final CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        if (settlementId != null) tag.putUUID("kingdomsSettlement", settlementId);
        if (representativeId != null) tag.putUUID("kingdomsRepresentative", representativeId);
    }

    @Override
    public void readAdditionalSaveData(final CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        settlementId = tag.hasUUID("kingdomsSettlement") ? tag.getUUID("kingdomsSettlement") : null;
        representativeId = tag.hasUUID("kingdomsRepresentative") ? tag.getUUID("kingdomsRepresentative") : null;
    }

    /** Walks to the manager-supplied waypoint; re-issues the path periodically and when the waypoint changes. */
    private static final class FollowPlanGoal extends Goal
    {
        private final SettlementCitizenEntity citizen;
        private Vec3 issued;
        private int ticks;

        FollowPlanGoal(final SettlementCitizenEntity citizen)
        {
            this.citizen = citizen;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse()
        {
            return citizen.planTarget != null && citizen.distanceToSqr(citizen.planTarget) > 2.25D;
        }

        @Override
        public boolean canContinueToUse()
        {
            return citizen.planTarget != null && citizen.distanceToSqr(citizen.planTarget) > 1.0D;
        }

        @Override
        public void start()
        {
            issue();
        }

        @Override
        public void tick()
        {
            // Never re-path every tick: an unreachable target leaves the navigation "done" immediately. The manager's
            // stuck tracker skips or gives up on waypoints that stay unreachable.
            if (!citizen.planTarget.equals(issued) || ++ticks % 40 == 0) issue();
        }

        @Override
        public void stop()
        {
            citizen.getNavigation().stop();
            issued = null;
        }

        private void issue()
        {
            issued = citizen.planTarget;
            citizen.getNavigation().moveTo(issued.x, issued.y, issued.z, WALK_SPEED);
        }
    }
}
