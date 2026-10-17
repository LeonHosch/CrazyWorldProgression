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

## Shared block cleanup budget

[BlockCleanupBudget](../src/main/java/ekuzo/crazyworldprogression/cleanup/BlockCleanupBudget.java) grants at most **8,192 block positions inspected per server tick**, shared by [building removal](../src/main/java/ekuzo/crazyworldprogression/buildings/BuildingService.java), [portal template removal](../src/main/java/ekuzo/crazyworldprogression/portals/PortalTemplateService.java), and [area clearing](../src/main/java/ekuzo/crazyworldprogression/selection/AreaSelectionService.java). Its counter resets when the server tick number changes and is cleared when the server stops. A caller reserves a slice before walking blocks, so the cap applies across those callers, not separately to each queue.

Building removal is resumable: script `remove`, removal before another placement, scheduled instance clears, and expired-instance cleanup carry a cursor into the next tick. Portal cleanup removes its template and remaining buildings before releasing the instance slot. The [script executor](../src/main/java/ekuzo/crazyworldprogression/mobforge/script/MobScriptExecutor.java) also caps the number of script operations it advances in one run per tick. Registration order gives the script executor first access to cleanup capacity; portal maintenance handles scheduled building clears before expired-instance cleanup; area clearing follows. This is intentional: active scripts and wipe recovery should not wait behind unused old instances. An old instance can consequently take longer to disappear during heavy script activity.

The **8,192 figure counts positions, not milliseconds**. Actual tick cost varies with loaded chunks, block updates, block entities, and entity work. The cap does not cover template capture or placement, network serialization, or every other action in these callbacks. Do not raise it solely to make cleanup finish faster; profile the worst tick first.

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
