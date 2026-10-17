# Performance design and profiling

This page records why CWP schedules work as it does. Counts and cadences below describe the current code; they are not measured tick times or a throughput promise. Minecraft runs these callbacks in single player too, on the integrated server.

## Work cadence and ownership

| Work | Current behavior | Reason |
| --- | --- | --- |
| MobForge script execution | Server tick callback registered before portal and selection maintenance | A live encounter should make progress before reclaiming expired instances. Script-driven building changes and wipe recovery take priority over old-instance cleanup. |
| Portal contact | Client tests its rendered portal snapshot each client tick and sends a request on contact change | Avoid scanning every server player against every portal each server tick. The server still validates the claimed portal and player position. |
| Portal snapshots and expiry | Checked every 20 server ticks; snapshot is sent only when its hash changes | Reduce routine network traffic. Snapshot construction and hashing still scale with placed portals. |
| Building and portal editor previews | Checked every 40 server ticks for players in editor dimensions; unchanged payloads are not sent | Keep editor work away from normal players and avoid repeated transfers. |
| Random events | Scheduler checks every 20 server ticks | Second-level scheduling needs no per-tick scan. |
| MobForge AI targets | Registry work is staggered; several target checks run every 20 entity ticks with an entity-ID offset | Spread target searches across ticks instead of aligning all mobs on one tick. |

Sources: [initialization](../src/main/java/ekuzo/crazyworldprogression/CrazyWorldProgression.java), [portal maintenance](../src/main/java/ekuzo/crazyworldprogression/portals/PortalService.java), [portal networking](../src/main/java/ekuzo/crazyworldprogression/portals/PortalNetworking.java), [building preview](../src/main/java/ekuzo/crazyworldprogression/buildings/BuildingPreviewNetworking.java), [random events](../src/main/java/ekuzo/crazyworldprogression/events/RandomEventService.java), and [mob AI](../src/main/java/ekuzo/crazyworldprogression/mobforge/ai/MobAiRegistry.java).

## Block work time budget

All block work (instance building, wipe rebuilds, script building pastes and removals, instance deletion and area clearing) runs inside one adjusting time budget per server tick, implemented in [TickBudget](../src/main/java/ekuzo/crazyworldprogression/cleanup/TickBudget.java):

- **Budget = tick length (50 ms) − server load − buffer**, kept between a minimum and a maximum. The server load is the server's average tick time minus the time this budget itself handed out, so block work does not shrink its own budget. Example: 15 ms load and a 10 ms buffer leave 25 ms per tick.
- **Priorities:**
  1. **Scripts** (building `add`/`delete`, clearing at `encounter_start`/`encounter_end`) always come first and may use up to `script_max_ms` per tick, however busy the server is, so raid and dungeon mechanics never stall.
  2. **Instance creation** and wipe rebuilds (and the building clears they wait for) come next. They keep room for what scripts usually need later in the tick and always get at least `min_budget_ms`.
  3. **Instance deletion** and admin area clearing come last and only use what is left, plus a small `cleanup_floor_ms` so slots are freed eventually.
- Settings live in `config/crazy-world-progression-performance.properties` (created on first start): `tick_buffer_ms=10`, `min_budget_ms=2`, `max_budget_ms=30`, `script_max_ms=30`, `cleanup_floor_ms=1`. Restart the server after changing them.
- Loops check the clock every 128 blocks. Some costs appear after the tick (chunk saving, lighting, sending block changes), which is what the buffer and the maximum leave room for.

## Instance building

Pockets have no size limit. A new dungeon or public instance is placed over several ticks within instance creation's share of the budget; several builds take turns so they progress together. Instance builds, wipe rebuilds and instance deletion write straight into chunk sections like world generation ([FastBlockWriter](../src/main/java/ekuzo/crazyworldprogression/portals/FastBlockWriter.java)): no neighbour updates or per-block change packets, lighting is queued for the light thread only where light properties change, and heightmaps are rebuilt once per chunk. All of them work chunk by chunk: a build first groups the template's blocks by chunk (off the server thread) and completes each chunk before moving on, and deletion goes one 16-block section at a time, skipping sections that hold only air. Each chunk being written is held loaded by a short ticket and marked for saving as soon as it is touched, so no write is lost when a chunk unloads between ticks. Players already in a chunk (wipe rebuilds) receive it as soon as it is complete. Script building pastes still use the normal block placement because players are usually standing next to them. Rebuilding the pocket after a wipe uses the same queue. Players who enter meanwhile see "Your instance is being prepared… N%" on the action bar and are brought in when the build is finished. Pocket scripts start only after the build.

Capturing a pocket (saving it from the pocket editor) leaves air out, since instances are built into emptied slots, so a mostly empty pocket saves, loads and builds much faster. The capture itself still happens in one step, so saving a very large pocket can pause the server briefly.

## Portal contact and trust boundary

[Client contact detection](../src/client/java/ekuzo/crazyworldprogression/client/PortalClientRenderer.java) uses the client portal snapshot and shared [contact geometry](../src/main/java/ekuzo/crazyworldprogression/portals/PortalContactGeometry.java). A contact packet is only a request. [PortalService.requestPortalContact](../src/main/java/ekuzo/crazyworldprogression/portals/PortalService.java) rate limits requests, resolves the exact placed portal in the player's dimension, checks the player's real position inside its shape, and applies entry rules. Confirmation revalidates the pending entry before teleporting. Keep these server checks whenever client detection changes; client state is not authoritative.

This shifts the broad detection loop to each client, while server work occurs on contact rather than every player tick. Rendering and particles remain client-side. If players report missed contact, test fast movement and snapshot freshness before increasing server polling.

## Building preview cache

[BuildingPreviewNetworking](../src/main/java/ekuzo/crazyworldprogression/buildings/BuildingPreviewNetworking.java) caches serialized block occupancy per server and pocket editor. It reuses a snapshot while dimension, linked script IDs, script object identities, and building metadata match. It builds one snapshot per pocket per check, sends only when the player's last payload differs, and clears the cache on server stop. This avoids repeatedly saving structure NBT and rebuilding the occupied-block bitset for every editor viewer.

**Invalidation rule:** any new code path that mutates a building template or linked script must update the compared metadata/object or explicitly invalidate this cache. A raw on-disk structure edit that bypasses those paths is not detected merely because the file changed. Reload the relevant data after external edits and add a targeted invalidation hook if such edits become a supported live workflow. The cache holds serialized previews in memory, so profile its size if editors use many large buildings.

## Known costs to measure before 1.0

- [Structure capture and placement](../src/main/java/ekuzo/crazyworldprogression/portals/PortalTemplateService.java) and [building capture and placement](../src/main/java/ekuzo/crazyworldprogression/buildings/BuildingService.java) call `fillFromWorld` or `placeInWorld` synchronously. Volume limits prevent unlimited input, but a large valid template can still produce a long tick. The cleanup budget does not constrain placement.
- Encounter transitions and instance teardown have [world entity scans](../src/main/java/ekuzo/crazyworldprogression/mobforge/script/MobScriptExecutor.java), [raid entity scans](../src/main/java/ekuzo/crazyworldprogression/portals/RaidService.java), and [template entity scans](../src/main/java/ekuzo/crazyworldprogression/portals/PortalTemplateService.java). Measure them with high entity counts before adding indexes or chunk-scoped queries.
- Script conditions that count entities in a box and many simultaneous active scripts can add repeated per-tick queries. Measure actual script count and entity density before changing condition semantics.
- [PortalNetworking](../src/main/java/ekuzo/crazyworldprogression/portals/PortalNetworking.java) reconstructs and hashes the portal snapshot once per second even when unchanged. If portal counts grow substantially, measure this path and consider dirty-state invalidation.

These are inspection findings, not benchmark results. Optimize only after identifying a reproducible slow path.

## Profiling and release checks

Test both an integrated single-player server and a dedicated server. Record baseline and worst server tick time, client frame time, player/entity count, portal count, template volume, active script count, cleanup backlog, and whether chunks were already loaded. Compare: idle world; many players near portals; a large script building placement/removal; a raid wipe; simultaneous expired instances and area clears; several editor viewers; many mobs executing target logic. Run a Java profiler or Minecraft tick profiler over the spike, and compare stack samples rather than inferring cost from source alone.

When changing one of these paths, preserve server validation, cleanup cursor persistence/completion, cache invalidation, and the active-script priority. Verify the same scenario in single player because its integrated server runs the server-side callbacks. No live performance benchmark is recorded in this document yet.
