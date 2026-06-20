package net.neoforged.neoforge.event.server;

import net.neoforged.bus.api.Event;

public abstract class ServerLifecycleEvent extends Event {
    protected final Object server;

    public ServerLifecycleEvent(Object server) {
        this.server = server;
    }

    public Object getServer() {
        return server;
    }
}
