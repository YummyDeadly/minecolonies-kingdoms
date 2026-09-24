package com.minecolonies.kingdoms.citizen.interaction;

import com.minecolonies.kingdoms.citizen.CitizenActivity;
import com.minecolonies.kingdoms.citizen.SettlementRepresentative;
import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import net.minecraft.server.level.ServerPlayer;

/** Read-only facts about one interaction; handlers must not mutate strategic state through it. */
public record CitizenInteractionContext(ServerPlayer player, SettlementRepresentative representative,
    SettlementRecord settlement, NPCColonyData colony, CitizenActivity activity, long dayTime)
{
}
