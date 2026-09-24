package com.minecolonies.kingdoms.integration.minecolonies;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.faction.FactionType;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MineColoniesColonySynchronizer
{
    private MineColoniesColonySynchronizer()
    {
    }

    public static NPCColonyData synchronize(final ServerLevel level, final IColony colony)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(level);
        final ResourceLocation dimension = colony.getDimension().location();
        final UUID colonyId = stableId("colony", dimension, colony.getID());
        final UUID factionId = stableId("faction", dimension, colony.getID());

        final Faction faction = data.faction(factionId).orElseGet(() -> {
            final boolean abandoned = "[abandoned]".equals(colony.getPermissions().getOwnerName());
            final Faction created = new Faction(factionId, colony.getName(), abandoned ? FactionType.NEUTRAL : FactionType.PLAYER);
            created.setCapitalColonyId(colonyId);
            if (!abandoned)
            {
                created.setLeaderId(colony.getPermissions().getOwner());
            }
            data.putFaction(created);
            return created;
        });

        final NPCColonyData snapshot = data.colony(colonyId).orElseGet(() -> {
            final NPCColonyData created = new NPCColonyData(
                colonyId,
                colony.getID(),
                dimension,
                colony.getCenter(),
                colony.getName(),
                factionId,
                level.getGameTime());
            data.putColony(created);
            return created;
        });

        snapshot.rename(colony.getName());
        snapshot.setCenter(colony.getCenter());
        snapshot.setPlayerManaged(faction.type() == FactionType.PLAYER);
        final int population = colony.getCitizenManager().getCurrentCitizenCount();
        final int workers = (int) colony.getCitizenManager().getCitizens().stream()
            .map(ICitizenData::getJob)
            .filter(job -> job != null && !job.isGuard())
            .count();
        final int soldiers = (int) colony.getCitizenManager().getCitizens().stream()
            .map(ICitizenData::getJob)
            .filter(job -> job != null && job.isGuard())
            .count();
        snapshot.updatePopulation(population, workers, soldiers);
        snapshot.updateCapacities(colony.getCitizenManager().getMaxCitizens(), snapshot.storageCapacity());
        data.markChanged();
        return snapshot;
    }

    private static UUID stableId(final String kind, final ResourceLocation dimension, final int colonyId)
    {
        final String key = "minecolonies_kingdoms:" + kind + ':' + dimension + ':' + colonyId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
