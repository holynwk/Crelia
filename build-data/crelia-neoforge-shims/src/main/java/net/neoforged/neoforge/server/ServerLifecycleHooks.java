/*
 * Crelia-NeoForge: Compile-only shim for net.neoforged.neoforge.server.ServerLifecycleHooks.
 * At runtime, the real ServerLifecycleHooks is loaded by FML class loader.
 */
package net.neoforged.neoforge.server;

/**
 * Compile-only shim. Do NOT use at runtime — FML loads the real ServerLifecycleHooks.
 */
public final class ServerLifecycleHooks {
    private ServerLifecycleHooks() { throw new UnsupportedOperationException("shim"); }

    public static void handleServerAboutToStart(Object server) {
        // shim — real implementation fires ServerAboutToStartEvent
    }

    public static void handleServerStarting(Object server) {
        // shim — real implementation fires ServerStartingEvent
    }

    public static void handleServerStarted(Object server) {
        // shim — real implementation fires ServerStartedEvent
    }

    public static void handleServerStopping(Object server) {
        // shim — real implementation fires ServerStoppingEvent
    }

    public static void handleServerStopped(Object server) {
        // shim — real implementation fires ServerStoppedEvent
    }

    public static void expectServerStopped() {
        // shim — throws if server is not stopped (used in finally blocks)
    }
}
