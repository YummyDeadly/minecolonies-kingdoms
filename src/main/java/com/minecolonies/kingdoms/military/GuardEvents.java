package com.minecolonies.kingdoms.military;

import com.minecolonies.kingdoms.entity.guard.SettlementGuardEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * How guards relate to fights beyond bandits and monsters (enemy soldiers of a faction at war, Phase 10). The military
 * package does not depend on the war package: the war module registers itself here while the server runs. Without a
 * registration guards never attack anyone but bandits, monsters, and whoever hurts them.
 */
public interface GuardEvents
{
    /** Whether this guard can have a further enemy right now (cheap; the guard skips the target search otherwise). */
    boolean active(SettlementGuardEntity guard);

    /** Whether a guard should attack this entity. */
    boolean hostile(SettlementGuardEntity guard, LivingEntity target);

    /** A guard was killed by {@code killer}; returns whether the death counted as a battle loss. */
    boolean guardKilled(SettlementGuardEntity guard, Entity killer);

    GuardEvents NONE = new GuardEvents()
    {
        @Override public boolean active(final SettlementGuardEntity guard) { return false; }
        @Override public boolean hostile(final SettlementGuardEntity guard, final LivingEntity target) { return false; }
        @Override public boolean guardKilled(final SettlementGuardEntity guard, final Entity killer) { return false; }
    };

    static GuardEvents current() { return Holder.current; }

    /** Registers the war module's listener (or {@code null} to remove it). */
    static void register(final GuardEvents events) { Holder.current = events == null ? NONE : events; }

    final class Holder
    {
        private static volatile GuardEvents current = NONE;
        private Holder() {}
    }
}
