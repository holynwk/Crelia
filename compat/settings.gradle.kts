/*
 * Crelia Compat — Multi-module Gradle Settings
 *
 * This settings file defines the subprojects for the Crelia compatibility module suite.
 * Each subproject is a standalone NeoForge mod that provides region-threading compatibility
 * for a specific target mod on Folia-based (Crelia) servers.
 *
 * To add a new compat module:
 *   1. Create a new directory alongside the existing modules
 *   2. Add a `build.gradle.kts` following the existing pattern
 *   3. Include it below via `include(":crelia-compat-<name>")`
 */

rootProject.name = "crelia-compat"

include(
    ":crelia-compat-create",
    ":crelia-compat-sable"
)
