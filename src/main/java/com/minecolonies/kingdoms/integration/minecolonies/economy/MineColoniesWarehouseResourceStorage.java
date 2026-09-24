package com.minecolonies.kingdoms.integration.minecolonies.economy;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.kingdoms.colony.ColonyResourceStorage;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.colony.ResourceStorageObservation;
import com.minecolonies.kingdoms.economy.ColonyEconomySnapshot;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.economy.ResourceEconomy;
import com.minecolonies.kingdoms.economy.ResourceFlow;
import com.minecolonies.kingdoms.economy.ResourceStockpile;
import com.minecolonies.kingdoms.economy.ResourceValueProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MineColoniesWarehouseResourceStorage implements ColonyResourceStorage
{
    private final ResourceValueProvider<ItemStack> valueProvider;

    public MineColoniesWarehouseResourceStorage(final ResourceValueProvider<ItemStack> valueProvider)
    {
        this.valueProvider = valueProvider;
    }

    @Override
    public Optional<ResourceStorageObservation> observe(final ServerLevel level, final NPCColonyData colonyData)
    {
        if (colonyData.mineColoniesColonyId().isEmpty())
        {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, colonyData.dimension());
        final IColony colony = IColonyManager.getInstance().getColonyByDimension(
            colonyData.mineColoniesColonyId().getAsInt(), dimension);
        if (colony == null)
        {
            return Optional.empty();
        }

        final Set<BlockPos> containerPositions = new LinkedHashSet<>();
        for (final IWareHouse wareHouse : colony.getServerBuildingManager().getWareHouses())
        {
            containerPositions.addAll(wareHouse.getContainers());
        }
        if (containerPositions.isEmpty())
        {
            return Optional.empty();
        }

        final EnumMap<EconomicResource, Double> totals = new EnumMap<>(EconomicResource.class);
        int totalSlots = 0;
        for (final BlockPos position : containerPositions)
        {
            if (!level.hasChunkAt(position))
            {
                return Optional.empty();
            }
            final BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity == null)
            {
                return Optional.empty();
            }
            final IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(
                level,
                position,
                level.getBlockState(position),
                blockEntity,
                null);
            if (handler == null)
            {
                return Optional.empty();
            }
            totalSlots += handler.getSlots();
            for (int slot = 0; slot < handler.getSlots(); slot++)
            {
                valueProvider.values(handler.getStackInSlot(slot)).forEach(
                    (resource, amount) -> totals.merge(resource, amount, Double::sum));
            }
        }

        final Map<EconomicResource, ResourceEconomy> resources = new EnumMap<>(EconomicResource.class);
        for (final EconomicResource resource : EconomicResource.values())
        {
            resources.put(resource, new ResourceEconomy(
                new ResourceStockpile(Math.max(0L, Math.round(totals.getOrDefault(resource, 0.0D))), 0L),
                new ResourceFlow(0.0D, 0.0D)));
        }
        return Optional.of(new ResourceStorageObservation(new ColonyEconomySnapshot(resources), totalSlots));
    }
}
