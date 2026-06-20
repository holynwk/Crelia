package net.neoforged.neoforge.event.entity.living;

import net.neoforged.neoforge.event.entity.EntityEvent;

public abstract class LivingEvent extends EntityEvent {
    private final Object livingEntity;

    public LivingEvent(Object entity) {
        super(entity);
        this.livingEntity = entity;
    }

    @Override
    public Object getEntity() {
        return livingEntity;
    }
}
