package com.minecolonies.kingdoms.entity.caravan;

import com.minecolonies.kingdoms.KingdomsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities
{
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, KingdomsMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<CaravanMemberEntity>> CARAVAN_MEMBER =
        ENTITY_TYPES.register("caravan_member", () -> EntityType.Builder
            .of(CaravanMemberEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(KingdomsMod.MOD_ID + ":caravan_member"));

    /** Physical settlement representative; MISC like villagers so it never counts against natural mob caps. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.minecolonies.kingdoms.entity.citizen.SettlementCitizenEntity>> SETTLEMENT_CITIZEN =
        ENTITY_TYPES.register("settlement_citizen", () -> EntityType.Builder
            .of(com.minecolonies.kingdoms.entity.citizen.SettlementCitizenEntity::new, MobCategory.MISC)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(KingdomsMod.MOD_ID + ":settlement_citizen"));

    /** Physical bandit of one encounter (Phase 8); MONSTER, but never spawned naturally and never saved. */
    public static final DeferredHolder<EntityType<?>, EntityType<com.minecolonies.kingdoms.entity.bandit.BanditEntity>> BANDIT =
        ENTITY_TYPES.register("bandit", () -> EntityType.Builder
            .of(com.minecolonies.kingdoms.entity.bandit.BanditEntity::new, MobCategory.MONSTER)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .updateInterval(3)
            .build(KingdomsMod.MOD_ID + ":bandit"));

    private ModEntities()
    {
    }

    public static void register(final IEventBus modBus)
    {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(ModEntities::registerAttributes);
    }

    private static void registerAttributes(final EntityAttributeCreationEvent event)
    {
        event.put(CARAVAN_MEMBER.get(), Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 24.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.28D)
            .add(Attributes.ATTACK_DAMAGE, 4.0D)
            .add(Attributes.FOLLOW_RANGE, 24.0D)
            .build());
        event.put(BANDIT.get(), com.minecolonies.kingdoms.entity.bandit.BanditEntity.createAttributes().build());
        event.put(SETTLEMENT_CITIZEN.get(), Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.5D)
            .add(Attributes.FOLLOW_RANGE, 48.0D)
            .build());
    }
}
