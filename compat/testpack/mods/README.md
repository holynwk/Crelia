# Test Mod Manifest

This directory contains pinned mod JARs for CI testing. **Do not manually place mod JARs
here** — use the download script to ensure version consistency.

## Required Mods (Content / Compat)

| Mod | Version | File | Purpose |
|-----|---------|------|---------|
| NeoForge | 21.1.79 | (provided by Gradle) | Mod loading framework |
| Create | 0.5.1.f | `create-0.5.1.f.jar` | Kinetic machines, contraptions |
| Create Big Cannons (CBC) | 1.0.0 | `createbigcannons-1.0.0.jar` | Artillery projectiles, explosions |
| Sable | 0.1.0 | `sable-0.1.0.jar` | Rapier physics engine (JNI) |
| Create Aeronautics | 0.1.0 | `create_aeronautics-0.1.0.jar` | Aircraft assembly, flight |

## Recommended Performance Mods (Server-Side)

These mods are tested and confirmed safe on Crelia's Folia regionized threading model.
They either operate client-side only or do not modify chunk generation threading.

| Mod | Version Pin | Mod ID | Status | Notes |
|-----|-------------|--------|--------|-------|
| **Radium Reforged** | `0.7.6+` | `radium` | **ALLOWED** | Client-side sodium-compatible rendering optimization. No server-side chunk threading changes. Reforged fork for NeoForge 21.1.x. |
| **ServerCore** | `1.3.6+` | `servercore` | **ALLOWED** | Server-side performance optimization. Does not modify chunk generation threading. Configurable entity/tick optimizations safe for regionized threading. |
| **Chunky** | `1.4.17+` | `chunky` | **ALLOWED** | Pre-generation tool only. Runs chunk generation on the main thread during pregen, which is safe because pregen happens before normal server operation. Not a runtime performance mod. |
| **FerriteCore** | `7.0.2+` | `ferritecore` | **ALLOWED** | Memory usage optimization (reduces block/entity NBT size). Purely data-structure optimization with no threading changes. |
| **Lithium Reforged** | `0.13.0+` | `lithium` | **ALLOWED** | General-purpose server optimization (physics, AI, redstone). Reforged fork for NeoForge 21.1.x. All optimizations are per-tick, no cross-thread access. |

## Client-Side LOD Mods (Server Configuration Required)

These mods are client-side but require server-side configuration via `crelia-lod.properties`
or `server.properties` for optimal operation.

| Mod | Version Pin | Server Config | Notes |
|-----|-------------|---------------|-------|
| **Distant Horizons (DH)** | `2.4.x` | `crelia.lod.mode=SERVER_ASSISTED`, `crelia.lod.dh-server-component=true` | Client-side LOD rendering. Server-assisted mode requires DH 2.4.x+ server component. Client-only mode works without server config. |
| **Voxy** | Latest stable | `crelia.lod.mode=CLIENT_ONLY`, `crelia.lod.voxy-support=true` | Alternative client-side LOD. Voxy is purely client-side; the server flag just advertises compatibility to connecting clients. |

## Blocked Mods (Critical Incompatibility)

These mods **WILL** conflict with Folia's regionized threading model. The server will
refuse to start if any of these are detected.

| Mod | Version | Mod ID | Reason |
|-----|---------|--------|--------|
| **C2ME / C2ME-FF** | ALL | `c2me` | Replaces chunk generation and scheduling with parallel threading. Fundamentally conflicts with Folia's per-region thread ownership. Causes data races, chunk corruption, and server crashes. |
| **C2ME No-Tick-Dedup** | ALL | `c2me_notickdedup` | C2ME module. Modifies tick scheduling, interferes with Folia's per-region entity ownership. |
| **C2ME Chunk Scheduling Rewrite** | ALL | `c2me_rewrites_chunkscheduling` | C2ME module. Directly replaces the chunk scheduling system that Folia depends on. |
| **C2ME Async Chunk I/O** | ALL | `c2me_rewrites_chunkio` | C2ME module. Bypasses Folia's region-owned chunk access patterns. |
| **C2ME WorldGen Threading** | ALL | `c2me_rewrites_worldgen` | C2ME module. Parallel worldgen conflicts with Folia's single-thread-per-region model. |
| **Any mod modifying chunk generation threading** | N/A | N/A | Any mod that replaces or wraps `ChunkGenerator`, `ChunkMap`, or the chunk scheduling system. Evaluate on a case-by-case basis. |

## LOD Configuration Example

Create `crelia-lod.properties` in the server root (alongside `server.properties`):

```properties
# Enable LOD integration
crelia.lod.enabled=true

# CLIENT_ONLY = LOD runs purely client-side
# SERVER_ASSISTED = server provides LOD data (DH 2.4.x)
crelia.lod.mode=CLIENT_ONLY

# Override client render distance for LOD (-1 = no override)
crelia.lod.max-render-distance=-1

# Distant Horizons 2.4.x server component
crelia.lod.dh-server-component=false

# Voxy client-only mode
crelia.lod.voxy-support=true
```

## Compatibility Notes by Mod

### Create 0.5.1.f
- **Region threading impact:** HIGH — kinetic networks and contraptions span multiple regions
- **Compat module:** `crelia-compat-create` patches cross-region kinetic propagation, contraption fragmentation, and projectile handling
- **Known issues:** Large contraptions (trains, aerial) spanning 3+ regions may have slight position desync at region boundaries

### Create Big Cannons (CBC)
- **Region threading impact:** HIGH — cannon projectiles travel at extreme velocity crossing regions rapidly
- **Compat module:** Handled by `crelia-compat-create` via `RegionizedProjectileHandler`
- **Known issues:** Explosion damage must be fanned out to all affected region threads, introducing up to 1 tick of damage aggregation delay

### Sable
- **Region threading impact:** CRITICAL — JNI physics runs on a separate thread; must not access Minecraft state from physics thread
- **Compat module:** `crelia-compat-sable` bridges physics thread to region threads via async message queues
- **Known issues:** The JNI thread safety auditor adds ~2% overhead to physics calls; can be disabled in dev mode

### Create Aeronautics
- **Region threading impact:** HIGH — aircraft move fast enough to cross multiple regions per tick
- **Compat module:** Handled by `crelia-compat-sable` via `VehicleAssemblyRegionHandler`
- **Known issues:** Emergency block scatter on crash requires cross-region coordination; debris may appear to pop in on distant regions

### Distant Horizons (DH) 2.4.x
- **Region threading impact:** LOW in CLIENT_ONLY mode, MEDIUM in SERVER_ASSISTED mode
- **Server config:** Set `crelia.lod.mode=SERVER_ASSISTED` and `crelia.lod.dh-server-component=true`
- **Notes:** SERVER_ASSISTED mode generates LOD data on the server. Ensure adequate CPU headroom; LOD generation runs at lower priority than region ticking.

### Voxy
- **Region threading impact:** NONE — purely client-side
- **Server config:** Set `crelia.lod.mode=CLIENT_ONLY` and `crelia.lod.voxy-support=true`
- **Notes:** The server-side flag only sets a Voxy compatibility marker in the handshake. No server-side code changes.

### Radium Reforged
- **Region threading impact:** NONE — client-side rendering only
- **Notes:** Safe to use on any Crelia server. Does not require server-side installation for NeoForge 21.1.x (client-side mod).

### ServerCore
- **Region threading impact:** NONE — entity/tick optimizations are per-tick, no threading model changes
- **Notes:** Compatible. Some ServerCore features (entity activation range) work even better on Folia due to per-region tick isolation.

### Chunky
- **Region threading impact:** NONE during normal operation — pregen runs before region threads are active
- **Notes:** Use Chunky for world pre-generation before opening to players. Pregen on a Folia server uses the global region thread.

### FerriteCore
- **Region threading impact:** NONE — memory optimization only
- **Notes:** Reduces memory footprint of block entity and entity NBT. Works identically on regionized servers.

### Lithium Reforged
- **Region threading impact:** NONE — all optimizations are per-tick and single-threaded within a region
- **Notes:** Best results when combined with FerriteCore. The physics, AI, and redstone optimizations are particularly effective on Crelia.

## Download

```bash
./scripts/download-test-mods.sh testpack/mods/
```

## Notes

- JAR files in this directory are **not** committed to version control (they are
  downloaded by CI).
- The versions listed above are tested and known to work with the current compat
  module API surface.
- Before upgrading any mod version, verify API compatibility by running the full
  CI suite.
- If you add a new performance mod, verify it does not modify chunk generation
  threading by checking against the Crelia mod compatibility list in
  `crelia-supported.json`.