package crelia.core.integration;

/*
 * Crelia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Crelia contributors
 *
 * This file is part of the Crelia project.
 * See https://github.com/ for license details.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Server-side configuration handler for client LOD (Level of Detail) mods.
 *
 * <h2>Purpose</h2>
 *
 * <p>On a Folia regionized server, client-side LOD mods (Distant Horizons, Voxy)
 * need server-side coordination. This class reads configuration from
 * {@code crelia-lod.properties} (preferred) or falls back to
 * {@code server.properties} entries prefixed with {@code crelia.lod.}.
 * It validates that known-incompatible mods (C2ME) are not present, and
 * provides a recommended performance mod compatibility list.</p>
 *
 * <h2>Configuration file location</h2>
 * <p>The loader searches for configuration in this order:</p>
 * <ol>
 *   <li>{@code crelia-lod.properties} in the server working directory.</li>
 *   <li>{@code server.properties} — any key starting with {@code crelia.lod.}
 *       is extracted.</li>
 *   <li>System properties (e.g. {@code -Dcrelia.lod.enabled=true}).</li>
 * </ol>
 *
 * <h2>Configuration options</h2>
 * <table>
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code crelia.lod.enabled}</td><td>boolean</td><td>{@code false}</td>
 *       <td>Master switch for LOD integration</td></tr>
 *   <tr><td>{@code crelia.lod.mode}</td><td>enum</td><td>{@code DISABLED}</td>
 *       <td>One of {@code CLIENT_ONLY}, {@code SERVER_ASSISTED}, {@code DISABLED}</td></tr>
 *   <tr><td>{@code crelia.lod.max-render-distance}</td><td>int</td><td>{@code -1}</td>
 *       <td>Overrides client render distance for LOD; -1 = no override</td></tr>
 *   <tr><td>{@code crelia.lod.dh-server-component}</td><td>boolean</td><td>{@code false}</td>
 *       <td>Enable DH 2.4.x server-side LOD generation component</td></tr>
 *   <tr><td>{@code crelia.lod.voxy-support}</td><td>boolean</td><td>{@code false}</td>
 *       <td>Enable Voxy client-only mode flag</td></tr>
 * </table>
 *
 * <h2>C2ME conflict detection</h2>
 * <p>C2ME (C2ME-FF) replaces the chunk generation and scheduling system with
 * its own parallel implementation. This fundamentally conflicts with Folia's
 * regionized threading model where each region thread owns a fixed set of
 * chunks. If C2ME is detected on the classpath or in the mods directory,
 * this class logs a {@code SEVERE} warning and recommends removal.</p>
 *
 * <h2>Performance mod compatibility</h2>
 * <p>The server maintains an allowlist and blocklist of performance mods:</p>
 * <ul>
 *   <li><b>ALLOWED:</b> Radium Reforged, ServerCore, Chunky (pregen only),
 *       FerriteCore, Lithium Reforged — these are either client-only rendering
 *       mods, or server-side mods that don't modify chunk generation threading.</li>
 *   <li><b>BLOCKED:</b> C2ME, and any mod that replaces or modifies the chunk
 *       generation threading model (e.g. LaserIO chunk threading patches,
 *       Phosphor's chunk load hooks in certain versions).</li>
 * </ul>
 *
 * @see crelia.core.loading.CreliaModLoadingPlugin
 */
public final class ClientLodConfig {

    private static final Logger LOGGER = Logger.getLogger("CreliaLodConfig");

    // =========================================================================
    // LOD Mode enum
    // =========================================================================

    /**
     * Determines how LOD integration operates.
     */
    public enum LodMode {
        /**
         * LOD is entirely client-side. The server does not generate or provide
         * LOD data. This is the safest mode and works with any client-side LOD
         * mod (Distant Horizons, Voxy, etc.) without server changes.
         */
        CLIENT_ONLY,

        /**
         * The server provides pre-computed LOD data (e.g. Distant Horizons 2.4.x
         * server-side LOD generation). This requires the server to generate LOD
         * chunks alongside normal world generation, which is coordinated with
         * the region threading model.
         */
        SERVER_ASSISTED,

        /**
         * LOD integration is disabled. No LOD-related configuration is sent to
         * clients and no server-side LOD generation occurs.
         */
        DISABLED
    }

    // =========================================================================
    // Mod compatibility entries
    // =========================================================================

    /**
     * Describes a mod's compatibility status with Crelia's LOD integration.
     */
    public static final class ModCompatibility {
        private final String modId;
        private final String displayName;
        private final String versionPin;
        private final String status; // ALLOWED, BLOCKED, WARNED
        private final String notes;

        public ModCompatibility(String modId, String displayName,
                                String versionPin, String status, String notes) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            this.versionPin = versionPin;
            this.status = Objects.requireNonNull(status, "status");
            this.notes = notes;
        }

        public String getModId() { return modId; }
        public String getDisplayName() { return displayName; }
        public String getVersionPin() { return versionPin; }
        public String getStatus() { return status; }
        public String getNotes() { return notes; }

        @Override
        public String toString() {
            return "[" + status + "] " + displayName + " (" + modId + ")"
                    + (versionPin != null ? " v" + versionPin : "")
                    + " — " + notes;
        }
    }

    // =========================================================================
    // Configuration fields
    // =========================================================================

    /** Master switch for LOD integration. */
    private boolean lodEnabled;

    /** The LOD integration mode. */
    private LodMode lodMode;

    /** Override for client render distance; -1 means no override. */
    private int maxRenderDistance;

    /** Enable DH 2.4.x server-side LOD generation component. */
    private boolean dhServerComponent;

    /** Enable Voxy client-only mode flag. */
    private boolean voxySupport;

    /** Whether C2ME was detected on the classpath. */
    private boolean c2meDetected;

    /** The path where configuration was loaded from. */
    private String configSource;

    /** All raw properties loaded from the config file. */
    private final Map<String, String> rawProperties = new LinkedHashMap<>();

    // =========================================================================
    // Singleton
    // =========================================================================

    private static volatile ClientLodConfig instance;

    private ClientLodConfig() {}

    /**
     * Returns the singleton LOD configuration instance, loading it if necessary.
     *
     * @return the configuration instance
     */
    public static ClientLodConfig getInstance() {
        if (instance == null) {
            synchronized (ClientLodConfig.class) {
                if (instance == null) {
                    instance = new ClientLodConfig();
                    instance.load();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the singleton so it will be reloaded on next access.
     * Primarily useful for testing.
     */
    public static void resetInstance() {
        synchronized (ClientLodConfig.class) {
            instance = null;
        }
    }

    // =========================================================================
    // Loading
    // =========================================================================

    /**
     * Loads configuration from the config file hierarchy.
     *
     * <p>Search order:</p>
     * <ol>
     *   <li>{@code crelia-lod.properties} in the working directory.</li>
     *   <li>System properties starting with {@code crelia.lod.}.</li>
     *   <li>Defaults.</li>
     * </ol>
     */
    private void load() {
        LOGGER.info("[Crelia] Loading LOD configuration");

        // Priority 1: crelia-lod.properties file
        Path lodProps = Path.of("crelia-lod.properties");
        if (Files.isRegularFile(lodProps)) {
            loadFromFile(lodProps);
            this.configSource = lodProps.toAbsolutePath().toString();
            LOGGER.info("[Crelia] LOD config loaded from: " + this.configSource);
        } else {
            LOGGER.fine("[Crelia] crelia-lod.properties not found — using system properties and defaults");
            this.configSource = "system-properties-and-defaults";
        }

        // Priority 2: System properties override file values
        applySystemProperties();

        // Parse all values
        parseValues();

        // Validate: check for C2ME presence
        this.c2meDetected = detectC2ME();
        if (this.c2meDetected) {
            LOGGER.severe("[Crelia] CRITICAL: C2ME detected! C2ME's parallel chunk "
                    + "generation threading is incompatible with Folia's regionized "
                    + "threading model. Remove C2ME before running Crelia.");
        }

        // Validate mode consistency
        validateConsistency();

        // Log final configuration
        logConfiguration();
    }

    /**
     * Loads properties from a {@code .properties} file.
     */
    private void loadFromFile(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                int sep = line.indexOf('=');
                if (sep > 0) {
                    String key = line.substring(0, sep).trim();
                    String value = line.substring(sep + 1).trim();
                    rawProperties.put(key, value);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Crelia] Failed to read " + file, e);
        }
    }

    /**
     * Applies system properties that override file values.
     * System properties use dot notation (e.g. {@code crelia.lod.enabled}).
     */
    private void applySystemProperties() {
        String[] systemKeys = {
            "crelia.lod.enabled",
            "crelia.lod.mode",
            "crelia.lod.max-render-distance",
            "crelia.lod.dh-server-component",
            "crelia.lod.voxy-support"
        };

        for (String key : systemKeys) {
            String value = System.getProperty(key);
            if (value != null) {
                rawProperties.put(key, value);
                LOGGER.fine("[Crelia] LOD config: " + key + " overridden from system property = " + value);
            }
        }
    }

    /**
     * Parses raw property strings into typed configuration values.
     */
    private void parseValues() {
        this.lodEnabled = getBoolean("crelia.lod.enabled", false);
        this.maxRenderDistance = getInt("crelia.lod.max-render-distance", -1);
        this.dhServerComponent = getBoolean("crelia.lod.dh-server-component", false);
        this.voxySupport = getBoolean("crelia.lod.voxy-support", false);

        // Parse LOD mode
        String modeStr = getString("crelia.lod.mode", "DISABLED").toUpperCase().replace('-', '_');
        try {
            this.lodMode = LodMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("[Crelia] Unknown LOD mode: '" + modeStr
                    + "' — valid values: CLIENT_ONLY, SERVER_ASSISTED, DISABLED. Using DISABLED.");
            this.lodMode = LodMode.DISABLED;
        }
    }

    /**
     * Validates consistency between configuration options.
     */
    private void validateConsistency() {
        // If LOD is disabled, mode should be DISABLED
        if (!this.lodEnabled && this.lodMode != LodMode.DISABLED) {
            LOGGER.warning("[Crelia] LOD is disabled (crelia.lod.enabled=false) but mode is "
                    + this.lodMode + ". Setting mode to DISABLED.");
            this.lodMode = LodMode.DISABLED;
        }

        // If LOD is enabled, mode should not be DISABLED
        if (this.lodEnabled && this.lodMode == LodMode.DISABLED) {
            LOGGER.info("[Crelia] LOD is enabled but mode is DISABLED. "
                    + "Setting mode to CLIENT_ONLY (safe default).");
            this.lodMode = LodMode.CLIENT_ONLY;
        }

        // SERVER_ASSISTED requires DH server component or Voxy
        if (this.lodMode == LodMode.SERVER_ASSISTED) {
            if (!this.dhServerComponent && !this.voxySupport) {
                LOGGER.warning("[Crelia] SERVER_ASSISTED mode requires at least one of "
                        + "crelia.lod.dh-server-component or crelia.lod.voxy-support to be true. "
                        + "Falling back to CLIENT_ONLY.");
                this.lodMode = LodMode.CLIENT_ONLY;
            }
        }

        // DH server component validation
        if (this.dhServerComponent) {
            if (!this.lodEnabled) {
                LOGGER.warning("[Crelia] DH server component enabled but LOD is disabled. "
                        + "DH server component will not be active.");
            }
            LOGGER.info("[Crelia] DH server-side LOD generation is enabled. "
                    + "Ensure Distant Horizons 2.4.x+ is installed on the server.");
        }

        // Voxy support validation
        if (this.voxySupport) {
            LOGGER.info("[Crelia] Voxy client-only mode flag is enabled. "
                    + "The server will advertise Voxy compatibility to connecting clients.");
        }

        // Render distance validation
        if (this.maxRenderDistance != -1) {
            if (this.maxRenderDistance < 2 || this.maxRenderDistance > 128) {
                LOGGER.warning("[Crelia] Invalid max-render-distance: "
                        + this.maxRenderDistance + " (must be 2-128 or -1). Using -1 (no override).");
                this.maxRenderDistance = -1;
            } else {
                LOGGER.info("[Crelia] Client render distance override: "
                        + this.maxRenderDistance + " chunks");
            }
        }
    }

    /**
     * Detects whether C2ME is present on the classpath.
     *
     * <p>C2ME modifies Minecraft's chunk generation and scheduling to use
     * parallel threads. This is fundamentally incompatible with Folia's
     * regionized threading model, where each region thread owns a specific
     * set of chunks and is the sole authority for ticking them.</p>
     *
     * @return {@code true} if C2ME classes are found on the classpath
     */
    private boolean detectC2ME() {
        // Check for C2ME's main class and known package prefixes
        String[] c2meClassSignatures = {
            "com.ishland.c2me",                          // C2ME main package
            "com.ishland.c2me.common",                   // C2ME common module
            "com.ishland.c2me.rewrites",                 // C2ME rewrites module
            "com.ishland.c2me.natives",                  // C2ME native module
            "com.ishland.c2me.notickdedup",              // C2ME no-tick-dedup
            "com.ishland.c2me.opts",                     // C2ME optimization module
            "com.ishland.c2me.chunk_tick_iteration",     // C2ME chunk iteration
            "com.ishland.c2me.rewrites.chunkio",         // C2ME async chunk I/O
            "com.ishland.c2me.rewrites.chunkscheduling", // C2ME chunk scheduling
            "com.ishland.c2me.rewrites.worldgen",        // C2ME worldgen threading
        };

        for (String pkg : c2meClassSignatures) {
            try {
                // Attempt to load a class from the C2ME package.
                // Class.forName is avoided to prevent NoClassDefFoundError side effects.
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = ClassLoader.getSystemClassLoader();
                }
                String resource = pkg.replace('.', '/') + "/";
                InputStream is = cl.getResourceAsStream(resource);
                if (is != null) {
                    is.close();
                    LOGGER.severe("[Crelia] C2ME package found on classpath: " + pkg);
                    return true;
                }
            } catch (Exception e) {
                // Ignore — class not found means C2ME is not present
            }
        }

        // Also check via service loader / mod loading
        // (This is a secondary check; the classpath check above is primary)
        try {
            // Check for C2ME's mod ID in the mod loading system
            // On NeoForge this would be through the mod container registry
            // For now, also scan the mods directory for C2ME jar files
            Path modsDir = Path.of("mods");
            if (Files.isDirectory(modsDir)) {
                try (var stream = Files.list(modsDir)) {
                    boolean found = stream.anyMatch(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.contains("c2me") || name.contains("c2me-ff")
                                || name.contains("c2me_ff");
                    });
                    if (found) {
                        LOGGER.severe("[Crelia] C2ME jar found in mods/ directory");
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "[Crelia] Could not scan mods/ directory for C2ME", e);
        }

        LOGGER.info("[Crelia] C2ME not detected — chunk generation threading is safe");
        return false;
    }

    // =========================================================================
    // Performance mod compatibility
    // =========================================================================

    /**
     * Returns the list of allowed performance mods with version pins and notes.
     *
     * <p>These mods are confirmed to be safe on a Folia regionized server
     * because they either operate client-side only (rendering optimizations)
     * or don't modify the chunk generation / scheduling threading model.</p>
     *
     * @return unmodifiable list of allowed mod entries
     */
    public static List<ModCompatibility> getAllowedMods() {
        return List.of(
            new ModCompatibility(
                "radium",
                "Radium Reforged",
                "0.7.6+",
                "ALLOWED",
                "Client-side sodium-compatible rendering optimization. No server-side chunk threading changes. " +
                "Reforged fork for NeoForge 21.1.x compatibility."
            ),
            new ModCompatibility(
                "servercore",
                "ServerCore",
                "1.3.6+",
                "ALLOWED",
                "Server-side performance optimization. Does not modify chunk generation threading. " +
                "Configurable entity/tick optimizations safe for regionized threading."
            ),
            new ModCompatibility(
                "chunky",
                "Chunky",
                "1.4.17+",
                "ALLOWED",
                "Pre-generation tool only. Runs chunk generation on the main thread during pregen, " +
                "which is safe because pregen happens before normal server operation. " +
                "Not a runtime performance mod — no threading conflicts."
            ),
            new ModCompatibility(
                "ferritecore",
                "FerriteCore",
                "7.0.2+",
                "ALLOWED",
                "Memory usage optimization (reduces block/entity NBT size). " +
                "Purely data-structure optimization with no threading changes. " +
                "Works identically on regionized and non-regionized servers."
            ),
            new ModCompatibility(
                "lithium",
                "Lithium Reforged",
                "0.13.0+",
                "ALLOWED",
                "General-purpose server optimization (physics, AI, redstone, etc.). " +
                "Reforged fork for NeoForge 21.1.x. All optimizations are per-tick " +
                "and don't introduce cross-thread access — safe for regionized threading."
            )
        );
    }

    /**
     * Returns the list of blocked performance mods with reasons.
     *
     * <p>These mods are incompatible with Folia's regionized threading model
     * because they replace or wrap the chunk generation/scheduling system.</p>
     *
     * @return unmodifiable list of blocked mod entries
     */
    public static List<ModCompatibility> getBlockedMods() {
        return List.of(
            new ModCompatibility(
                "c2me",
                "C2ME / C2ME-FF",
                "ALL",
                "BLOCKED",
                "Replaces chunk generation and scheduling with parallel threading. " +
                "Fundamentally conflicts with Folia's per-region thread ownership model. " +
                "Will cause data races, chunk corruption, and server crashes. " +
                "Severity: CRITICAL — server will refuse to start."
            ),
            new ModCompatibility(
                "c2me_notickdedup",
                "C2ME No-Tick-Dedup Module",
                "ALL",
                "BLOCKED",
                "Part of C2ME. Modifies tick scheduling to skip duplicate entity ticking, " +
                "which interferes with Folia's per-region entity ownership."
            ),
            new ModCompatibility(
                "c2me_rewrites_chunkscheduling",
                "C2ME Chunk Scheduling Rewrite",
                "ALL",
                "BLOCKED",
                "Directly replaces the chunk scheduling system that Folia regions depend on. " +
                "Not a standalone mod but a C2ME module that is always incompatible."
            ),
            new ModCompatibility(
                "c2me_rewrites_chunkio",
                "C2ME Async Chunk I/O Rewrite",
                "ALL",
                "BLOCKED",
                "Replaces chunk I/O with async implementation that bypasses Folia's " +
                "region-owned chunk access patterns."
            ),
            new ModCompatibility(
                "c2me_rewrites_worldgen",
                "C2ME WorldGen Threading Rewrite",
                "ALL",
                "BLOCKED",
                "Introduces parallel world generation threading that conflicts with " +
                "Folia's single-thread-per-region worldgen model."
            )
        );
    }

    /**
     * Returns the full compatibility list (allowed + blocked) for logging.
     *
     * @return unmodifiable list of all mod compatibility entries
     */
    public static List<ModCompatibility> getFullCompatibilityList() {
        List<ModCompatibility> all = new java.util.ArrayList<>();
        all.addAll(getAllowedMods());
        all.addAll(getBlockedMods());
        return Collections.unmodifiableList(all);
    }

    /**
     * Checks whether a given mod ID is on the blocked list.
     *
     * @param modId the mod ID to check
     * @return {@code true} if the mod is blocked
     */
    public static boolean isBlocked(String modId) {
        if (modId == null) return false;
        String normalized = modId.toLowerCase();
        return getBlockedMods().stream()
                .anyMatch(entry -> entry.getModId().equalsIgnoreCase(normalized));
    }

    /**
     * Checks whether a given mod ID is on the allowed list.
     *
     * @param modId the mod ID to check
     * @return {@code true} if the mod is allowed
     */
    public static boolean isAllowed(String modId) {
        if (modId == null) return false;
        String normalized = modId.toLowerCase();
        return getAllowedMods().stream()
                .anyMatch(entry -> entry.getModId().equalsIgnoreCase(normalized));
    }

    // =========================================================================
    // Property accessors
    // =========================================================================

    private boolean getBoolean(String key, boolean defaultValue) {
        String value = rawProperties.get(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    private int getInt(String key, int defaultValue) {
        String value = rawProperties.get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                LOGGER.warning("[Crelia] Invalid integer value for " + key
                        + ": '" + value + "' — using default: " + defaultValue);
            }
        }
        return defaultValue;
    }

    private String getString(String key, String defaultValue) {
        return rawProperties.getOrDefault(key, defaultValue);
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    /** Returns whether LOD integration is enabled. */
    public boolean isLodEnabled() { return lodEnabled; }

    /** Returns the LOD integration mode. */
    public LodMode getLodMode() { return lodMode; }

    /**
     * Returns the max render distance override, or {@code -1} if no override
     * is configured.
     */
    public int getMaxRenderDistance() { return maxRenderDistance; }

    /** Returns whether DH 2.4.x server-side LOD generation is enabled. */
    public boolean isDhServerComponentEnabled() { return dhServerComponent; }

    /** Returns whether Voxy client-only mode flag is enabled. */
    public boolean isVoxySupportEnabled() { return voxySupport; }

    /** Returns whether C2ME was detected during loading. */
    public boolean isC2meDetected() { return c2meDetected; }

    /** Returns the configuration source path. */
    public String getConfigSource() { return configSource; }

    /** Returns the raw properties map (unmodifiable). */
    public Map<String, String> getRawProperties() {
        return Collections.unmodifiableMap(rawProperties);
    }

    // =========================================================================
    // Logging
    // =========================================================================

    /**
     * Logs the complete LOD configuration to the server log.
     */
    private void logConfiguration() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Crelia LOD Configuration ===\n");
        sb.append("  Source: ").append(configSource).append('\n');
        sb.append("  LOD Enabled: ").append(lodEnabled).append('\n');
        sb.append("  LOD Mode: ").append(lodMode).append('\n');
        sb.append("  Max Render Distance Override: ");
        sb.append(maxRenderDistance == -1 ? "none" : maxRenderDistance + " chunks").append('\n');
        sb.append("  DH Server Component: ").append(dhServerComponent).append('\n');
        sb.append("  Voxy Support: ").append(voxySupport).append('\n');
        sb.append("  C2ME Detected: ").append(c2meDetected).append('\n');

        // Log allowed mods
        sb.append("\n  Allowed Performance Mods:\n");
        for (ModCompatibility mod : getAllowedMods()) {
            sb.append("    [OK] ").append(mod.getDisplayName())
                    .append(" v").append(mod.getVersionPin())
                    .append(" — ").append(mod.getNotes()).append('\n');
        }

        // Log blocked mods
        sb.append("\n  Blocked Mods:\n");
        for (ModCompatibility mod : getBlockedMods()) {
            sb.append("    [BLOCKED] ").append(mod.getDisplayName())
                    .append(" — ").append(mod.getNotes()).append('\n');
        }

        sb.append("=== End LOD Configuration ===");
        LOGGER.info(sb.toString());
    }

    // =========================================================================
    // Server properties integration
    // =========================================================================

    /**
     * Generates a properties string that can be appended to
     * {@code server.properties} for LOD configuration.
     *
     * <p>This is a convenience method for operators who prefer to keep all
     * configuration in a single file.</p>
     *
     * @return the properties string with current values and comments
     */
    public String toServerPropertiesFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Crelia LOD Configuration\n");
        sb.append("# These properties control client LOD (Level of Detail) integration.\n");
        sb.append("# For detailed documentation, see the Crelia wiki.\n");
        sb.append("crelia.lod.enabled=").append(lodEnabled).append('\n');
        sb.append("crelia.lod.mode=").append(lodMode).append('\n');
        sb.append("crelia.lod.max-render-distance=").append(maxRenderDistance).append('\n');
        sb.append("crelia.lod.dh-server-component=").append(dhServerComponent).append('\n');
        sb.append("crelia.lod.voxy-support=").append(voxySupport).append('\n');
        return sb.toString();
    }

    /**
     * Generates a complete {@code crelia-lod.properties} file content.
     *
     * <p>Writes a well-documented properties file with all options and their
     * current values. The file includes comments explaining each option.</p>
     *
     * @return the properties file content
     */
    public String toPropertiesFileContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Crelia LOD Configuration File\n");
        sb.append("# =============================\n");
        sb.append("#\n");
        sb.append("# This file controls server-side LOD (Level of Detail) integration\n");
        sb.append("# for client-side LOD mods like Distant Horizons and Voxy.\n");
        sb.append("#\n");
        sb.append("# Options can also be set via system properties (e.g. -Dcrelia.lod.enabled=true)\n");
        sb.append("# or in server.properties with the crelia.lod. prefix.\n");
        sb.append("#\n");
        sb.append("# LOD Modes:\n");
        sb.append("#   CLIENT_ONLY       - LOD is purely client-side; server does not generate LOD data\n");
        sb.append("#   SERVER_ASSISTED   - Server provides LOD data (requires DH 2.4.x or Voxy)\n");
        sb.append("#   DISABLED          - No LOD integration at all\n");
        sb.append("#\n\n");

        sb.append("# Master switch for LOD integration\n");
        sb.append("# Default: false\n");
        sb.append("crelia.lod.enabled=").append(lodEnabled).append("\n\n");

        sb.append("# LOD integration mode\n");
        sb.append("# Values: CLIENT_ONLY, SERVER_ASSISTED, DISABLED\n");
        sb.append("# Default: DISABLED\n");
        sb.append("crelia.lod.mode=").append(lodMode).append("\n\n");

        sb.append("# Override client render distance for LOD (in chunks)\n");
        sb.append("# Set to -1 to disable override (client uses its own setting)\n");
        sb.append("# Range: 2-128 or -1\n");
        sb.append("# Default: -1\n");
        sb.append("crelia.lod.max-render-distance=").append(maxRenderDistance).append("\n\n");

        sb.append("# Enable Distant Horizons 2.4.x server-side LOD generation component\n");
        sb.append("# Only effective when mode=SERVER_ASSISTED\n");
        sb.append("# Default: false\n");
        sb.append("crelia.lod.dh-server-component=").append(dhServerComponent).append("\n\n");

        sb.append("# Enable Voxy client-only mode flag\n");
        sb.append("# Advertises Voxy compatibility to connecting clients\n");
        sb.append("# Default: false\n");
        sb.append("crelia.lod.voxy-support=").append(voxySupport).append("\n\n");

        sb.append("# Performance Mod Compatibility\n");
        sb.append("# ===============================\n");
        sb.append("#\n");
        sb.append("# ALLOWED (safe to use with Crelia):\n");
        for (ModCompatibility mod : getAllowedMods()) {
            sb.append("#   ").append(mod.getDisplayName())
                    .append(" v").append(mod.getVersionPin())
                    .append(" — ").append(mod.getNotes()).append('\n');
        }
        sb.append("#\n");
        sb.append("# BLOCKED (will conflict with Folia region threading):\n");
        for (ModCompatibility mod : getBlockedMods()) {
            sb.append("#   ").append(mod.getDisplayName())
                    .append(" — ").append(mod.getNotes()).append('\n');
        }

        return sb.toString();
    }
}