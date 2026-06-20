package net.neoforged.neoforge.event.tick;

import net.neoforged.bus.api.Event;

public abstract class PlayerTickEvent extends Event {
    private final Object player;
    private final Object level;
    private PlayerTickEvent(Object player) {
        this.player = player;
        this.level = null;
    }
    public Object getPlayer() { return player; }
    public Object getLevel() { return level; }

    public static class Pre extends PlayerTickEvent {
        public Pre(Object player) { super(player); }
    }
    public static class Post extends PlayerTickEvent {
        public Post(Object player) { super(player); }
    }
}
