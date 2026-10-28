# Persistence ownership and migration

This inventory describes the current CWP and EchelonCore composite checkout. `SavedData` below means Minecraft's overworld data storage unless a row explicitly names another owner. Each `SavedDataType` has its own codec and is obtained with `computeIfAbsent`; loading is lazy at the first service access and Minecraft saves dirty instances with the world. Persistent Fabric attachments use the entity or block entity's normal save lifecycle. No gameplay data needs a new `world/cwp` folder or database.

## Inventory

| Owner and lifecycle | Persistent structures | Access and write path |
| --- | --- | --- |
| CWP player entity | `Skills.XP`; `Accessories.STORAGE`; `AmmoPouch.STORAGE` and `SELECTED`; `BuffFood.STORAGE`; `Collectibles.UNLOCKED` and `SELECTED`; `FishingJournal.CAUGHT` and `LOOTED`; `CrossbreedingService.DISCOVERED`; `Quicksort.SETTINGS`; `TitleData.EQUIPPED`; `ResearchData.STORAGE` | Persistent attachments. Services read via `getAttachedOrCreate` and replace values with `setAttached`; `copyOnDeath` is configured. Player entity serialization loads/saves them with the player. |
| CWP other entities and block entities | `AnimalProduce.READY_AT`; `ChestQuality.QUALITY`; `ChestLocks.LOCK`; `ChestFilters.FILTER` | Persistent attachments on the relevant chicken/cow or chest. They follow entity or chunk serialization, not player files. |
| CWP item stacks | `ItemLocks` and `ProduceQuality` item components, plus normal item components carried by equipment, ammo, food and stored inventories | Minecraft's persistent item component codecs. The containing inventory, mailbox, chest or entity owns the save lifecycle; these are not extra world stores. |
| CWP player records requiring UUID access, including offline players, or system delivery | `PlayerProgressionData` (currency balances, skill unlocks, claim ledgers); `PostmasterData` (mailbox inventories) | Overworld `SavedData`. Currency commands support offline profiles; postmaster contents form a system-owned delivery queue. The postmaster menu writes changes back into its store. UUID keys alone are not a reason to move these records. |
| CWP shared social and party state | `SocialData` (clans, membership, relationships and missions); `LockRegistry` (shared lock registry); `Parties.Data` (party membership and invitations) | Overworld `SavedData`, updated by the respective services. Membership is shared state and must remain one authoritative record. `ChestLocks.LOCK` is separately attached to each chest. |
| CWP global gameplay state | `GlobalProgressionData` (global currency and unlocks); `WorldLeaderData`; `RandomEventData`; `AdminCategories.Data`; `BoundaryRadiusData`; `RestrictionData`; `BonusAreaData` | Overworld `SavedData`. The boundary, restrictions, bonus areas and leader use CWP IDs. Global progression uses `global_progression`. |
| CWP portal and pocket systems | `PortalData` (definitions, locations, player entry history and instance leases); `PortalTemplateData`; `PocketEditorData`; `RaidData` (attempts and encounter state); `RaidCheckpoints` (UUID/portal checkpoints); `RaidSnapshots` (inventory orbs and logout restoration) | Separate overworld `SavedData` stores, accessed through portal, pocket and raid services. UUID-keyed checkpoints remain portal-owned because their validity depends on a raid lease and portal ID. `PocketEditorService` also writes structure templates on edits, exit and shutdown. |
| CWP world configuration and production | `BuildingData`; `CookingData`; `FletchingData`; `CrossbreedingData`; `FishingLootData`; `LootPoolData`; `MobLoot.Data`; `ReforgeData`; `SmithingData` | Separate overworld `SavedData` stores for definitions, recipes, pools or shared system state. Their services own mutation and dirty marking. |
| CWP dimension-qualified world data | `PlacedBlocks` | One overworld `SavedData` map keyed by dimension and block position. It records blocks players placed so they cannot gain repeated XP. It can grow and deserves retention monitoring. |
| CWP pocket weather | Vanilla `WeatherData.TYPE` selected by `PocketWeatherMixin` | Native per-level data storage for a pocket dimension. The weather service changes that dimension's weather state without copying overworld weather. |
| Echelon game content | `FalsekinHaunts` (target UUID, power, decoys and protection time) | Echelon-owned overworld `SavedData`. The haunt survives target disconnects, so it remains a game-specific world system rather than a CWP attachment. Echelon no longer owns `KingData`; the generic leader is in CWP. |
| Content and authoring files | MobForge templates and scripts under config, skill trees and other packaged/data-driven definitions, pocket templates/exports | Explicit file-backed configuration or templates, distinct from live player/world state. Template and script editors validate a temporary file before replacing their destination. They should not be routed through player attachments. |

## Read, write and lifecycle rules

1. Every module owns its codec, `SavedDataType`, service and stable ID. Public APIs expose operations, not mutable maps or raw data storage. The game mod owns game-specific state and may call public CWP services; CWP never imports Echelon classes.
2. Persistent player attachments remain primary for state that is only needed with a loaded player. Mutations must replace their attachment value or otherwise notify Fabric; never rely on an in-place edit of an attached collection being detected. Keep the existing `copyOnDeath` semantics.
3. Keep offline wallet and claim access in `PlayerProgressionData` until there is a demonstrated need and a migration that preserves offline updates. At 30–50 concurrent players, one dirty world record is simpler than loading arbitrary offline player files or maintaining duplicate copies. Reconsider partitioning only if profiling shows serialization cost or world-file size is material.
4. Shared clan, party, portal lease and raid state has one server-thread authority. A player's view is a projection, not a second persistent copy. Mutate on the server thread and mark only the owner dirty; avoid filesystem I/O in tick, menu and reward callbacks.
5. A disabled module must skip its event, command and service registration while leaving its saved data untouched. Persistent attachment types must still be registered before player data is decoded, even when gameplay hooks are disabled. Re-enabling reads the same codec and ID. Dependency ordering must be explicit when module switches are introduced.
6. Minecraft/Fabric own normal saves on disconnect and world shutdown. `PocketEditorService`'s explicit template writes are a separate, already-existing authoring path and need their own shutdown flush. Do not add background save threads.

## Compatibility choice

There is no production CWP server whose progress must be migrated. Equipped titles and enchantment research move from SavedData to player attachments; their old values reset. The generic UUID-backed progression store now uses `player_progression`, and global progression uses `global_progression`. Both old store IDs and their fixed Echelon fields are discarded. This removes game-specific names from framework storage; offline wallet commands still work through the new UUID-backed store.

The generic world leader, boundary, restrictions and bonus areas no longer import old Echelon or Divine save records. Their codecs and IDs use CWP terminology. The old `addDivine...` skill expressions remain as content aliases; those are configuration expressions rather than persistence format.

Future releases with production worlds should use an explicit, idempotent reader for any old format. That requirement is not driving this initial cleanup.

## Risks and validation

| Risk | Affected owners | Required check |
| --- | --- | --- |
| Large UUID maps serialize together when dirty | `PlayerProgressionData`, `PostmasterData`, `ResearchData`, `SocialData` | Profile save size and frequency before splitting. No-op wallet, claim, unlock and title writes now avoid dirtying their stores. |
| Cross-store operations cannot be atomic | currency purchases, raid lease/checkpoints/inventory restoration | Use idempotent operations and test restart at each boundary. A build or codec test cannot prove crash consistency. |
| Codec failure on a corrupt `.dat` file can prevent loading | Every `SavedData` store | Back up worlds before a release migration, preserve originals, and test intentionally corrupt or partially written files on a disposable world. Do not auto-delete them. |
| Module disabling can leave referenced state unavailable | Portals, parties, raids, scripts and skill trees | Test each dependency combination after module switches exist. Saved data must remain untouched while disabled. |
| Explicit template writes differ from native save scheduling | Pocket editor, exports, MobForge authoring | Test occupied editor, disconnect, shutdown and restart with real server files. |

Codec round-trip tests cover multiple player records, offline UUID access and enchantment research attachment data. Existing raid checkpoint and snapshot tests cover further codec paths. A composite build checks both mods compile. Real disconnect/restart, module-switch and corrupt-file scenarios require an integration server fixture and are not established by unit tests alone.

## Incremental implementation boundary

The first code increment moves online-only title and research choices into player attachments. It renames the generic player and global progression stores and removes old Echelon-specific fields. It also avoids dirtying progression stores on repeated identical writes. Tests exercise the relevant codecs. Public service APIs and gameplay decisions remain stable; old progress resets as authorized.

The next increment needs a disposable dedicated-server fixture with real world files. It should exercise a player save and logout, offline wallet mutation, shutdown/restart, a deliberately corrupt file, and portal/raid state spanning a restart. Module-disable tests must wait for actual module switches; a synthetic flag in a unit test would not validate startup behavior. This fixture should run against the Minecraft and Fabric versions declared by these repositories. Do not migrate more UUID records to player attachments until those tests establish offline and shared-state equivalence.
