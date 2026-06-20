package net.neoforged.neoforge.event.entity;

import net.neoforged.bus.api.Event;

public abstract class EntityEvent extends Event {
    private final Object entity;

    public EntityEvent(Object entity) {
        this.entity = entity;
    }

    public Object getEntity() {
        return entity;
    }

    public static class EntityConstructing extends EntityEvent {
        public EntityConstructing(Object entity) {
            super(entity);
        }
    }

    public static class EnteringSection extends EntityEvent {
        private final long packedOldPos;
        private final long packedNewPos;

        public EnteringSection(Object entity, long packedOldPos, long packedNewPos) {
            super(entity);
            this.packedOldPos = packedOldPos;
            this.packedNewPos = packedNewPos;
        }

        public long getPackedOldPos() { return packedOldPos; }
        public long getPackedNewPos() { return packedNewPos; }
        public Object getOldPos() { return null; }
        public Object getNewPos() { return null; }
        public boolean didChunkChange() {
            return (int)(packedOldPos >> 42) != (int)(packedNewPos >> 42) || (int)(packedOldPos >> 4 & 0x3FFFFF) != (int)(packedNewPos >> 4 & 0x3FFFFF);
        }
    }

    public static class Size extends EntityEvent {
        private final Object pose;
        private final Object oldSize;
        private Object newSize;

        public Size(Object entity, Object pose, Object size) {
            this(entity, pose, size, size);
        }

        public Size(Object entity, Object pose, Object oldSize, Object newSize) {
            super(entity);
            this.pose = pose;
            this.oldSize = oldSize;
            this.newSize = newSize;
        }

        public Object getPose() { return pose; }
        public Object getOldSize() { return oldSize; }
        public Object getNewSize() { return newSize; }
        public void setNewSize(Object size) { this.newSize = size; }
    }
}
