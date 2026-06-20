package net.neoforged.neoforge.event.level;

import java.util.List;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public class ExplosionEvent extends Event {
    private final Object level;
    private final Object explosion;
    public ExplosionEvent(Object level, Object explosion) {
        this.level = level;
        this.explosion = explosion;
    }
    public Object getLevel() { return level; }
    public Object getExplosion() { return explosion; }

    public static class Start extends ExplosionEvent implements ICancellableEvent {
        public Start(Object level, Object explosion) { super(level, explosion); }
        @Override public void setCanceled(boolean canceled) { super.setCanceled(canceled); }
    }
    public static class Detonate extends ExplosionEvent {
        private final List<Object> entities;
        private final List<Object> affectedBlocks;
        public Detonate(Object level, Object explosion, List<Object> entities, List<Object> affectedBlocks) {
            super(level, explosion);
            this.entities = entities;
            this.affectedBlocks = affectedBlocks;
        }
        public List<Object> getAffectedEntities() { return entities; }
        public List<Object> getAffectedBlocks() { return affectedBlocks; }
    }
}
