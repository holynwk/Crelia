package net.neoforged.neoforge.event.level;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public abstract class LevelEvent extends Event {
    private final Object level;

    public LevelEvent(Object level) {
        this.level = level;
    }

    public Object getLevel() {
        return level;
    }

    public static class Load extends LevelEvent {
        public Load(Object level) { super(level); }
    }

    public static class Unload extends LevelEvent {
        public Unload(Object level) { super(level); }
    }

    public static class Save extends LevelEvent {
        public Save(Object level) { super(level); }
    }

    public static class CreateSpawnPosition extends LevelEvent implements ICancellableEvent {
        private final Object settings;

        public CreateSpawnPosition(Object level, Object settings) {
            super(level);
            this.settings = settings;
        }

        public Object getSettings() { return settings; }
    }
}
