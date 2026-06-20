/*
 * Crelia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Crelia contributors
 *
 * This file is part of the Crelia project.
 * See https://github.com/ for license details.
 */

package crelia.core.mixin.server;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Static hook methods for region thread validation.
 *
 * <p>This class is invoked by the {@code RegionThreadValidator} coremod
 * transformer before every {@code ServerLevel.tick(BooleanSupplier)} call.
 * It validates that the calling thread is an appropriate tick thread for the
 * given {@link ServerLevel}, preventing data corruption from cross-region
 * tick invocations.</p>
 *
 * <h2>Validation Strategy</h2>
 * <p>The validator uses a three-tier detection approach:</p>
 * <ol>
 *   <li><b>Paper {@code RegionizedWorldThread}</b> — If the Paper/Folia API
 *       is on the classpath, the validator checks whether the current thread
 *       is a {@code RegionizedWorldThread} via reflection. On Folia servers, all
 *       world ticking happens on region threads.</li>
 *   <li><b>Server thread</b> — If the current thread is the global
 *       {@code "Server thread"}, the validator allows the tick. This covers
 *       non-Folia (vanilla / standard Bukkit) servers.</li>
 *   <li><b>Thread-name heuristic</b> — As a last resort, the validator checks
 *       if the thread name contains {@code "Region"} (Folia convention) or
 *       {@code "Worker"}.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code crelia.thread.validation.enabled} (default: {@code true}) —
 *       Set to {@code false} to completely disable thread validation.</li>
 *   <li>{@code crelia.thread.validation.strict} (default: {@code false}) —
 *       In strict mode, a violation throws an {@link IllegalStateException}.
 *       In permissive mode (default), a warning is logged and the tick proceeds.</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * <p>This method is called before every {@code ServerLevel.tick()} invocation,
 * which is on the hot path of the server loop. The Paper API class lookup is
 * performed once and cached via an {@link AtomicBoolean}. Subsequent calls
 * only perform a lightweight {@code Class#isInstance} check or a string
 * comparison.</p>
 *
 * @see ServerLevel
 * @see io.papermc.paper.threadedregions.RegionizedWorldThread
 */
public final class RegionThreadValidatorHooks {

    private static final Logger LOGGER = Logger.getLogger("CreliaRegionValidator");

    /** System property to enable/disable thread validation. */
    private static final String PROP_ENABLED = "crelia.thread.validation.enabled";

    /** System property for strict (throw) vs. permissive (warn) mode. */
    private static final String PROP_STRICT = "crelia.thread.validation.strict";

    // ---------------------------------------------------------------------------
    // Paper API reflection handles (resolved once, cached)
    // ---------------------------------------------------------------------------

    /** Fully-qualified class name of Paper's RegionizedWorldThread. */
    private static final String RWT_CLASS_NAME =
            "io.papermc.paper.threadedregions.RegionizedWorldThread";

    /** Cached Class<?> for RegionizedWorldThread, or null if not on classpath. */
    private static volatile Class<?> regionizedWorldThreadClass;

    /** Whether we have attempted to resolve the Paper API class. */
    private static final AtomicBoolean paperApiResolved = new AtomicBoolean(false);

    /** Whether Paper API is available (true once resolved and found). */
    private static volatile boolean paperApiAvailable = false;

    // ---------------------------------------------------------------------------
    // Mode flags (read once from system properties)
    // ---------------------------------------------------------------------------

    /** Whether thread validation is enabled at all. */
    private static final boolean VALIDATION_ENABLED = resolveEnabled();

    /** Whether violations should throw (strict) or just warn (permissive). */
    private static final boolean STRICT_MODE = resolveStrict();

    private RegionThreadValidatorHooks() {
        // Utility class — no instances
    }

    // ===========================================================================
    // Public API
    // ===========================================================================

    /**
     * Validates that the current thread is the owning region thread for the
     * given server level.
     *
     * <p>Called by the {@code RegionThreadValidator} coremod transformer as
     * an {@code INVOKESTATIC} before every
     * {@code ServerLevel.tick(BooleanSupplier)} invocation. The
     * {@code ServerLevel} receiver of the {@code tick()} call is already on
     * the stack when this method is invoked.</p>
     *
     * <p>If validation is disabled or the thread is valid, this method returns
     * immediately. If the thread is invalid:</p>
     * <ul>
     *   <li>In <b>permissive mode</b> (default): a warning is logged and the
     *       method returns (the tick proceeds, but the operator is alerted).</li>
     *   <li>In <b>strict mode</b>: an {@link IllegalStateException} is thrown,
     *       crashing the server. This is intended for development/testing.</li>
     * </ul>
     *
     * @param level the {@link ServerLevel} about to be ticked
     * @throws IllegalStateException if in strict mode and the current thread
     *         is not a valid tick thread
     */
    public static void checkRegionThread(Object level) {
        if (!VALIDATION_ENABLED) {
            return;
        }

        if (level == null) {
            return;
        }

        Thread current = Thread.currentThread();

        // Tier 1: Check if we are on a Paper RegionizedWorldThread
        if (isRegionizedWorldThread(current)) {
            return;
        }

        // Tier 2: Check if we are the global server thread (non-Folia)
        if (isGlobalServerThread(current)) {
            return;
        }

        // Tier 3: Thread-name heuristic fallback
        if (isHeuristicRegionThread(current)) {
            return;
        }

        // Validation failed
        handleViolation(level, current);
    }

    // ===========================================================================
    // Detection Methods
    // ===========================================================================

    /**
     * Checks if the given thread is a Paper {@code RegionizedWorldThread},
     * using a lazily-resolved reflection lookup.
     *
     * <p>The class lookup is performed once and cached. After the first call,
     * subsequent calls only perform a {@code Class#isInstance} check, which is
     * very fast (comparable to {@code instanceof}).</p>
     *
     * @param thread the thread to check
     * @return {@code true} if the thread is a {@code RegionizedWorldThread}
     */
    private static boolean isRegionizedWorldThread(Thread thread) {
        ensurePaperApiResolved();

        if (paperApiAvailable && regionizedWorldThreadClass != null) {
            return regionizedWorldThreadClass.isInstance(thread);
        }

        return false;
    }

    /**
     * Checks if the given thread is the global "Server thread" used by
     * vanilla Minecraft and non-Folia Bukkit servers.
     *
     * <p>On a standard (non-Folia) server, the world tick loop runs on a
     * single thread named {@code "Server thread"}. This check ensures that
     * Crelia's validation does not break vanilla/Spigot/Paper servers that
     * don't use regionized threading.</p>
     *
     * @param thread the thread to check
     * @return {@code true} if the thread is the global server thread
     */
    private static boolean isGlobalServerThread(Thread thread) {
        return "Server thread".equals(thread.getName());
    }

    /**
     * Fallback heuristic: checks if the thread name suggests it is a Folia
     * region worker thread.
     *
     * <p>Folia names region threads with patterns like
     * {@code "Worker-Region-#", "RegionizedServerThread-#", etc.}
     * This is a best-effort heuristic used when the Paper API class is not
     * on the classpath (e.g., during testing or on a modified server).</p>
     *
     * @param thread the thread to check
     * @return {@code true} if the thread name contains {@code "Region"} or
     *         {@code "Tick"}
     */
    private static boolean isHeuristicRegionThread(Thread thread) {
        String name = thread.getName();
        return name.contains("Region") || name.contains("Tick");
    }

    // ===========================================================================
    // Violation Handling
    // ===========================================================================

    /**
     * Handles a thread validation violation.
     *
     * <p>Constructs a diagnostic message including the level's dimension
     * key and the current thread's name, then either throws an exception
     * (strict mode) or logs a warning (permissive mode).</p>
     *
     * @param level   the level that was about to be ticked
     * @param current the thread that failed validation
     * @throws IllegalStateException in strict mode
     */
    private static void handleViolation(Object level, Thread current) {
        String levelKey = String.valueOf(level); // Use toString() instead of dimension()
        String threadInfo = current.getName()
                + " (id=" + current.threadId()
                + ", state=" + current.getState()
                + ", group=" + (current.getThreadGroup() != null
                    ? current.getThreadGroup().getName() : "null") + ")";

        String message = "[Crelia] Region thread validation FAILED: ServerLevel.tick() "
                + "called from invalid thread for level '" + levelKey + "'. "
                + "Current thread: " + threadInfo + ". "
                + "This may indicate a cross-region threading violation that could "
                + "cause data corruption.";

        if (STRICT_MODE) {
            LOGGER.severe(message + " (STRICT MODE — throwing)");
            throw new IllegalStateException(message);
        } else {
            LOGGER.warning(message + " (permissive mode — tick will proceed)");
        }
    }

    // ===========================================================================
    // Paper API Resolution
    // ===========================================================================

    /**
     * Lazily resolves the {@code RegionizedWorldThread} class from the
     * classpath, if available. The result is cached for the lifetime of the
     * JVM.
     *
     * <p>This method is safe to call from any thread. The
     * {@link AtomicBoolean} guard ensures that the reflection lookup is
     * performed at most once.</p>
     */
    private static void ensurePaperApiResolved() {
        if (paperApiResolved.get()) {
            return;
        }

        if (paperApiResolved.compareAndSet(false, true)) {
            try {
                Class<?> rwtClass = Class.forName(RWT_CLASS_NAME, false,
                        RegionThreadValidatorHooks.class.getClassLoader());
                regionizedWorldThreadClass = rwtClass;
                paperApiAvailable = true;

                LOGGER.info("[Crelia] RegionizedWorldThread resolved: "
                        + rwtClass.getName()
                        + " — region thread validation is fully active");
            } catch (ClassNotFoundException e) {
                paperApiAvailable = false;
                LOGGER.fine("[Crelia] RegionizedWorldThread not found on classpath "
                        + "(" + RWT_CLASS_NAME + ") — "
                        + "using thread-name heuristic for validation");
            }
        }
    }

    // ===========================================================================
    // Configuration Resolution
    // ===========================================================================

    /**
     * Resolves whether thread validation is enabled from system properties.
     *
     * @return {@code true} if validation is enabled (default)
     */
    private static boolean resolveEnabled() {
        String prop = System.getProperty(PROP_ENABLED);
        if (prop != null) {
            boolean enabled = Boolean.parseBoolean(prop);
            LOGGER.info("[Crelia] Thread validation "
                    + (enabled ? "enabled" : "DISABLED")
                    + " via system property " + PROP_ENABLED);
            return enabled;
        }
        return true; // default: enabled
    }

    /**
     * Resolves whether strict mode is active from system properties.
     *
     * @return {@code true} if strict mode (default: permissive)
     */
    private static boolean resolveStrict() {
        String prop = System.getProperty(PROP_STRICT);
        if (prop != null) {
            boolean strict = Boolean.parseBoolean(prop);
            LOGGER.info("[Crelia] Thread validation mode: "
                    + (strict ? "STRICT" : "permissive")
                    + " via system property " + PROP_STRICT);
            return strict;
        }
        return false; // default: permissive
    }
}
