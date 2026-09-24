package com.minecolonies.kingdoms.entity.guard;

import com.minecolonies.kingdoms.entity.bandit.BanditEntity;
import com.minecolonies.kingdoms.entity.caravan.CaravanMemberEntity;
import com.minecolonies.kingdoms.entity.citizen.SettlementCitizenEntity;
import com.minecolonies.kingdoms.military.GarrisonManager;
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
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Temporary physical representative of one soldier of a settlement garrison (Phase 9). It carries only association
 * IDs and a cosmetic key; the garrison record owns strength and casualties. It is never written to chunk storage,
 * discards itself when the garrison manager no longer lists it, drops nothing, and gives no experience.
 *
 * <p>AI, in priority order: fight back (never against its own side), bandits, then ordinary hostile monsters (not
 * creepers or endermen, so it never blows up or provokes anything in town), then walk the manager's patrol waypoint.
 * Local movement is vanilla navigation; the manager supplies sparse waypoints along the settlement's streets.
 */
public final class SettlementGuardEntity extends PathfinderMob
{
    private static final EntityDataAccessor<String> TEXTURE = SynchedEntityData.defineId(SettlementGuardEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TITLE = SynchedEntityData.defineId(SettlementGuardEntity.class, EntityDataSerializers.STRING);
    /** Navigation speed multiplier while patrolling (the attribute is 0.3; fights use 1.15). */
    public static final double WALK_SPEED = 0.85D;
    /** Guards engage ordinary monsters within this range only. */
    static final double MONSTER_RANGE = 16.0D;
    private UUID settlementId;
    private UUID engagedEncounter;
    private boolean managerDiscard;
    private Vec3 planTarget;

    public SettlementGuardEntity(final EntityType<? extends SettlementGuardEntity> type, final Level level)
    {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 30.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.3D)
            .add(Attributes.ATTACK_DAMAGE, 5.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .add(Attributes.ARMOR, 2.0D);
    }

    /** {@code encounter}: the bandit fight a responder was sent to, or null for a patrolling guard. */
    public void configure(final UUID settlementId, final String name, final String textureKey, final UUID encounter)
    {
        this.settlementId = settlementId;
        this.engagedEncounter = encounter;
        entityData.set(TEXTURE, textureKey);
        entityData.set(TITLE, name);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        for (final EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0.0F);
    }

    /** "Oakwatch Guard" as the type name (no custom name, so deaths are not logged as named entities). */
    @Override
    protected Component getTypeName()
    {
        final String title = entityData.get(TITLE);
        return title.isEmpty() ? super.getTypeName() : Component.literal(title);
    }

    public UUID settlementId() { return settlementId; }
    public UUID engagedEncounter() { return engagedEncounter; }
    public String textureKey() { return entityData.get(TEXTURE); }
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
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15D, false));
        goalSelector.addGoal(4, new FollowPlanGoal(this));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, SettlementGuardEntity.class, SettlementCitizenEntity.class, CaravanMemberEntity.class));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, BanditEntity.class, 5, true, false, null));
        // enemy soldiers (Phase 10): the entity search runs only while some faction is at war
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false,
            target -> GarrisonManager.getInstance().hostileTo(this, target))
        {
            @Override
            public boolean canUse()
            {
                return GarrisonManager.getInstance().hostilityActive() && super.canUse();
            }
        });
        targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
            target -> target instanceof Enemy && !(target instanceof Creeper) && !(target instanceof EnderMan) && !(target instanceof BanditEntity))
        {
            @Override
            protected AABB getTargetSearchArea(final double distance)
            {
                return super.getTargetSearchArea(MONSTER_RANGE);
            }
        });
    }

    /** Guards never travel through portals (that would load chunks in another dimension); the manager owns where they are. */
    @Override
    public boolean canUsePortal(final boolean allowPassengers) { return false; }

    /** Guards never target their own side: other guards, settlement residents, or caravan members. */
    @Override
    public boolean isAlliedTo(final Entity other)
    {
        if (other instanceof SettlementGuardEntity || other instanceof SettlementCitizenEntity || other instanceof CaravanMemberEntity) return true;
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
        if (!level().isClientSide && tickCount % 40 == 0 && !GarrisonManager.getInstance().isCurrent(this))
        {
            managerDiscard = true;
            discard();
        }
    }

    @Override
    public boolean hurt(final DamageSource source, final float amount)
    {
        final boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide) GarrisonManager.getInstance().onGuardHurt(this);
        return hurt;
    }

    @Override
    public void die(final DamageSource source)
    {
        final boolean wasDead = dead;
        super.die(source);
        if (wasDead || !dead) return; // a cancelled death (LivingDeathEvent) or a repeated call reports nothing
        if (!managerDiscard && !level().isClientSide) GarrisonManager.getInstance().onGuardDeath(this, source);
    }

    /** No loot of any kind (equipment never drops, see {@link #configure}). */
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
        if (settlementId != null) tag.putUUID("kingdomsSettlement", settlementId);
    }

    @Override
    public void readAdditionalSaveData(final CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        settlementId = tag.hasUUID("kingdomsSettlement") ? tag.getUUID("kingdomsSettlement") : null;
    }

    /** Walks to the manager-supplied waypoint when not fighting; re-paths at most every 40 ticks. */
    private static final class FollowPlanGoal extends Goal
    {
        private final SettlementGuardEntity guard;
        private Vec3 issued;
        private int ticks;

        FollowPlanGoal(final SettlementGuardEntity guard)
        {
            this.guard = guard;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse()
        {
            return guard.getTarget() == null && guard.planTarget != null && guard.distanceToSqr(guard.planTarget) > 2.25D;
        }

        @Override
        public boolean canContinueToUse()
        {
            return guard.getTarget() == null && guard.planTarget != null && guard.distanceToSqr(guard.planTarget) > 1.0D;
        }

        @Override
        public void start() { issue(); }

        @Override
        public void tick()
        {
            if (!guard.planTarget.equals(issued) || ++ticks % 40 == 0) issue();
        }

        @Override
        public void stop()
        {
            guard.getNavigation().stop();
            issued = null;
        }

        private void issue()
        {
            issued = guard.planTarget;
            guard.getNavigation().moveTo(issued.x, issued.y, issued.z, WALK_SPEED);
        }
    }
}
