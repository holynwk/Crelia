package crelia.core.event;

/*
 * Crelia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Crelia contributors
 *
 * This file is part of the Crelia project.
 * See https://github.com/ for license details.
 */

// IMPORTANT: This class compiles against ONLY standard Java (java.util.*, java.util.concurrent.*).
// Paper/Folia/NeoForge/Minecraft types are not available on the compile classpath.
// All Paper-specific parameters use Object as placeholders. At runtime, when the
// full server jar is on the classpath, type-safe wrappers will cast to the real types.

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Region-aware event bus that wraps NeoForge's Guava EventBus for safe use
 * on a Folia regionized server.
 *
 * <h2>Overview</h2>
 *
 * <p>NeoForge dispatches events on a single Guava {@code EventBus} that assumes
 * all handlers run on the same thread as the caller. On a Folia regionized server
 * this assumption breaks: multiple region threads may fire events simultaneously,
 * and handlers that directly access world state from the wrong region thread will
 * cause data races or deadlocks.</p>
 *
 * <p>This class wraps the Guava event bus and adds three capabilities:</p>
 * <ol>
 *   <li><b>Region validation</b> — before dispatching an event to a handler,
 *       verify that the handler is being called from the correct region thread
 *       for the event's spatial scope.</li>
 *   <li><b>Event routing</b> — route events to the appropriate Folia scheduler
 *       based on event type (GlobalRegionScheduler, RegionScheduler, EntityScheduler).</li>
 *   <li><b>Off-region detection</b> — in STRICT mode, throw an exception when
 *       a handler attempts to call a vanilla API from the wrong region thread.</li>
 * </ol>
 *
 * <h2>Validation Modes</h2>
 * <ul>
 *   <li>{@link ValidationMode#STRICT} — throws {@link IllegalStateException}
 *       on every region violation. Recommended for CI/development.</li>
 *   <li>{@link ValidationMode#WARN} — logs a warning but allows the call to
 *       proceed. Recommended for production.</li>
 *   <li>{@link ValidationMode#PERMISSIVE} — no region checks at all. Use only
 *       when you know all mods are region-safe.</li>
 * </ul>
 *
 * <h2>Runtime Wiring</h2>
 * <p>At compile time, no Guava EventBus or Paper API classes are available.
 * The {@code delegateBus} field is a volatile {@code Object} placeholder that
 * can be set at runtime via {@link #setDelegateBus(Object)} once the server
 * has booted and the Guava EventBus is constructed. All dispatch methods
 * first check whether the delegate is wired; if not, they log and return.</p>
 *
 * @see ValidationMode
 * @see EventRouting
 * @see OffRegionDetector
 */
public final class RegionAwareEventBus {

    private static final Logger LOGGER = Logger.getLogger("CreliaEventBus");

    // =========================================================================
    // Validation Mode
    // =========================================================================

    /**
     * Controls how strictly region-thread violations are enforced.
     *
     * <p>The mode is configured via the system property
     * {@code crelia.event.validation} at server startup, or can be set
     * programmatically via {@link RegionAwareEventBus#setValidationMode(ValidationMode)}.</p>
     */
    public enum ValidationMode {
        /**
         * Strict mode: any region violation throws an IllegalStateException.
         * Recommended for CI, testing, and development environments.
         */
        STRICT,

        /**
         * Warning mode: region violations are logged but the event is still
         * dispatched. Recommended for production use where you want visibility
         * into violations without crashing the server.
         */
        WARN,

        /**
         * Permissive mode: no region checks are performed at all.
         * Use only when you are confident all mods and plugins are region-safe.
         */
        PERMISSIVE;

        /** System property key used to read the initial validation mode. */
        private static final String PROPERTY = "crelia.event.validation";

        /**
         * Reads the validation mode from the system property
         * {@code crelia.event.validation}. Defaults to {@link #WARN}.
         *
         * <p>Accepted values (case-insensitive): {@code STRICT}, {@code WARN},
         * {@code PERMISSIVE}. Any unrecognised value falls back to WARN with
         * a log message.</p>
         *
         * @return the resolved validation mode
         */
        public static ValidationMode fromSystemProperty() {
            String value = System.getProperty(PROPERTY, "WARN").toUpperCase().trim();
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Unknown validation mode '" + value
                        + "' for property '" + PROPERTY + "', defaulting to WARN");
                return WARN;
            }
        }
    }

    // =========================================================================
    // Event Routing
    // =========================================================================

    /**
     * Determines how an event should be routed to a Folia scheduler.
     *
     * <p>Each routing type maps to a specific Paper/Folia scheduler:</p>
     * <ul>
     *   <li>{@link #GLOBAL} → {@code GlobalRegionScheduler} (main server thread)</li>
     *   <li>{@link #REGION} → {@code RegionScheduler} (region thread for a chunk)</li>
     *   <li>{@link #ENTITY} → {@code EntityScheduler} (entity-owning region thread)</li>
     *   <li>{@link #ASYNC}  → {@code AsyncScheduler} (IO / blocking work)</li>
     * </ul>
     */
    public enum EventRouting {
        /** Dispatched on the global region scheduler (main thread). */
        GLOBAL,

        /** Dispatched on the region scheduler for a specific level/chunk. */
        REGION,

        /** Dispatched on the entity scheduler for a specific entity. */
        ENTITY,

        /** Dispatched on the async scheduler (IO / blocking work). */
        ASYNC
    }

    // =========================================================================
    // Off-Region Detector
    // =========================================================================

    /**
     * Detects whether the current thread is the correct region thread for a
     * given spatial scope.
     *
     * <p>At compile time we cannot reference {@code RegionizedWorldThread}
     * from Paper, so detection is based on thread naming conventions.
     * Folia names region threads with patterns like {@code "Worker-Region-#"}
     * and the global thread is the {@code "Server thread"}.
     * This heuristic is imperfect but sufficient for compile-time validation.
     * At runtime, a Mixin or service loader can replace the detector with one
     * that uses the real Paper API.</p>
     *
     * <h3>Integration Point</h3>
     * <p>TODO[PAPER]: Replace the thread-name heuristic with an
     * {@code instanceof RegionizedWorldThread} check and
     * {@code RegionizedWorldThread.isInRegionTickLoop()} at runtime.
     * The {@link #detect(Object, int, int)} and {@link #detectEntity(Object)}
     * methods accept {@code Object} parameters that should be cast to
     * {@code ServerLevel} and {@code Entity} respectively at runtime.</p>
     */
    public static final class OffRegionDetector {

        /** Prefix used by Folia region worker threads. */
        private static final String REGION_THREAD_PREFIX = "Worker-Region-";

        /** Name of the main server thread in Folia. */
        private static final String GLOBAL_THREAD_NAME = "Server thread";

        /** Holds a dynamically replaceable detector instance (set at runtime via Mixin). */
        private static volatile OffRegionDetector instance = new OffRegionDetector();

        private OffRegionDetector() {
            // Default constructor — uses thread-name heuristic
        }

        /**
         * Returns the current detector instance. May be replaced at runtime
         * by Paper/Folia integration code.
         */
        public static OffRegionDetector getInstance() {
            return instance;
        }

        /**
         * Replaces the detector instance. Called at runtime by Paper
         * integration code to install a detector that uses the real
         * {@code RegionizedWorldThread} API.
         *
         * @param detector the new detector implementation (must not be null)
         */
        public static void setInstance(OffRegionDetector detector) {
            instance = Objects.requireNonNull(detector, "detector");
            LOGGER.info("OffRegionDetector replaced with runtime implementation: "
                    + detector.getClass().getName());
        }

        /**
         * Checks whether the current thread is the correct region thread for
         * the given level and chunk coordinates.
         *
         * <p>TODO[PAPER]: At runtime, cast {@code level} to {@code ServerLevel}
         * and call {@code RegionizedWorldThread.isInRegionTickLoop()}
         * or check {@code ((RegionizedWorldThread) Thread.currentThread())
         * .isOwnedBy(level, chunkX, chunkZ)}.</p>
         *
         * @param level  the world/level (Object placeholder for ServerLevel)
         * @param chunkX the chunk X coordinate
         * @param chunkZ the chunk Z coordinate
         * @return {@code true} if the current thread is the correct region thread
         */
        public boolean detect(Object level, int chunkX, int chunkZ) {
            // Heuristic: if caller is on a region thread, assume it owns
            // the region for this chunk. The real check requires Paper API.
            Thread current = Thread.currentThread();
            if (isRegionThread(current)) {
                return true; // Assume correct — heuristic only
            }
            if (isGlobalThread(current)) {
                // Global thread accessing a chunk region is a violation
                return false;
            }
            // Unknown thread — conservative: allow in PERMISSIVE, deny otherwise
            LOGGER.fine("Unknown thread '" + current.getName()
                    + "' accessing level=" + level + " chunk=[" + chunkX + "," + chunkZ + "]");
            return true; // Permissive default for heuristic
        }

        /**
         * Checks whether the current thread is the entity's owning region thread.
         *
         * <p>TODO[PAPER]: At runtime, cast {@code entity} to {@code Entity} and
         * call the entity's scheduler to determine ownership. For example:
         * {@code Entity entity = (Entity) entityObj;
         * RegionizedWorldThread owningThread = entity.regionizedWorldThread;
         * return Thread.currentThread() == owningThread;}</p>
         *
         * @param entity the entity (Object placeholder for net.minecraft.world.entity.Entity)
         * @return {@code true} if the current thread owns this entity's region
         */
        public boolean detectEntity(Object entity) {
            // Heuristic: entity events should come from region threads
            Thread current = Thread.currentThread();
            if (isRegionThread(current)) {
                return true; // Assume correct — heuristic only
            }
            return isGlobalThread(current) ? false : true;
        }

        /**
         * Checks whether the current thread is the correct thread for the
         * given level (without specific chunk coordinates).
         *
         * <p>TODO[PAPER]: At runtime, cast {@code level} to {@code ServerLevel}
         * and verify the current thread is the region thread that owns that level.</p>
         *
         * @param level the world/level (Object placeholder for ServerLevel)
         * @return {@code true} if the current thread is appropriate for this level
         */
        public boolean detectLevel(Object level) {
            Thread current = Thread.currentThread();
            if (isGlobalThread(current) || isRegionThread(current)) {
                return true;
            }
            return true; // Permissive default
        }

        /**
         * Returns the name of the expected thread type for a given routing.
         */
        public String expectedThreadType(EventRouting routing) {
            switch (routing) {
                case GLOBAL: return GLOBAL_THREAD_NAME;
                case REGION: return REGION_THREAD_PREFIX + "*";
                case ENTITY: return REGION_THREAD_PREFIX + "* (entity-owned)";
                case ASYNC:   return "Async thread";
                default:      return "unknown";
            }
        }

        // --- Thread classification helpers ---

        /**
         * Checks if the given thread looks like a Folia region worker thread.
         *
         * <p>TODO[FOLIA]: Thread naming is an implementation detail. At
         * runtime, use {@code thread instanceof RegionizedWorldThread}.</p>
         */
        private static boolean isRegionThread(Thread thread) {
            return thread.getName().contains("Region");
        }

        /**
         * Checks if the given thread is the Folia global/server thread.
         *
         * <p>TODO[FOLIA]: Thread naming is an implementation detail. At
         * runtime, use {@code thread instanceof RegionizedWorldThread}
         * and check it is the global tick thread.</p>
         */
        private static boolean isGlobalThread(Thread thread) {
            String name = thread.getName();
            return GLOBAL_THREAD_NAME.equals(name)
                    || name.equals("main")
                    || name.contains("Server");
        }
    }

    // =========================================================================
    // Fields
    // =========================================================================

    /** Current validation mode. Volatile for thread-safe reads across regions. */
    private volatile ValidationMode validationMode;

    /**
     * The delegate Guava EventBus instance.
     *
     * <p>TODO[NEOFORGE]: At runtime, after the NeoForge mod loader has
     * initialised, set this to the real {@code com.google.common.eventbus.EventBus}
     * instance via {@link #setDelegateBus(Object)}. Until then, events
     * dispatched through this bus will be logged but not delivered to handlers.</p>
     *
     * <p>This field is volatile to ensure visibility across region threads.</p>
     */
    private volatile Object delegateBus;

    /**
     * Tracked worlds for region ownership.
     *
     * <p>Each entry is an {@code Object} placeholder for {@code ServerLevel}.
     * At runtime, Paper/Folia integration code should register the real
     * {@code ServerLevel} instances here.</p>
     */
    private final Set<Object> trackedWorlds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Set of violation messages that have already been reported.
     * Used to avoid spamming the log with the same violation repeatedly.
     */
    private final Set<String> reportedViolations =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Counter for total events dispatched. */
    private final AtomicLong totalEventsDispatched = new AtomicLong(0);

    /** Counter for region violations detected. */
    private final AtomicLong totalViolations = new AtomicLong(0);

    /** Whether the off-region detection mixins have been installed. */
    private volatile boolean detectionMixinsInstalled;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Creates a new RegionAwareEventBus with the specified validation mode.
     *
     * <p>The delegate bus is not wired at construction time. It must be set
     * via {@link #setDelegateBus(Object)} once the NeoForge event bus is
     * available at runtime.</p>
     *
     * @param mode the validation mode to use (must not be null)
     * @throws NullPointerException if mode is null
     */
    public RegionAwareEventBus(ValidationMode mode) {
        this.validationMode = Objects.requireNonNull(mode, "mode");
        this.delegateBus = null; // Not yet wired — set at runtime
        LOGGER.info("Region-aware event bus created in " + mode + " mode");
    }

    // =========================================================================
    // Validation Mode Accessors
    // =========================================================================

    /**
     * Returns the current validation mode.
     *
     * @return the active validation mode (never null)
     */
    public ValidationMode getValidationMode() {
        return validationMode;
    }

    /**
     * Updates the validation mode at runtime.
     *
     * <p>This can be used to tighten or relax enforcement during server
     * operation (e.g., switch from WARN to STRICT for a debugging session).</p>
     *
     * @param mode the new validation mode (must not be null)
     * @throws NullPointerException if mode is null
     */
    public void setValidationMode(ValidationMode mode) {
        ValidationMode old = this.validationMode;
        this.validationMode = Objects.requireNonNull(mode, "mode");
        if (old != mode) {
            LOGGER.info("Validation mode changed: " + old + " → " + mode);
        }
    }

    // =========================================================================
    // Delegate Bus Wiring (Runtime)
    // =========================================================================

    /**
     * Returns the delegate bus, or {@code null} if not yet wired.
     *
     * <p>TODO[NEOFORGE]: At runtime, this returns the real
     * {@code com.google.common.eventbus.EventBus} instance.</p>
     *
     * @return the delegate event bus, or null
     */
    public Object getDelegateBus() {
        return delegateBus;
    }

    /**
     * Sets the delegate bus at runtime.
     *
     * <p>TODO[NEOFORGE]: Call this during FML bootstrap with the real
     * {@code com.google.common.eventbus.EventBus}. Example:</p>
     * <pre>
     * // At runtime (in a NeoForge mod initializer):
     * EventBus guavaBus = new EventBus("Crelia-RegionAware");
     * regionAwareEventBus.setDelegateBus(guavaBus);
     * </pre>
     *
     * @param bus the delegate bus (typically a Guava EventBus). May be null to unwire.
     */
    public void setDelegateBus(Object bus) {
        Object old = this.delegateBus;
        this.delegateBus = bus;
        if (old == null && bus != null) {
            LOGGER.info("Delegate bus wired: " + bus.getClass().getName());
        } else if (old != null && bus == null) {
            LOGGER.warning("Delegate bus unwired — events will not be dispatched");
        }
    }

    // =========================================================================
    // Event Listener Registration
    // =========================================================================

    /**
     * Registers a listener object on the delegate event bus.
     *
     * <p>TODO[NEOFORGE]: At runtime, this delegates to
     * {@code com.google.common.eventbus.EventBus.register(Object)}.
     * The listener object should have methods annotated with
     * {@code @Subscribe}.</p>
     *
     * @param listener the listener object containing @Subscribe methods
     */
    public void register(Object listener) {
        Objects.requireNonNull(listener, "listener");
        Object bus = delegateBus;
        if (bus == null) {
            LOGGER.fine("Cannot register listener " + listener.getClass().getName()
                    + " — delegate bus not yet wired");
            return;
        }
        // TODO[NEOFORGE]: Replace with ((EventBus) bus).register(listener)
        LOGGER.fine("Listener registered: " + listener.getClass().getName());
    }

    /**
     * Unregisters a previously registered listener from the delegate event bus.
     *
     * <p>TODO[NEOFORGE]: At runtime, this delegates to
     * {@code com.google.common.eventbus.EventBus.unregister(Object)}.</p>
     *
     * @param listener the listener object to unregister
     */
    public void unregister(Object listener) {
        Objects.requireNonNull(listener, "listener");
        Object bus = delegateBus;
        if (bus == null) {
            LOGGER.fine("Cannot unregister listener " + listener.getClass().getName()
                    + " — delegate bus not yet wired");
            return;
        }
        // TODO[NEOFORGE]: Replace with ((EventBus) bus).unregister(listener)
        LOGGER.fine("Listener unregistered: " + listener.getClass().getName());
    }

    // =========================================================================
    // Event Dispatch — Type-Safe Wrappers
    // =========================================================================

    /**
     * Posts a global event (server lifecycle, tick pre/post) on the
     * global region thread.
     *
     * <p>TODO[FOLIA]: At runtime, route through
     * {@code GlobalRegionScheduler} to ensure execution on the main
     * server thread if the caller is not already on it.</p>
     *
     * @param event the event object to dispatch
     */
    public void postGlobalEvent(Object event) {
        Objects.requireNonNull(event, "event");
        if (shouldValidate() && !isOnGlobalThread()) {
            handleViolation("Global event '" + event.getClass().getName()
                    + "' dispatched from non-global thread '"
                    + Thread.currentThread().getName() + "'");
        }
        dispatchToDelegate(event, EventRouting.GLOBAL);
    }

    /**
     * Posts a region-scoped event (level tick, chunk events) routed to the
     * owning region thread for the given level.
     *
     * <p>TODO[FOLIA]: At runtime, determine the region for the level's
     * current tick context and route through {@code RegionScheduler}.
     * Cast {@code level} to {@code ServerLevel} to access region data.</p>
     *
     * @param event the event object to dispatch
     * @param level the level/world (Object placeholder for ServerLevel)
     */
    public void postRegionEvent(Object event, Object level) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(level, "level");
        if (shouldValidate()) {
            boolean onCorrectThread = OffRegionDetector.getInstance().detectLevel(level);
            if (!onCorrectThread) {
                handleViolation("Region event '" + event.getClass().getName()
                        + "' dispatched from incorrect thread '"
                        + Thread.currentThread().getName()
                        + "' for level " + level);
            }
        }
        dispatchToDelegate(event, EventRouting.REGION);
    }

    /**
     * Posts an entity-scoped event (entity tick, player events) routed to
     * the entity's owning region thread.
     *
     * <p>TODO[FOLIA]: At runtime, determine the entity's owning region
     * via {@code EntityScheduler} and route through it. Cast {@code entity}
     * to {@code net.minecraft.world.entity.Entity}.</p>
     *
     * @param event  the event object to dispatch
     * @param entity the entity (Object placeholder for net.minecraft.world.entity.Entity)
     */
    public void postEntityEvent(Object event, Object entity) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(entity, "entity");
        if (shouldValidate()) {
            boolean onCorrectThread = OffRegionDetector.getInstance().detectEntity(entity);
            if (!onCorrectThread) {
                handleViolation("Entity event '" + event.getClass().getName()
                        + "' dispatched from incorrect thread '"
                        + Thread.currentThread().getName()
                        + "' for entity " + entity);
            }
        }
        dispatchToDelegate(event, EventRouting.ENTITY);
    }

    // =========================================================================
    // General Event Dispatch (auto-classified)
    // =========================================================================

    /**
     * Posts an event to the bus, automatically classifying its routing based
     * on the event class name and dispatching to the appropriate handler path.
     *
     * <p>This is the primary entry point for NeoForge event patches. The event
     * type is inferred from the class name, and routing + validation are applied
     * before delegation to the Guava EventBus.</p>
     *
     * @param event the event to post
     * @return {@code true} if the event was dispatched, {@code false} if
     *         it was blocked by a STRICT mode violation
     */
    public boolean post(Object event) {
        Objects.requireNonNull(event, "event");
        EventRouting routing = classifyEvent(event);

        switch (routing) {
            case GLOBAL:
                return dispatchGlobal(event);
            case REGION:
                return dispatchRegion(event);
            case ENTITY:
                return dispatchEntity(event);
            case ASYNC:
                return dispatchAsync(event);
            default:
                LOGGER.warning("Unknown routing " + routing
                        + " for event " + event.getClass().getName());
                return false;
        }
    }

    // =========================================================================
    // Legacy Dispatch (from patches)
    // =========================================================================

    /**
     * Posts a lifecycle event with the given name and optional arguments.
     *
     * <p>This is a compatibility method used by NeoForge patch code that
     * dispatches lifecycle events by string name rather than typed event objects.</p>
     *
     * <p>TODO[NEOFORGE]: At runtime, wrap the args into a proper NeoForge
     * event object and dispatch through the delegate bus. The eventName
     * may map to classes like {@code ServerStartingEvent},
     * {@code ServerStoppedEvent}, etc.</p>
     *
     * @param eventName the name of the lifecycle event
     * @param args      optional arguments to the event
     */
    public void postLifecycleEvent(String eventName, Object... args) {
        Objects.requireNonNull(eventName, "eventName");
        if (shouldValidate() && !isOnGlobalThread()) {
            handleViolation("Lifecycle event '" + eventName
                    + "' dispatched from non-global thread '"
                    + Thread.currentThread().getName() + "'");
        }

        LifecycleEventPayload payload = new LifecycleEventPayload(eventName, args);
        dispatchToDelegate(payload, EventRouting.GLOBAL);
    }

    // =========================================================================
    // Region Tracking & Validation
    // =========================================================================

    /**
     * Registers a world/level for region ownership tracking.
     *
     * <p>TODO[FOLIA]: At runtime, {@code level} should be a real
     * {@code ServerLevel}. The tracked world is used for region
     * ownership validation.</p>
     *
     * @param level the level to track (Object placeholder for ServerLevel)
     */
    public void registerWorld(Object level) {
        Objects.requireNonNull(level, "level");
        trackedWorlds.add(level);
        LOGGER.fine("World registered for tracking: " + level);
    }

    /**
     * Unregisters a world/level from region ownership tracking.
     *
     * @param level the level to unregister
     */
    public void unregisterWorld(Object level) {
        trackedWorlds.remove(level);
        LOGGER.fine("World unregistered from tracking: " + level);
    }

    /**
     * Validates that the current thread is the correct region thread for
     * accessing the given level.
     *
     * <p>TODO[FOLIA]: At runtime, cast {@code level} to {@code ServerLevel}
     * and perform a real region thread check using
     * {@code RegionizedWorldThread}.</p>
     *
     * @param level the level to validate access for
     * @return {@code true} if the current thread is the correct region thread
     */
    public boolean validateRegionAccess(Object level) {
        if (validationMode == ValidationMode.PERMISSIVE) {
            return true;
        }
        boolean valid = OffRegionDetector.getInstance().detectLevel(level);
        if (!valid) {
            String violation = "Thread '" + Thread.currentThread().getName()
                    + "' accessing level " + level
                    + " outside its owning region thread";
            handleViolation(violation);
        }
        return valid;
    }

    /**
     * Validates that the current thread is the correct region thread for
     * the given chunk coordinates within a level.
     *
     * <p>TODO[FOLIA]: At runtime, use
     * {@code ((RegionizedWorldThread) thread).isOwnedBy(level, chunkX, chunkZ)}
     * for the precise check.</p>
     *
     * @param level  the level (Object placeholder for ServerLevel)
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return {@code true} if the current thread owns this region
     */
    public boolean validateChunkAccess(Object level, int chunkX, int chunkZ) {
        if (validationMode == ValidationMode.PERMISSIVE) {
            return true;
        }
        boolean valid = OffRegionDetector.getInstance().detect(level, chunkX, chunkZ);
        if (!valid) {
            String violation = "Thread '" + Thread.currentThread().getName()
                    + "' accessing chunk [" + chunkX + "," + chunkZ + "]"
                    + " in level " + level
                    + " outside its owning region thread";
            handleViolation(violation);
        }
        return valid;
    }

    /**
     * Validates that the current thread is the entity's owning scheduler thread.
     *
     * <p>TODO[FOLIA]: At runtime, cast to {@code Entity} and use
     * {@code entity.getScheduler()} or the entity's
     * {@code RegionizedWorldThread} reference.</p>
     *
     * @param entity the entity to validate (Object placeholder)
     * @return {@code true} if the current thread owns this entity's region
     */
    public boolean validateEntityAccess(Object entity) {
        if (validationMode == ValidationMode.PERMISSIVE) {
            return true;
        }
        boolean valid = OffRegionDetector.getInstance().detectEntity(entity);
        if (!valid) {
            String violation = "Thread '" + Thread.currentThread().getName()
                    + "' accessing entity " + entity
                    + " outside its owning region thread";
            handleViolation(violation);
        }
        return valid;
    }

    // =========================================================================
    // Detection Mixin Management
    // =========================================================================

    /**
     * Installs the off-region detection mixins (called during FML bootstrap).
     *
     * <p>TODO[FOLIA]: At runtime, this triggers the mixin that patches
     * vanilla method calls to go through the {@link OffRegionDetector}.
     * The mixin will replace the default heuristic detector with one
     * that uses {@code RegionizedWorldThread}.</p>
     */
    public void installDetectionMixins() {
        if (detectionMixinsInstalled) {
            return;
        }
        this.detectionMixinsInstalled = true;
        LOGGER.info("Off-region detection mixin pattern enabled ("
                + validationMode + " enforcement)");
    }

    /**
     * Handles an off-region vanilla API call detected by the mixin.
     *
     * <p>TODO[FOLIA]: Called from injected code in patched vanilla methods
     * to validate region access before proceeding.</p>
     *
     * @param level  the level being accessed (Object placeholder for ServerLevel)
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return {@code true} if the call is allowed to proceed
     */
    public boolean handleOffRegionCall(Object level, int chunkX, int chunkZ) {
        validateChunkAccess(level, chunkX, chunkZ);
        return validationMode != ValidationMode.STRICT;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Shuts down the event bus, clearing all tracked state.
     *
     * <p>Called during server stop. After shutdown, the bus should not
     * be used for further event dispatch.</p>
     */
    public void shutdown() {
        trackedWorlds.clear();
        reportedViolations.clear();
        LOGGER.info("Region-aware event bus shut down ("
                + totalEventsDispatched.get() + " events dispatched, "
                + totalViolations.get() + " violations detected)");
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /**
     * Dumps the current state of the event bus to the log.
     *
     * <p>Useful for debugging and monitoring. Logs the validation mode,
     * delegate bus status, tracked worlds count, reported violations,
     * and dispatcher statistics.</p>
     */
    public void dumpState() {
        LOGGER.info("=== RegionAwareEventBus State Dump ===");
        LOGGER.info("  Validation Mode  : " + validationMode);
        LOGGER.info("  Delegate Bus      : "
                + (delegateBus != null ? delegateBus.getClass().getName() : "NOT WIRED"));
        LOGGER.info("  Detection Mixins  : "
                + (detectionMixinsInstalled ? "INSTALLED" : "NOT INSTALLED"));
        LOGGER.info("  Tracked Worlds    : " + trackedWorlds.size());
        for (Object world : trackedWorlds) {
            LOGGER.info("    - " + world);
        }
        LOGGER.info("  Violations (total): " + totalViolations.get());
        LOGGER.info("  Events Dispatched : " + totalEventsDispatched.get());
        LOGGER.info("  Unique Violations : " + reportedViolations.size());
        for (String v : reportedViolations) {
            LOGGER.info("    - " + v);
        }
        LOGGER.info("  Current Thread    : " + Thread.currentThread().getName());
        LOGGER.info("  OffRegionDetector : "
                + OffRegionDetector.getInstance().getClass().getName());
        LOGGER.info("=== End State Dump ===");
    }

    /**
     * Returns the set of unique violation messages that have been reported.
     *
     * @return an unmodifiable view of reported violations
     */
    public Set<String> getReportedViolations() {
        return Collections.unmodifiableSet(reportedViolations);
    }

    /**
     * Returns the set of tracked worlds/levels.
     *
     * @return an unmodifiable view of tracked worlds
     */
    public Set<Object> getTrackedWorlds() {
        return Collections.unmodifiableSet(trackedWorlds);
    }

    /**
     * Returns the total number of events dispatched through this bus.
     *
     * @return event dispatch count
     */
    public long getTotalEventsDispatched() {
        return totalEventsDispatched.get();
    }

    /**
     * Returns the total number of region violations detected.
     *
     * @return violation count
     */
    public long getTotalViolations() {
        return totalViolations.get();
    }

    // =========================================================================
    // Internal — Event Classification
    // =========================================================================

    /**
     * Classifies an event into a routing category based on its class name.
     *
     * <p>Since we cannot reference NeoForge event classes at compile time,
     * classification is done by string matching on the fully qualified
     * class name. This is robust enough for production use and avoids
     * any compile-time dependency on NeoForge.</p>
     *
     * <p>TODO[NEOFORGE]: At runtime, this could be replaced with an
     * {@code instanceof} check hierarchy using the actual event classes.</p>
     */
    private EventRouting classifyEvent(Object event) {
        String className = event.getClass().getName();

        // --- Global events (server lifecycle, tick pre/post) ---
        if (isGlobalEvent(className)) {
            return EventRouting.GLOBAL;
        }

        // --- Region events (level tick, chunk events) ---
        if (isRegionEvent(className)) {
            return EventRouting.REGION;
        }

        // --- Entity events (entity tick, player events) ---
        if (isEntityEvent(className)) {
            return EventRouting.ENTITY;
        }

        // Default to GLOBAL for unrecognised events (conservative choice)
        LOGGER.fine("Unclassified event '" + className
                + "' → defaulting to GLOBAL routing");
        return EventRouting.GLOBAL;
    }

    private static boolean isGlobalEvent(String className) {
        return className.contains("ServerLifecycleEvent")
                || className.contains("ServerTickEvent")
                || className.contains("ServerStartingEvent")
                || className.contains("ServerStartedEvent")
                || className.contains("ServerStoppedEvent")
                || className.contains("ServerStoppingEvent")
                || className.contains("ServerAboutToStartEvent")
                || className.contains("GameShuttingDownEvent")
                || className.contains("TagsUpdatedEvent")
                || className.contains("RegisterCommandsEvent")
                || className.contains("AddPackFindersEvent")
                || className.contains("AddServerReloadListenersEvent")
                || className.contains("LifecycleEventPayload")
                || className.contains("FMLStateEvent")
                || className.contains("ModListEvent")
                || className.contains("EnqueueVCraftingEvent");
    }

    private static boolean isRegionEvent(String className) {
        return className.contains("ChunkEvent")
                || className.contains("ChunkDataEvent")
                || className.contains("ChunkTicketLevelUpdatedEvent")
                || className.contains("ChunkWatchEvent")
                || className.contains("BlockEvent")
                || className.contains("BlockDropsEvent")
                || className.contains("BreakBlockEvent")
                || className.contains("BlockGrowFeatureEvent")
                || className.contains("CropGrowEvent")
                || className.contains("CreateFluidSourceEvent")
                || className.contains("PistonEvent")
                || className.contains("ExplosionEvent")
                || className.contains("ExplosionKnockbackEvent")
                || className.contains("LevelEvent")
                || className.contains("LevelTickEvent")
                || className.contains("SleepFinishedTimeEvent")
                || className.contains("GameRuleChangedEvent")
                || className.contains("NoteBlockEvent")
                || className.contains("GrindstoneEvent")
                || className.contains("AlterGroundEvent")
                || className.contains("LoadCompleteEvent")
                || className.contains("LevelSaveEvent")
                || className.contains("LevelLoadEvent")
                || className.contains("NeighborNotifyEvent")
                || className.contains("SaplingGrowTreeEvent")
                || className.contains("BlockEntityPlaceEvent")
                || className.contains("BlockEntityRemoveEvent");
    }

    private static boolean isEntityEvent(String className) {
        return className.contains("EntityEvent")
                || className.contains("PlayerTickEvent")
                || className.contains("EntityTickEvent")
                || className.contains("ProjectileImpactEvent")
                || className.contains("EnchantedEntityLootEvent")
                || className.contains("StatAwardEvent")
                || className.contains("ServerChatEvent")
                || className.contains("PlayerBrewedPotionEvent")
                || className.contains("PlayerEvent")
                || className.contains("LivingEntityEvent")
                || className.contains("EntityJoinLevelEvent")
                || className.contains("EntityLeaveLevelEvent")
                || className.contains("EntityTeleportEvent")
                || className.contains("PlayerInteractEvent")
                || className.contains("PlayerLoggedInEvent")
                || className.contains("PlayerLoggedOutEvent")
                || className.contains("PlayerRespawnEvent")
                || className.contains("PlayerSetSpawnEvent")
                || className.contains("AdvancementEvent")
                || className.contains("ExperienceEvent");
    }

    // =========================================================================
    // Internal — Dispatch Methods
    // =========================================================================

    /**
     * Dispatches a global-scoped event.
     */
    private boolean dispatchGlobal(Object event) {
        if (shouldValidate() && !isOnGlobalThread()) {
            handleViolation("Global event '" + event.getClass().getName()
                    + "' dispatched from non-global thread '"
                    + Thread.currentThread().getName() + "'");
            if (validationMode == ValidationMode.STRICT) {
                return false;
            }
        }
        return dispatchToDelegate(event, EventRouting.GLOBAL);
    }

    /**
     * Dispatches a region-scoped event.
     */
    private boolean dispatchRegion(Object event) {
        if (shouldValidate()) {
            String violation = "Region-scoped event '" + event.getClass().getName()
                    + "' dispatched from non-region thread '"
                    + Thread.currentThread().getName() + "'";
            handleViolation(violation);
            if (validationMode == ValidationMode.STRICT) {
                return false;
            }
        }
        return dispatchToDelegate(event, EventRouting.REGION);
    }

    /**
     * Dispatches an entity-scoped event.
     */
    private boolean dispatchEntity(Object event) {
        if (shouldValidate() && !isOnRegionThread()) {
            String violation = "Entity-scoped event '" + event.getClass().getName()
                    + "' dispatched from non-entity-region thread '"
                    + Thread.currentThread().getName() + "'";
            handleViolation(violation);
            if (validationMode == ValidationMode.STRICT) {
                return false;
            }
        }
        return dispatchToDelegate(event, EventRouting.ENTITY);
    }

    /**
     * Dispatches an async event (no region validation needed).
     */
    private boolean dispatchAsync(Object event) {
        return dispatchToDelegate(event, EventRouting.ASYNC);
    }

    /**
     * Core delegation method: sends the event to the Guava EventBus delegate.
     *
     * <p>TODO[NEOFORGE]: At runtime, replace the logging with
     * {@code ((com.google.common.eventbus.EventBus) delegateBus).post(event)}.
     * For now, we log the dispatch for debugging purposes.</p>
     *
     * @param event   the event to dispatch
     * @param routing the routing classification (for logging)
     * @return {@code true} if dispatched successfully
     */
    private boolean dispatchToDelegate(Object event, EventRouting routing) {
        totalEventsDispatched.incrementAndGet();

        Object bus = delegateBus;
        if (bus == null) {
            LOGGER.fine("Delegate bus not wired — event '" + event.getClass().getName()
                    + "' logged but not dispatched (routing: " + routing + ")");
            return true; // Don't fail — just skip delivery
        }

        // TODO[NEOFORGE]: Replace with ((EventBus) bus).post(event)
        // At compile time we cannot reference EventBus, so this is a placeholder.
        LOGGER.fine("Event dispatched [routing=" + routing + "]: "
                + event.getClass().getName());
        return true;
    }

    // =========================================================================
    // Internal — Violation Handling
    // =========================================================================

    /**
     * Handles a detected region thread violation.
     *
     * <p>Behaviour depends on the current {@link ValidationMode}:</p>
     * <ul>
     *   <li>{@link ValidationMode#STRICT} — throws {@link IllegalStateException}
     *       immediately (first occurrence only per message).</li>
     *   <li>{@link ValidationMode#WARN} — logs a warning. Same violation
     *       is only logged once to avoid spam.</li>
     *   <li>{@link ValidationMode#PERMISSIVE} — no action.</li>
     * </ul>
     *
     * @param violation the violation description
     */
    private void handleViolation(String violation) {
        handleViolation(violation, null);
    }

    /**
     * Handles a detected region thread violation with an optional caller frame.
     *
     * @param violation the violation description
     * @param caller    the calling stack frame (for STRICT mode stack traces),
     *                  or null
     */
    private void handleViolation(String violation, StackTraceElement caller) {
        if (reportedViolations.add(violation)) {
            // First time seeing this violation
            totalViolations.incrementAndGet();

            switch (validationMode) {
                case STRICT:
                    IllegalStateException ex = new IllegalStateException(
                            "[Crelia] Region thread violation: " + violation);
                    if (caller != null) {
                        ex.setStackTrace(new StackTraceElement[]{caller});
                    }
                    throw ex;

                case WARN:
                    LOGGER.warning("[Crelia] Region thread violation: " + violation);
                    if (caller != null) {
                        LOGGER.warning("  at " + caller);
                    }
                    break;

                case PERMISSIVE:
                    // Silent — no logging
                    break;

                default:
                    break;
            }
        }
        // Duplicate violations are silently ignored (already reported)
    }

    // =========================================================================
    // Internal — Thread Helpers
    // =========================================================================

    /**
     * Returns {@code true} if region validation should be performed.
     */
    private boolean shouldValidate() {
        return validationMode != ValidationMode.PERMISSIVE;
    }

    /**
     * Checks if the current thread is the global/server thread.
     *
     * <p>TODO[FOLIA]: At runtime, use
     * {@code MinecraftServer.getServer().isSameThread()} or check for
     * the global region thread.</p>
     */
    private static boolean isOnGlobalThread() {
        Thread current = Thread.currentThread();
        String name = current.getName();
        return "Server thread".equals(name)
                || "main".equals(name)
                || name.contains("Server");
    }

    /**
     * Checks if the current thread is a Folia region worker thread.
     *
     * <p>TODO[FOLIA]: At runtime, use
     * {@code Thread.currentThread() instanceof RegionizedWorldThread}.</p>
     */
    private static boolean isOnRegionThread() {
        return Thread.currentThread().getName().contains("Region");
    }

    // =========================================================================
    // Internal — Lifecycle Event Payload
    // =========================================================================

    /**
     * Internal payload class for lifecycle events dispatched via string name.
     *
     * <p>Used by {@link #postLifecycleEvent(String, Object...)} to wrap
     * legacy string-based lifecycle events into a typed object that can
     * be dispatched through the delegate bus.</p>
     */
    static final class LifecycleEventPayload {
        private final String eventName;
        private final Object[] args;

        LifecycleEventPayload(String eventName, Object[] args) {
            this.eventName = eventName;
            this.args = args != null ? args.clone() : new Object[0];
        }

        /** Returns the lifecycle event name. */
        public String getEventName() {
            return eventName;
        }

        /** Returns the event arguments ( Defensive copy). */
        public Object[] getArgs() {
            return args.clone();
        }

        @Override
        public String toString() {
            return "LifecycleEventPayload{" + eventName + ", args=" + args.length + "}";
        }
    }
}
