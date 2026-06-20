package net.neoforged.neoforge.event.tick;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;

public abstract class EntityTickEvent extends EntityEvent {
    protected EntityTickEvent(Object entity) { super(entity); }

    public static class Pre extends EntityTickEvent implements ICancellableEvent {
        public Pre(Object entity) { super(entity); }
        @Override public void setCanceled(boolean canceled) { super.setCanceled(canceled); }
    }
    public static class Post extends EntityTickEvent {
        public Post(Object entity) { super(entity); }
    }
}
