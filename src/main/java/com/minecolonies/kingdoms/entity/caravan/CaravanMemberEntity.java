package com.minecolonies.kingdoms.entity.caravan;

import com.minecolonies.kingdoms.caravan.CaravanManager;
import com.minecolonies.kingdoms.caravan.CaravanMemberRole;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class CaravanMemberEntity extends PathfinderMob
{
    private UUID caravanId;
    private UUID shipmentId;
    private CaravanMemberRole role = CaravanMemberRole.GUARD;
    private boolean managerDiscard;

    public CaravanMemberEntity(final EntityType<? extends CaravanMemberEntity> type, final Level level)
    {
        super(type, level);
        setPersistenceRequired();
    }

    public void configure(
        final UUID caravanId,
        final UUID shipmentId,
        final CaravanMemberRole role,
        final boolean debugNames)
    {
        this.caravanId = caravanId;
        this.shipmentId = shipmentId;
        this.role = role;
        setCustomName(Component.literal(debugNames
            ? "Caravan " + role + " " + shipmentId.toString().substring(0, 8)
            : switch (role)
            {
                case LEADER -> "Caravan leader";
                case CARRIER -> "Caravan carrier";
                case GUARD -> "Caravan guard";
            }));
        setCustomNameVisible(debugNames);
    }

    public UUID caravanId() { return caravanId; }
    public UUID shipmentId() { return shipmentId; }
    public CaravanMemberRole role() { return role; }

    @Override
    protected void registerGoals()
    {
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1D, true));
        targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(CaravanMemberEntity.class));
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
        if (!level().isClientSide && tickCount % 100 == 0
            && !CaravanManager.getInstance().isRegistered(caravanId, shipmentId, getUUID()))
        {
            managerDiscard = true;
            discard();
        }
    }

    @Override
    public void die(final DamageSource source)
    {
        super.die(source);
        if (!managerDiscard && !level().isClientSide
            && (role == CaravanMemberRole.LEADER || role == CaravanMemberRole.CARRIER))
        {
            CaravanManager.getInstance().onCriticalMemberDestroyed((ServerLevel) level(), caravanId, shipmentId);
        }
    }

    @Override
    protected InteractionResult mobInteract(final Player player, final InteractionHand hand)
    {
        if (!level().isClientSide && role == CaravanMemberRole.LEADER)
        {
            CaravanManager.getInstance().describeTo(player, shipmentId);
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public void addAdditionalSaveData(final CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        if (caravanId != null) tag.putUUID("kingdomsCaravan", caravanId);
        if (shipmentId != null) tag.putUUID("kingdomsShipment", shipmentId);
        tag.putString("kingdomsRole", role.name());
    }

    @Override
    public void readAdditionalSaveData(final CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        caravanId = tag.hasUUID("kingdomsCaravan") ? tag.getUUID("kingdomsCaravan") : null;
        shipmentId = tag.hasUUID("kingdomsShipment") ? tag.getUUID("kingdomsShipment") : null;
        try
        {
            role = CaravanMemberRole.valueOf(tag.getString("kingdomsRole"));
        }
        catch (IllegalArgumentException ignored)
        {
            role = CaravanMemberRole.GUARD;
        }
    }
}
