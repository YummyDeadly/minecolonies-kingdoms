package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.TradeManager;
import com.minecolonies.kingdoms.worldevent.WorldHistory;

import java.util.Optional;
import java.util.UUID;

/**
 * Removes a tracked colony in one place (operator deletion of a strategic NPC colony, or a MineColonies colony that was
 * deleted/abandoned): trade routes break and in-transit cargo is failed or returned through {@link TradeManager}, the
 * record is removed, an emptied faction is dropped, and the world history records the abandonment once.
 */
public final class ColonyRemoval
{
    private ColonyRemoval() {}

    /** Returns the removed colony, or empty if it was not tracked. */
    public static Optional<NPCColonyData> remove(final KingdomsSavedData data, final UUID colonyId, final long gameTime, final String why)
    {
        final NPCColonyData colony = data.colony(colonyId).orElse(null);
        if (colony == null) return Optional.empty();
        TradeManager.getInstance().handleColonyDeletion(data, colonyId, gameTime);
        data.removeColony(colonyId);
        data.faction(colony.factionId()).ifPresent(faction -> {
            faction.removeSettlement(colonyId);
            if (faction.settlementIds().isEmpty()) data.removeFaction(faction.id());
        });
        data.markChanged();
        WorldHistory.record(data, WorldHistory.Entry.of(WorldHistory.Kind.COLONY_ABANDONED, colonyId, gameTime, colonyId, colony.factionId(),
            colony.name() + " was abandoned (" + why + ")"));
        return Optional.of(colony);
    }
}
