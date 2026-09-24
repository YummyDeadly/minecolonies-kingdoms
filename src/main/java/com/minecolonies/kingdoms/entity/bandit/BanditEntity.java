package com.minecolonies.kingdoms.entity.bandit;

import com.minecolonies.kingdoms.bandit.BanditManager;
import com.minecolonies.kingdoms.entity.caravan.CaravanMemberEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Temporary physical representative of one bandit of one encounter. It holds only association IDs; the encounter
 * record owns strength, cargo, and outcome. Never written to chunk storage, discarded when the bandit manager no
 * longer lists it, and it drops nothing of value (rewards come from contracts and encounter resolution only).
 *
 * <p>AI, in priority order: fight back, attack the caravan of its encounter, attack a nearby player, return to the
 * ambush point. Local movement is vanilla navigation; re-paths are throttled.
 */
public final class BanditEntity extends Monster
{
    private static final EntityDataAccessor<Boolean> CHIEF = SynchedEntityData.defineId(BanditEntity.class, EntityDataSerializers.BOOLEAN);
    private UUID encounterId;
    private UUID shipmentId;
    private BlockPos rally;
    private boolean managerDiscard;

    public BanditEntity(final EntityType<? extends BanditEntity> type, final Level level)
    {
        super(type, level);
        setPersistenceRequired();
        xpReward = 3;
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.3D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 24.0D)
            .add(Attributes.ARMOR, 2.0D);
    }

    public void configure(final UUID encounterId, final UUID shipmentId, final BlockPos rally, final boolean chief)
    {
        this.encounterId = encounterId;
        this.shipmentId = shipmentId;
        this.rally = rally.immutable();
        entityData.set(CHIEF, chief);
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(chief ? Items.IRON_AXE : Items.STONE_SWORD));
        for (final EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0.0F);
    }

    /** The chief is named by its type name (no custom name, so deaths are not logged as named entities). */
    @Override
    protected Component getTypeName()
    {
        return chief() ? Component.translatable("entity.minecolonies_kingdoms.bandit_chief") : super.getTypeName();
    }

    public UUID encounterId() { return encounterId; }
    public UUID shipmentId() { return shipmentId; }
    public BlockPos rally() { return rally; }
    public boolean chief() { return entityData.get(CHIEF); }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder builder)
    {
        super.defineSynchedData(builder);
        builder.define(CHIEF, false);
    }

    @Override
    protected void registerGoals()
    {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1D, false));
        goalSelector.addGoal(5, new ReturnToRallyGoal(this));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(BanditEntity.class));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, CaravanMemberEntity.class, 10, true, false,
            member -> shipmentId != null && member instanceof CaravanMemberEntity caravan && shipmentId.equals(caravan.shipmentId())));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Player.class, true));
        targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, com.minecolonies.kingdoms.entity.guard.SettlementGuardEntity.class, true));
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
        if (!level().isClientSide && tickCount % 40 == 0 && !BanditManager.getInstance().isCurrent(this))
        {
            managerDiscard = true;
            discard();
        }
    }

    @Override
    public boolean hurt(final DamageSource source, final float amount)
    {
        final boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide && source.getEntity() instanceof ServerPlayer player)
            BanditManager.getInstance().onBanditHurt(this, player);
        return hurt;
    }

    @Override
    public void die(final DamageSource source)
    {
        final boolean wasDead = dead;
        super.die(source);
        if (wasDead || !dead) return; // a cancelled death (LivingDeathEvent) or a repeated call reports nothing
        if (!managerDiscard && !level().isClientSide)
            BanditManager.getInstance().onBanditDeath(this, source.getEntity() instanceof ServerPlayer player ? player : null,
                source.getEntity() instanceof com.minecolonies.kingdoms.entity.guard.SettlementGuardEntity guard ? guard.settlementId() : null);
    }

    /** Nothing but the vanilla experience: no custom loot, equipment never drops (see {@link #configure}). */
    @Override
    protected void dropCustomDeathLoot(final ServerLevel level, final DamageSource source, final boolean recentlyHit) { }

    @Override
    public boolean shouldBeSaved() { return false; }

    /** Never through portals: that would load chunks in another dimension; the manager owns where it is. */
    @Override
    public boolean canUsePortal(final boolean allowPassengers) { return false; }

    @Override
    public boolean removeWhenFarAway(final double distance) { return false; }

    @Override
    public void addAdditionalSaveData(final CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        if (encounterId != null) tag.putUUID("kingdomsEncounter", encounterId);
    }

    @Override
    public void readAdditionalSaveData(final CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        encounterId = tag.hasUUID("kingdomsEncounter") ? tag.getUUID("kingdomsEncounter") : null;
    }

    /** Walks back to the ambush point when idle; re-paths at most every 40 ticks. */
    private static final class ReturnToRallyGoal extends Goal
    {
        private final BanditEntity bandit;
        private int cooldown;

        ReturnToRallyGoal(final BanditEntity bandit)
        {
            this.bandit = bandit;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse()
        {
            return bandit.getTarget() == null && bandit.rally != null && bandit.distanceToSqr(bandit.rally.getCenter()) > 36.0D;
        }

        @Override
        public boolean canContinueToUse()
        {
            return bandit.getTarget() == null && bandit.rally != null && bandit.distanceToSqr(bandit.rally.getCenter()) > 9.0D;
        }

        @Override
        public void start()
        {
            cooldown = 0;
        }

        @Override
        public void tick()
        {
            if (--cooldown > 0) return;
            cooldown = 40;
            bandit.getNavigation().moveTo(bandit.rally.getX() + 0.5D, bandit.rally.getY(), bandit.rally.getZ() + 0.5D, 1.0D);
        }

        @Override
        public void stop()
        {
            bandit.getNavigation().stop();
        }
    }
}
