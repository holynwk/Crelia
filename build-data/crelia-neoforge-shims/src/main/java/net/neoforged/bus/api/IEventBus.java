package net.neoforged.bus.api;

import java.util.function.Consumer;

public interface IEventBus {
    <T extends Event> void post(T event);
    void addListener(Object listener);
    <T extends Event> void addListener(Consumer<T> consumer);
}