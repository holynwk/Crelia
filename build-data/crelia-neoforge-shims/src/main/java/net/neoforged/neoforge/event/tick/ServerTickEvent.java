package net.neoforged.neoforge.event.tick;

import java.util.function.BooleanSupplier;
import net.neoforged.bus.api.Event;

public abstract class ServerTickEvent extends Event {
    private final BooleanSupplier haveTime;
    private final Object server;
    private ServerTickEvent(BooleanSupplier haveTime, Object server) {
        this.haveTime = haveTime;
        this.server = server;
    }
    public BooleanSupplier haveTime() { return haveTime; }
    public Object getServer() { return server; }

    public static class Pre extends ServerTickEvent {
        public Pre(BooleanSupplier haveTime, Object server) { super(haveTime, server); }
    }
    public static class Post extends ServerTickEvent {
        public Post(BooleanSupplier haveTime, Object server) { super(haveTime, server); }
    }
}
