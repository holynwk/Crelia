package net.neoforged.bus.api;

public interface ICancellableEvent {
    void setCanceled(boolean cancel);
    boolean isCanceled();
}