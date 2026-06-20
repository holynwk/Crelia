# Testpack — Fixed Test Modpack for CI

This directory contains a pinned, version-locked modpack used for automated integration
testing of the Crelia compatibility modules against real mod JARs in CI pipelines.

## Directory Structure

```
testpack/
├── mods/           # Mod JARs (pinned versions for CI stability)
│   └── README.md   # Mod version manifest
├── config/         # Server configuration files
│   └── .gitkeep    # Placeholder to preserve directory in git
└── README.md       # This file
```

## Purpose

The testpack ensures that:

1. **Crelia compat modules work with real mod APIs** — CI downloads pinned mod JARs
   (Create, Create Big Cannons, Sable, Create Aeronautics) and compiles the compat
   modules against them, catching API breakage early.

2. **Cross-region scenarios are testable** — The testpack includes a set of world
   templates and test configurations that exercise region boundary crossings
   (kinetic networks spanning regions, contraptions crossing boundaries, projectiles,
   vehicle flight across regions).

3. **Version stability** — All mod versions are pinned in `mods/README.md`. Upgrades
   are done intentionally via PR, not by auto-update, preventing CI breakage from
   upstream mod updates.

## CI Usage

In CI pipelines, the testpack is used as follows:

```bash
# Download pinned mod JARs based on mods/README.md manifest
./scripts/download-test-mods.sh testpack/mods/

# Compile compat modules against test mods
./gradlew :crelia-compat-create:compileJava --test-mods-dir testpack/mods/
./gradlew :crelia-compat-sable:compileJava --test-mods-dir testpack/mods/

# Run integration tests with the test server
./scripts/start-test-server.sh testpack/
./scripts/run-integration-tests.sh
```

## Updating Mod Versions

To update a pinned mod version:

1. Edit `mods/README.md` to change the version hash/reference
2. Run `./scripts/download-test-mods.sh testpack/mods/` to fetch the new JAR
3. Run the full CI suite to verify compatibility
4. Commit both the updated manifest and the new JAR
