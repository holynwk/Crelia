package net.neoforged.neoforge.event.entity;

import net.neoforged.bus.api.ICancellableEvent;

public class EntityJoinLevelEvent extends EntityEvent implements ICancellableEvent {
    public EntityJoinLevelEvent(Object entity, Object level, Object direction) {
        super(entity);
    }

    @Override
    public void setCanceled(boolean canceled) {
        super.setCanceled(canceled);
    }
}
