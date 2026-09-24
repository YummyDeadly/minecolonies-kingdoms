package com.minecolonies.kingdoms.persistence;

import net.minecraft.nbt.CompoundTag;

/** Lets tests in other packages simulate a save and restart through the real (package-private) load path. */
public final class PersistenceTestAccess
{
    private PersistenceTestAccess() {}

    public static KingdomsSavedData reload(final KingdomsSavedData data)
    {
        return KingdomsSavedData.load(data.save(new CompoundTag(), null), null);
    }

    public static KingdomsSavedData load(final CompoundTag tag)
    {
        return KingdomsSavedData.load(tag, null);
    }
}
