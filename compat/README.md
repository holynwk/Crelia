# Crelia Compat — Compatibility Modules

This directory contains the compatibility layer modules for the Crelia (Folia-based)
NeoForge server project. Each module patches a specific mod to operate correctly on
Folia's regionized threading model.

## Architecture Overview

```
                    ┌─────────────────────────────────────┐
                    │        Crelia (Folia-based)         │
                    │      Regionized World Server        │
                    │                                     │
                    │  Region Thread A  Region Thread B  │
                    │  ┌───────────┐  ┌───────────────┐  │
                    │  │ Region A  │  │   Region B    │  │
                    │  │ Chunks,   │←→│  Chunks,      │  │
                    │  │ Entities  │  │  Entities     │  │
                    │  └─────┬─────┘  └──────┬────────┘  │
                    │        │               │           │
                    └────────┼───────────────┼───────────┘
                             │               │
                    ┌────────┼───────────────┼───────────┐
                    │  Compat Layer          │           │
                    │  (This directory)     │           │
                    │                        │           │
                    │  ┌────────────────────┘           │
                    │  │                                │
                    │  ▼                                ▼
                    │  crelia-compat-create    crelia-compat-sable
                    │  ├─ KineticNetwork       ├─ SubLevelManager
                    │  ├─ ContraptionHandler   ├─ PhysicsBridge
                    │  ├─ ProjectileHandler    ├─ JNI Auditor
                    │  └─ ExplosionHandler     └─ VehicleAssembly
                    └───────────────────────────────────┘
```

## Modules

### crelia-compat-create

**Target:** Create + Create Big Cannons
**Package:** `com.crelia.compat.create`
**Mod ID:** `crelia_compat_create`

Patches Create's mechanical systems for Folia region threading:

| Component | Description |
|-----------|-------------|
| `RegionAwareKineticNetwork` | Partitions kinetic networks into per-region segments, propagating boundary values via `RegionizedTaskQueue` |
| `ContraptionRegionHandler` | Fragments contraption updates across region boundaries, handles trains spanning 3+ regions |
| `RegionizedProjectileHandler` | Tracks CBC projectile ownership across regions via `EntityScheduler`, handles high-speed region crossings |
| `CrossRegionExplosionHandler` | Fans out explosion damage to affected region threads, collects partial results |

### crelia-compat-sable

**Target:** Sable + Create Aeronautics
**Package:** `com.crelia.compat.sable`
**Mod ID:** `crelia_compat_sable`

Bridges Sable's Rapier physics engine (JNI) to Folia's region threads:

| Component | Description |
|-----------|-------------|
| `CrossRegionSubLevelManager` | Manages two-phase handoff protocol for physics sub-levels crossing region boundaries |
| `SablePhysicsRegionBridge` | Async message queues from physics thread to region threads with back-pressure handling |
| `JNIThreadSafetyAuditor` | Bytecode hooks at JNI entry points, detects unsafe cross-thread Minecraft access |
| `VehicleAssemblyRegionHandler` | Region-safe aircraft assembly/disassembly, emergency block scatter across regions |

## Folia Region Threading Model

On a Folia server, the world is divided into `RegionizedWorldSection`s (typically 3×3 chunk
groups). Each section is ticked by a dedicated region thread. The key rules are:

1. **No cross-region access** — A region thread must not directly access block entities,
   entities, or chunk data owned by another region.
2. **Use RegionizedTaskQueue** — Cross-region operations must be scheduled via
   `RegionizedTaskQueue`, which submits work to the target region's thread.
3. **Use EntityScheduler** — Entity operations (tick, damage, remove) must happen on the
   entity's owning region thread via `EntityScheduler`.
4. **No synchronous blocking** — Region threads must not block waiting for other regions,
   as this can deadlock the server.

## Building

```bash
# Build all compat modules
./gradlew build

# Build a specific module
./gradlew :crelia-compat-create:build
./gradlew :crelia-compat-sable:build

# Run tests
./gradlew test
```

## Adding a New Compat Module

1. Create a new directory: `crelia-compat-<name>/`
2. Follow the structure of existing modules:
   ```
   crelia-compat-<name>/
   ├── build.gradle.kts
   └── src/main/
       ├── java/com/crelia/compat/<name>/
       │   ├── <MainClass>.java
       │   └── ... (feature classes)
       └── resources/
           ├── META-INF/neoforge.mods.toml
           └── crelia-compat-<name>.mixins.json
   ```
3. Add `include(":crelia-compat-<name>")` to `settings.gradle.kts`
4. Update this README with the module details

## Test Pack

The `testpack/` directory contains a pinned modpack for CI testing. See
[testpack/README.md](testpack/README.md) for details.
