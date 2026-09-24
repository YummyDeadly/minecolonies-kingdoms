package com.minecolonies.kingdoms.integration.minecolonies.economy;

import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.ResourceValueProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Map;

public final class MinecraftItemResourceValueProvider implements ResourceValueProvider<ItemStack>
{
    @Override
    public Map<EconomicResource, Double> values(final ItemStack stack)
    {
        final EnumMap<EconomicResource, Double> values = new EnumMap<>(EconomicResource.class);
        if (stack.isEmpty())
        {
            return values;
        }

        final FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null)
        {
            values.put(EconomicResource.FOOD, (double) stack.getCount() * Math.max(1, food.nutrition()));
        }
        if (stack.is(ItemTags.LOGS))
        {
            values.put(EconomicResource.WOOD, stack.getCount() * 4.0D);
        }
        else if (stack.is(ItemTags.PLANKS))
        {
            values.put(EconomicResource.WOOD, (double) stack.getCount());
        }
        if (stack.is(ItemTags.STONE_CRAFTING_MATERIALS)
            || stack.is(Items.STONE)
            || stack.is(Items.COBBLESTONE)
            || stack.is(Items.DEEPSLATE)
            || stack.is(Items.COBBLED_DEEPSLATE))
        {
            values.put(EconomicResource.STONE, (double) stack.getCount());
        }
        if (stack.is(Items.IRON_BLOCK))
        {
            values.put(EconomicResource.IRON, stack.getCount() * 9.0D);
        }
        else if (stack.is(Items.IRON_INGOT) || stack.is(Items.RAW_IRON))
        {
            values.put(EconomicResource.IRON, (double) stack.getCount());
        }
        if (stack.is(ItemTags.AXES)
            || stack.is(ItemTags.HOES)
            || stack.is(ItemTags.PICKAXES)
            || stack.is(ItemTags.SHOVELS))
        {
            values.put(EconomicResource.TOOLS, (double) stack.getCount());
        }
        return values;
    }
}
