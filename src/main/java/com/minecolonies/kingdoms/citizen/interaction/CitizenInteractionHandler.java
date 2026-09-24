package com.minecolonies.kingdoms.citizen.interaction;

import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Extension point for right-click interaction. Phase 7 systems (contracts, reputation, trade offers) register
 * handlers here; entities never contain diplomacy or trade policy.
 */
public interface CitizenInteractionHandler
{
    /** Stable ordering key; lower runs first. */
    default int priority() { return 100; }

    /** A line to show the player, or empty when this handler has nothing to say. */
    Optional<Component> respond(CitizenInteractionContext context);
}
