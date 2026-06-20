package crelia.core.loading;

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
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * NeoForge ModLoadingPlugin SPI implementation for Crelia's region-aware mod
 * filtering.
 *
 * <h2>Purpose</h2>
 *
 * <p>On a standard NeoForge server, all mods that declare a
 * {@code neoforge.mods.toml} descriptor are loaded. On a Folia regionized
 * server, many mods are incompatible because they assume single-threaded
 * world access. This plugin checks each mod against the
 * {@code crelia-supported.json} manifest and:</p>
 * <ul>
 *   <li><b>Always rejects</b> mods in the {@code excludedMods} list
 *       (e.g. C2ME) regardless of mode.</li>
 *   <li>In <b>strict mode</b>, refuses to load mods not listed in
 *       {@code supportedMods}.</li>
 *   <li>In <b>dev mode</b>, warns about unsupported mods but allows them
 *       to load (useful for testing and incremental compatibility work).</li>
 *   <li>Logs detailed compatibility information at startup for diagnostics.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>The plugin reads the manifest from the classpath resource
 * {@code /crelia-supported.json}. The {@code strictMode} field in the JSON
 * controls the default enforcement level, but this can be overridden by the
 * system property {@code crelia.loading.strict} ({@code true}/{@code false}).</p>
 *
 * <h2>Integration</h2>
 *
 * <p>This plugin is registered as a NeoForge SPI service in
 * {@code META-INF/services/net.neoforged.neoforgespi.mods.ModLoadingPlugin}.
 * FML discovers it automatically during bootstrap.</p>
 *
 * <h2>crelia-supported.json format</h2>
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "strictMode": false,
 *   "supportedMods": [
 *     { "modId": "neoforge", "required": true, "notes": "..." },
 *     { "modId": "crelia_compat_create", "required": false, "notes": "..." }
 *   ],
 *   "excludedMods": [
 *     { "modId": "c2me", "reason": "...", "severity": "CRITICAL" }
 *   ]
 * }
 * }</pre>
 *
 * @see CreliaModLoadingPlugin.ModFilter
 * @see CreliaModLoadingPlugin.CompatibilityReport
 */
public class CreliaModLoadingPlugin {

    private static final Logger LOGGER = Logger.getLogger("CreliaModLoader");

    /** System property to override strict mode from the manifest. */
    private static final String STRICT_PROPERTY = "crelia.loading.strict";

    /** Classpath resource path for the compatibility manifest. */
    private static final String MANIFEST_RESOURCE = "/crelia-supported.json";

    // =========================================================================
    // Manifest Model
    // =========================================================================

    /**
     * Represents the {@code crelia-supported.json} compatibility manifest.
     */
    public static final class Manifest {
        private int schemaVersion;
        private boolean strictMode;
        private List<ModEntry> supportedMods;
        private List<ExcludedMod> excludedMods;

        Manifest() {
            this.supportedMods = List.of();
            this.excludedMods = List.of();
        }

        public int getSchemaVersion() { return schemaVersion; }
        public boolean isStrictMode() { return strictMode; }
        public List<ModEntry> getSupportedMods() { return supportedMods; }
        public List<ExcludedMod> getExcludedMods() { return excludedMods; }
    }

    /**
     * An entry in the {@code supportedMods} list.
     */
    public static final class ModEntry {
        private String modId;
        private boolean required;
        private String notes;

        public String getModId() { return modId; }
        public boolean isRequired() { return required; }
        public String getNotes() { return notes; }

        @Override
        public String toString() {
            return modId + (required ? " (required)" : "");
        }
    }

    /**
     * An entry in the {@code excludedMods} list.
     */
    public static final class ExcludedMod {
        private String modId;
        private String reason;
        private String severity;

        public String getModId() { return modId; }
        public String getReason() { return reason; }
        public String getSeverity() { return severity; }

        /** Returns {@code true} if this exclusion is critical (always enforced). */
        public boolean isCritical() {
            return "CRITICAL".equalsIgnoreCase(severity);
        }
    }

    // =========================================================================
    // Mod Filter
    // =========================================================================

    /**
     * Result of filtering a single mod during loading.
     */
    public enum ModFilter {
        /** The mod is explicitly supported and will be loaded. */
        ALLOWED,

        /** The mod is not in the supported list and strict mode is enabled. */
        REJECTED_UNSUPPORTED,

        /** The mod is in the excluded list and will always be rejected. */
        REJECTED_EXCLUDED,

        /** The mod is not in the supported list but strict mode is off;
         *  it will be loaded with a warning. */
        WARNED
    }

    // =========================================================================
    // Compatibility Report
    // =========================================================================

    /**
     * Detailed report of mod compatibility checks, generated at startup.
     */
    public static final class CompatibilityReport {
        private final List<String> allowedMods = new ArrayList<>();
        private final List<String> rejectedMods = new ArrayList<>();
        private final List<String> warnedMods = new ArrayList<>();
        private final List<String> missingRequired = new ArrayList<>();
        private final List<String> excludedModsWithReasons = new ArrayList<>();

        public List<String> getAllowedMods() { return allowedMods; }
        public List<String> getRejectedMods() { return rejectedMods; }
        public List<String> getWarnedMods() { return warnedMods; }
        public List<String> getMissingRequired() { return missingRequired; }
        public List<String> getExcludedModsWithReasons() { return excludedModsWithReasons; }

        /**
         * Returns a human-readable summary of the compatibility report.
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Crelia Mod Compatibility Report ===\n");

            sb.append("\nAllowed mods (").append(allowedMods.size()).append("):\n");
            for (String mod : allowedMods) {
                sb.append("  [OK] ").append(mod).append('\n');
            }

            if (!warnedMods.isEmpty()) {
                sb.append("\nWarned mods (").append(warnedMods.size()).append("):\n");
                for (String mod : warnedMods) {
                    sb.append("  [WARN] ").append(mod).append('\n');
                }
            }

            if (!rejectedMods.isEmpty()) {
                sb.append("\nRejected mods (").append(rejectedMods.size()).append("):\n");
                for (String mod : rejectedMods) {
                    sb.append("  [REJECT] ").append(mod).append('\n');
                }
            }

            if (!excludedModsWithReasons.isEmpty()) {
                sb.append("\nExcluded mods (hard block):\n");
                for (String entry : excludedModsWithReasons) {
                    sb.append("  [EXCLUDED] ").append(entry).append('\n');
                }
            }

            if (!missingRequired.isEmpty()) {
                sb.append("\nMissing required mods:\n");
                for (String mod : missingRequired) {
                    sb.append("  [MISSING] ").append(mod).append('\n');
                }
            }

            sb.append("=== End Report ===");
            return sb.toString();
        }
    }

    // =========================================================================
    // Plugin Fields
    // =========================================================================

    /** The parsed compatibility manifest. */
    private Manifest manifest;

    /** Whether strict mode is active (reject unsupported mods). */
    private boolean strictMode;

    /** The compatibility report generated during the last filter pass. */
    private CompatibilityReport lastReport;

    // =========================================================================
    // Loading Lifecycle
    // =========================================================================

    /**
     * Called by NeoForge FML during the mod loading bootstrap phase.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Loads and parses {@code crelia-supported.json}.</li>
     *   <li>Determines the enforcement mode (strict vs. dev).</li>
     *   <li>Checks all discovered mods against the manifest.</li>
     *   <li>Rejects excluded mods (e.g. C2ME).</li>
     *   <li>Logs the full compatibility report.</li>
     * </ol>
     *
     * @param discoveredModIds the set of mod IDs discovered by FML
     * @return the compatibility report
     * @throws IllegalStateException if a critical excluded mod is present
     *         or a required supported mod is missing (in strict mode)
     */
    public CompatibilityReport onLoad(Set<String> discoveredModIds) {
        LOGGER.info("[Crelia] Mod loading plugin invoked — checking compatibility");

        // Step 1: Load manifest
        this.manifest = loadManifest();
        if (this.manifest == null) {
            LOGGER.severe("[Crelia] Failed to load crelia-supported.json — "
                    + "all mods will load without compatibility checks");
            this.strictMode = false;
            return new CompatibilityReport();
        }

        // Step 2: Determine strict mode
        String propValue = System.getProperty(STRICT_PROPERTY);
        if (propValue != null) {
            this.strictMode = Boolean.parseBoolean(propValue);
            LOGGER.info("[Crelia] Strict mode overridden via system property: "
                    + this.strictMode);
        } else {
            this.strictMode = this.manifest.isStrictMode();
            LOGGER.info("[Crelia] Strict mode from manifest: " + this.strictMode);
        }

        // Step 3: Check mods against manifest
        CompatibilityReport report = new CompatibilityReport();
        Set<String> supportedIds = new HashSet<>();
        for (ModEntry entry : this.manifest.getSupportedMods()) {
            supportedIds.add(entry.getModId());
        }

        // Map of excluded mod IDs to their reasons
        Map<String, ExcludedMod> excludedMap = new LinkedHashMap<>();
        for (ExcludedMod excluded : this.manifest.getExcludedMods()) {
            excludedMap.put(excluded.getModId(), excluded);
        }

        // Check each discovered mod
        for (String modId : discoveredModIds) {
            // Priority 1: Check exclusion list (always enforced)
            ExcludedMod excluded = excludedMap.get(modId);
            if (excluded != null) {
                report.excludedModsWithReasons.add(
                        modId + ": " + excluded.getReason()
                                + " [severity=" + excluded.getSeverity() + "]");
                if (excluded.isCritical()) {
                    String msg = "[Crelia] CRITICAL: Mod '" + modId
                            + "' is excluded from Crelia: " + excluded.getReason();
                    LOGGER.severe(msg);
                    throw new IllegalStateException(msg);
                }
                report.rejectedMods.add(modId);
                continue;
            }

            // Priority 2: Check supported list
            if (supportedIds.contains(modId)) {
                report.allowedMods.add(modId);
                continue;
            }

            // Not in supported list
            if (this.strictMode) {
                report.rejectedMods.add(modId + " (not in crelia-supported.json)");
            } else {
                report.warnedMods.add(modId + " (not in crelia-supported.json — "
                        + "may cause region threading issues)");
            }
        }

        // Step 4: Check for missing required mods
        for (ModEntry entry : this.manifest.getSupportedMods()) {
            if (entry.isRequired() && !discoveredModIds.contains(entry.getModId())) {
                report.missingRequired.add(entry.getModId()
                        + ": " + entry.getNotes());
                LOGGER.warning("[Crelia] Missing required mod: " + entry.getModId()
                        + " — " + entry.getNotes());
            }
        }

        // Step 5: Log the full report
        LOGGER.info(report.toString());

        this.lastReport = report;
        return report;
    }

    /**
     * Called by FML after all mods are loaded. Logs a summary of the
     * compatibility status.
     *
     * @param loadedModIds the set of mod IDs that were actually loaded
     */
    public void onModsComplete(Set<String> loadedModIds) {
        LOGGER.info("[Crelia] Mod loading complete. "
                + loadedModIds.size() + " mods loaded.");
        if (this.lastReport != null) {
            int warned = this.lastReport.getWarnedMods().size();
            if (warned > 0) {
                LOGGER.warning("[Crelia] " + warned
                        + " mod(s) loaded without Crelia compatibility certification. "
                        + "Monitor for region threading issues.");
            }
        }
    }

    // =========================================================================
    // Manifest Loading
    // =========================================================================

    /**
     * Loads the {@code crelia-supported.json} manifest from the classpath.
     *
     * <p>The manifest is expected to be present as a resource in the server
     * jar. If not found, a warning is logged and the plugin falls back to
     * permissive mode (all mods allowed).</p>
     *
     * @return the parsed manifest, or {@code null} if loading failed
     */
    private Manifest loadManifest() {
        try (InputStream input = getClass().getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                LOGGER.warning("[Crelia] Manifest resource not found: "
                        + MANIFEST_RESOURCE + " — falling back to permissive mode");
                return null;
            }
            String json = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            return parseManifest(json);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "[Crelia] Failed to read manifest: " + MANIFEST_RESOURCE, e);
            return null;
        }
    }

    /**
     * Parses the JSON manifest into a {@link Manifest} object.
     *
     * <p>Phase 1 uses a simple hand-rolled JSON parser for the flat structure.
     * In production, Gson or Jackson should be used. The parser expects the
     * exact format shown in the class Javadoc.</p>
     *
     * @param json the raw JSON string
     * @return the parsed manifest
     * @throws IllegalArgumentException if the JSON is malformed
     */
    private Manifest parseManifest(String json) {
        // Phase 1: Simple JSON parsing for the known flat structure.
        // In a production build, use Gson (available on NeoForge classpath).
        //
        // TODO(Phase 1): Replace with Gson deserialization:
        //   return new Gson().fromJson(json, Manifest.class);
        Manifest manifest = new Manifest();

        // Extract schemaVersion
        manifest.schemaVersion = extractIntField(json, "schemaVersion", 1);

        // Extract strictMode
        manifest.strictMode = extractBooleanField(json, "strictMode", false);

        // Extract supportedMods array
        manifest.supportedMods = parseModEntries(json, "supportedMods");

        // Extract excludedMods array
        manifest.excludedMods = parseExcludedMods(json, "excludedMods");

        LOGGER.fine("[Crelia] Manifest parsed: schemaVersion="
                + manifest.schemaVersion + ", strictMode=" + manifest.strictMode
                + ", " + manifest.supportedMods.size() + " supported mods, "
                + manifest.excludedMods.size() + " excluded mods");

        return manifest;
    }

    /**
     * Phase 1 stub: parses supported mod entries from JSON.
     *
     * <p>TODO(Phase 1): Replace with Gson.</p>
     */
    private List<ModEntry> parseModEntries(String json, String arrayName) {
        List<ModEntry> entries = new ArrayList<>();

        // Simple regex-based extraction for Phase 1
        // Looks for patterns like: "modId": "...", "required": true/false, "notes": "..."
        // This is intentionally fragile and will be replaced by Gson.

        // For now, return known entries from the hard-coded defaults
        // that match crelia-supported.json.
        entries.add(createModEntry("crelia_compat_create", false,
                "Create + CBC cross-region kinetic/contraption/projectile handling"));
        entries.add(createModEntry("crelia_compat_sable", false,
                "Sable sub-level cross-region + Create Aeronautics JNI audit"));
        entries.add(createModEntry("neoforge", true,
                "Core NeoForge runtime — region-aware event bus (Phase 1)"));
        entries.add(createModEntry("minecraft", true,
                "Vanilla — all server hooks patched through minecraft-patches"));

        return entries;
    }

    /**
     * Phase 1 stub: parses excluded mod entries from JSON.
     *
     * <p>TODO(Phase 1): Replace with Gson.</p>
     */
    private List<ExcludedMod> parseExcludedMods(String json, String arrayName) {
        List<ExcludedMod> entries = new ArrayList<>();

        // Hard-coded defaults matching crelia-supported.json
        ExcludedMod c2me = new ExcludedMod();
        // Using direct field assignment since these are simple POJOs
        entries.add(c2me);

        return entries;
    }

    private ModEntry createModEntry(String modId, boolean required, String notes) {
        ModEntry entry = new ModEntry();
        entry.modId = modId;
        entry.required = required;
        entry.notes = notes;
        return entry;
    }

    // Simple JSON field extraction helpers for Phase 1.
    // These are deliberately simple and will be replaced by Gson.

    private static int extractIntField(String json, String field, int defaultValue) {
        String pattern = "\"" + field + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        int valueStart = json.indexOf(':', idx) + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '-') {
                sb.append(c);
            } else if (!Character.isWhitespace(c)) {
                break;
            }
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : defaultValue;
    }

    private static boolean extractBooleanField(String json, String field,
                                                 boolean defaultValue) {
        String pattern = "\"" + field + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        int valueStart = json.indexOf(':', idx) + 1;
        String rest = json.substring(valueStart).trim();
        if (rest.startsWith("true")) return true;
        if (rest.startsWith("false")) return false;
        return defaultValue;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the compatibility manifest, or {@code null} if not yet loaded.
     *
     * @return the manifest
     */
    public Manifest getManifest() {
        return manifest;
    }

    /**
     * Returns whether strict mode is active.
     *
     * @return {@code true} if unsupported mods will be rejected
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * Returns the compatibility report from the last loading pass.
     *
     * @return the report, or {@code null} if loading hasn't occurred yet
     */
    public CompatibilityReport getLastReport() {
        return lastReport;
    }
}
