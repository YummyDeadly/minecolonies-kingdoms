package com.minecolonies.kingdoms.entity.soldier;

import com.minecolonies.kingdoms.entity.KnightAppearance;
import com.minecolonies.kingdoms.entity.bandit.BanditEntity;
import com.minecolonies.kingdoms.entity.guard.SettlementGuardEntity;
import com.minecolonies.kingdoms.war.ArmyManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Temporary physical representative of one soldier of a field army (Phase 10). It carries only association IDs and a
 * cosmetic key; the army and battle records own strength and losses. It is never written to chunk storage, discards
 * itself when the army manager no longer lists it, drops nothing, and gives no experience.
 *
 * <p>It fights guards of settlements whose faction is at war with its own (only while its army besieges or marches),
 * whoever hurts it (players are never attacked unprovoked), and otherwise follows the manager's march waypoint.
 */
public final class SoldierEntity extends PathfinderMob implements KnightAppearance
{
    private static final EntityDataAccessor<String> TEXTURE = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TITLE = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.STRING);
    public static final double MARCH_SPEED = 0.9D;
    private UUID armyId;
    private UUID factionId;
    private boolean managerDiscard;
    private Vec3 planTarget;

    public SoldierEntity(final EntityType<? extends SoldierEntity> type, final Level level)
    {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 26.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.3D)
            .add(Attributes.ATTACK_DAMAGE, 4.5D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .add(Attributes.ARMOR, 3.0D);
    }

    public void configure(final UUID armyId, final UUID factionId, final String name, final String textureKey)
    {
        this.armyId = armyId;
        this.factionId = factionId;
        entityData.set(TEXTURE, textureKey);
        entityData.set(TITLE, name);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
        setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
        setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
        for (final EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0.0F);
    }

    @Override
    protected Component getTypeName()
    {
        final String title = entityData.get(TITLE);
        return title.isEmpty() ? super.getTypeName() : Component.literal(title);
    }

    public UUID armyId() { return armyId; }
    public UUID factionId() { return factionId; }
    @Override public String textureKey() { return entityData.get(TEXTURE); }
    public Vec3 planTarget() { return planTarget; }
    public void planTarget(final Vec3 target) { planTarget = target; }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder builder)
    {
        super.defineSynchedData(builder);
        builder.define(TEXTURE, "");
        builder.define(TITLE, "");
    }

    @Override
    protected void registerGoals()
    {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1D, false));
        goalSelector.addGoal(4, new FollowPlanGoal(this));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, SoldierEntity.class)
        {
            @Override
            protected void alertOthers()
            {
                // only this soldier's own squad joins in (vanilla would alert every soldier of every army nearby)
                ArmyManager.getInstance().alertSquad(SoldierEntity.this, mob.getLastHurtByMob());
            }
        }.setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, SettlementGuardEntity.class, 5, true, false,
            target -> target instanceof SettlementGuardEntity guard && ArmyManager.getInstance().enemies(this, guard)));
    }

    /** Soldiers of one faction never fight each other. */
    @Override
    public boolean isAlliedTo(final Entity other)
    {
        if (other instanceof SoldierEntity soldier && factionId != null && factionId.equals(soldier.factionId)) return true;
        if (other instanceof BanditEntity) return false;
        return super.isAlliedTo(other);
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
        if (!level().isClientSide && tickCount % 40 == 0 && !ArmyManager.getInstance().isCurrent(this))
        {
            managerDiscard = true;
            discard();
        }
    }

    @Override
    public boolean hurt(final DamageSource source, final float amount)
    {
        final boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide) ArmyManager.getInstance().onSoldierHurt(this, source);
        return hurt;
    }

    @Override
    public void die(final DamageSource source)
    {
        final boolean wasDead = dead;
        super.die(source);
        if (wasDead || !dead) return; // a cancelled death (LivingDeathEvent) or a repeated call reports nothing
        if (!managerDiscard && !level().isClientSide) ArmyManager.getInstance().onSoldierDeath(this, source);
    }

    /** Never through portals (that would load chunks in another dimension); the manager owns where soldiers are. */
    @Override
    public boolean canUsePortal(final boolean allowPassengers) { return false; }

    /** Never riding: a passenger would be saved to chunks with its vehicle. */
    @Override
    public boolean startRiding(final Entity vehicle, final boolean force) { return false; }

    @Override
    protected void dropCustomDeathLoot(final ServerLevel level, final DamageSource source, final boolean recentlyHit) { }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean removeWhenFarAway(final double distance) { return false; }

    @Override
    public void addAdditionalSaveData(final CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        if (armyId != null) tag.putUUID("kingdomsArmy", armyId);
    }

    @Override
    public void readAdditionalSaveData(final CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        armyId = tag.hasUUID("kingdomsArmy") ? tag.getUUID("kingdomsArmy") : null;
    }

    /** Walks to the manager-supplied waypoint when not fighting; re-paths at most every 40 ticks. */
    private static final class FollowPlanGoal extends Goal
    {
        private final SoldierEntity soldier;
        private Vec3 issued;
        private int ticks;

        FollowPlanGoal(final SoldierEntity soldier)
        {
            this.soldier = soldier;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse()
        {
            return soldier.getTarget() == null && soldier.planTarget != null && soldier.distanceToSqr(soldier.planTarget) > 2.25D;
        }

        @Override
        public boolean canContinueToUse()
        {
            return soldier.getTarget() == null && soldier.planTarget != null && soldier.distanceToSqr(soldier.planTarget) > 1.0D;
        }

        @Override
        public void start() { issue(); }

        @Override
        public void tick()
        {
            if (!soldier.planTarget.equals(issued) || ++ticks % 40 == 0) issue();
        }

        @Override
        public void stop()
        {
            soldier.getNavigation().stop();
            issued = null;
        }

        private void issue()
        {
            issued = soldier.planTarget;
            soldier.getNavigation().moveTo(issued.x, issued.y, issued.z, MARCH_SPEED);
        }
    }
}
