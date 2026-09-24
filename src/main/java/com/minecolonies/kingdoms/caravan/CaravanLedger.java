package com.minecolonies.kingdoms.caravan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CaravanLedger
{
    private final Map<UUID, CaravanInstance> byId = new LinkedHashMap<>();
    private final Map<UUID, UUID> byShipment = new LinkedHashMap<>();

    public Collection<CaravanInstance> instances() { return Collections.unmodifiableCollection(byId.values()); }
    public Optional<CaravanInstance> instance(final UUID id) { return Optional.ofNullable(byId.get(id)); }
    public Optional<CaravanInstance> forShipment(final UUID shipmentId)
    {
        return Optional.ofNullable(byShipment.get(shipmentId)).map(byId::get);
    }

    public void put(final CaravanInstance instance)
    {
        removeForShipment(instance.shipmentId());
        byId.put(instance.id(), instance);
        byShipment.put(instance.shipmentId(), instance.id());
    }

    public Optional<CaravanInstance> removeForShipment(final UUID shipmentId)
    {
        final UUID id = byShipment.remove(shipmentId);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.remove(id));
    }

    public void clear()
    {
        byId.clear();
        byShipment.clear();
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        byId.values().forEach(instance -> list.add(instance.save()));
        tag.put("instances", list);
        return tag;
    }

    public static CaravanLedger load(final CompoundTag tag)
    {
        final CaravanLedger ledger = new CaravanLedger();
        tag.getList("instances", Tag.TAG_COMPOUND).forEach(value -> ledger.put(CaravanInstance.load((CompoundTag) value)));
        return ledger;
    }
}
