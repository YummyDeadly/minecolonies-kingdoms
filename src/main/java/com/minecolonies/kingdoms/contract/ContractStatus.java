package com.minecolonies.kingdoms.contract;

/**
 * Contract lifecycle. OFFERED -> ACCEPTED | EXPIRED | CANCELLED; ACCEPTED -> COMPLETED | FAILED | CANCELLED.
 * Terminal states never change again.
 */
public enum ContractStatus
{
    /** Posted by a settlement; nothing is reserved yet. */
    OFFERED,
    /** Taken by one player; the agreed reward is reserved from the treasury. */
    ACCEPTED,
    /** Fully delivered; reward and reputation are applied exactly once. */
    COMPLETED,
    /** Accepted but not delivered before the deadline; the reservation returns to the treasury. */
    FAILED,
    /** An offer nobody accepted in time. */
    EXPIRED,
    /** Withdrawn before completion (player abandoned it, settlement removed, operator); reservations return. */
    CANCELLED;

    public boolean terminal() { return this != OFFERED && this != ACCEPTED; }
    public boolean open() { return !terminal(); }
}
