package net.neoforged.neoforge.event.tick;

import java.util.function.BooleanSupplier;
import net.neoforged.bus.api.Event;

public abstract class LevelTickEvent extends Event {
    private final Object level;
    private final BooleanSupplier haveTime;
    private LevelTickEvent(Object level, BooleanSupplier haveTime) {
        this.level = level;
        this.haveTime = haveTime;
    }
    public Object getLevel() { return level; }
    public BooleanSupplier haveTime() { return haveTime; }

    public static class Pre extends LevelTickEvent {
        public Pre(BooleanSupplier haveTime, Object level) { super(level, haveTime); }
    }
    public static class Post extends LevelTickEvent {
        public Post(BooleanSupplier haveTime, Object level) { super(level, haveTime); }
    }
}
