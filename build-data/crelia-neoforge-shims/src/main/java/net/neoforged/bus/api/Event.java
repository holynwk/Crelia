package net.neoforged.bus.api;

public abstract class Event {
    private boolean canceled;

    public boolean isCanceled() { return this.canceled; }
    public void setCanceled(boolean cancel) { this.canceled = cancel; }
}