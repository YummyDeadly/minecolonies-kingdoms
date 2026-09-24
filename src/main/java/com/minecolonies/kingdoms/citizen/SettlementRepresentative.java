package com.minecolonies.kingdoms.citizen;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * One notable, recognisable resident of a settlement. The roster is a bounded sample of the population, never the
 * population itself; nothing here is strategic truth. The slot key ({@code role}, {@code workBuildingId},
 * {@code ordinal}) determines the identity deterministically, so recomputing a roster reproduces the same people.
 */
public record SettlementRepresentative(UUID id, UUID settlementId, CitizenRole role, UUID workBuildingId, int ordinal,
    UUID homeBuildingId, String name, boolean female, long cosmeticSeed)
{
    public SettlementRepresentative
    {
        Objects.requireNonNull(id);
        Objects.requireNonNull(settlementId);
        Objects.requireNonNull(role);
        name = Objects.requireNonNull(name).trim();
        if (name.isEmpty() || ordinal < 0) throw new IllegalArgumentException("Invalid representative");
        if (role.works() != (workBuildingId != null))
            throw new IllegalArgumentException("Working roles need a workplace; residents have none");
    }

    public String slotKey()
    {
        return role.name() + ':' + (workBuildingId == null ? "-" : workBuildingId.toString()) + ':' + ordinal;
    }

    public SettlementRepresentative withHome(final UUID home)
    {
        return new SettlementRepresentative(id, settlementId, role, workBuildingId, ordinal, home, name, female, cosmeticSeed);
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id); tag.putUUID("settlement", settlementId); tag.putString("role", role.name());
        if (workBuildingId != null) tag.putUUID("work", workBuildingId);
        if (homeBuildingId != null) tag.putUUID("home", homeBuildingId);
        tag.putInt("ordinal", ordinal); tag.putString("name", name); tag.putBoolean("female", female);
        tag.putLong("seed", cosmeticSeed);
        return tag;
    }

    public static SettlementRepresentative load(final CompoundTag tag)
    {
        return new SettlementRepresentative(tag.getUUID("id"), tag.getUUID("settlement"),
            CitizenRole.valueOf(tag.getString("role")), tag.hasUUID("work") ? tag.getUUID("work") : null,
            tag.getInt("ordinal"), tag.hasUUID("home") ? tag.getUUID("home") : null, tag.getString("name"),
            tag.getBoolean("female"), tag.getLong("seed"));
    }
}
