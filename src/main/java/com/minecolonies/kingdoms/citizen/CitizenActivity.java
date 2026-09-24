package com.minecolonies.kingdoms.citizen;

/** Lightweight daily activity of a physical representative; never persisted. */
public enum CitizenActivity
{
    HOME("turning in for the night"), // a visible representative with this activity is on the way to its door
    GO_TO_WORK("going to work"),
    WORK("working"),
    GO_TO_PLAZA("heading to the plaza"),
    WANDER("strolling"),
    VISIT_MARKET("visiting the market"),
    SOCIALIZE("chatting on the plaza"),
    RETURN_HOME("returning home"),
    IDLE("idling");

    private final String description;

    CitizenActivity(final String description) { this.description = description; }

    public String description() { return description; }
}
