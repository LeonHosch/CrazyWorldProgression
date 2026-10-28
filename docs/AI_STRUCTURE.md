# Crazy World Progression structure — AI context

Snapshot of the checked-in source on 2026-10-16. CWP is the reusable framework; `../EchelonCore` is a dependent content mod. See [AI_FEATURES.md](AI_FEATURES.md) for behavior, [architecture.md](architecture.md) for dependency rules, and the MobForge and portal docs for detailed APIs.

## Entry points and lifecycle

| Path | Responsibility |
| --- | --- |
| `src/main/resources/fabric.mod.json` | Mod ID, common/client entry points, mixin configs, dependencies. |
| `src/main/java/ekuzo/crazyworldprogression/CrazyWorldProgression.java` | Initializes server services, packets, commands, admin module, and MobForge. On `SERVER_STARTING`, reloads baseline stats, skill trees, and random events. |
| `src/client/java/ekuzo/crazyworldprogression/client/CrazyWorldProgressionClient.java` | Initializes admin, selection, tree, portal, group, prompt, and MobForge client systems. |
| `src/main/java/ekuzo/crazyworldprogression/mobforge/MobForge.java` | Initializes MobForge networking, commands, AI, hooks, registries, and script tick; reloads templates/scripts when server starts. |
| `src/main/java/ekuzo/crazyworldprogression/admin/AdminScriptEditor.java` | Server-backed MobForge script timeline editor rendered directly by the generic admin panel for the MobForge tab and individual pockets. |

## Package map

Paths below are under `src/main/java/ekuzo/crazyworldprogression/` unless noted.

| Package | Key classes and ownership |
| --- | --- |
| `currency/` | `CurrencyRegistry`, `CurrencyService`, `ClaimLedgerService`; definitions and world-backed wallets. |
| `progression/` | `ProgressionService` purchase transaction and global/player persistent data. |
| `progression/skilltrees/` | `SkillTreeRegistry` source/policy API, `SkillTreeManager` YAML loader, `SkillTreeService`, `SkillStatRegistry`, `BaselineStatRegistry`, networking and stat sheet. |
| `events/` | `RandomEventRegistry` source/handler API, `RandomEventManager` YAML definitions, `RandomEventService` scheduling/lifecycle, persistent data. |
| `portals/` | `PortalRegistry` slots/listeners, `PortalTemplateService` capture/reconstruction, `PortalService` runtime/re-entry/projected exits, `PortalBuildProtection` player edit gate, `PortalGroups`, definitions, data, networking. |
| `selection/` | Generic area selector service, registry, packets, and exact-coordinate API for other integrations. Pocket capture no longer uses selection. |
| `admin/` | Generic `AdminPanelRegistry`, CWP's module, and validated networking. |
| `command/` | Balance, currency, tree, admin, and portal command registration. |
| `api/mobforge/` | Public registration, spawn, ability, behavior, runtime-handle, and event interfaces. |
| `mobforge/template/`, `mobforge/script/` | YAML codecs, validation, reload/save, and script execution. |
| `mobforge/ai/`, `mobforge/entity/`, `mobforge/networking/`, `mobforge/command/` | AI controllers, entity state/spawn utilities, editor packets, commands. |

Client UI/rendering lives in `src/client/java/ekuzo/crazyworldprogression/client/` and `src/client/java/ekuzo/crazyworldprogression/mobforge/client/`. Server services own validation and persistence; the client should display snapshots and submit requests.

## Resources and mutable data

- `config/GeneratedWorlds` writes the pocket editor (workshop) dimensions, the reusable portal instance dimensions and one inaccessible legacy workshop dimension for old saves into a data pack at startup, as many as `config/crazy-world-progression.yml` asks for (templates in `src/main/resources/cwp-worlds/`). `config/CwpModule` and `config/CwpConfig` switch the framework's parts on and off. `PocketEditorData` persistently binds pocket IDs to editor dimensions; retired editor slots are not reassigned.
- `src/main/resources/assets/crazy-world-progression/` contains language strings, icon, and GUI textures.
- `src/main/resources/crazy-world-progression.mixins.json` and `mobforge.mixins.json` configure common mixins.
- `config/mobforge/templates/` and `config/mobforge/scripts/` are runtime YAML directories in a Minecraft installation, not bundled source definitions.
- Wallets, unlocks, claim ledgers, events, portal definitions/templates, and leases use server/world persistence. Avoid treating client state as authoritative.

## Where to make a change

- Reusable tree schema or purchase rule: `progression/skilltrees/` and `ProgressionService`; update tree networking/UI if the contract changes.
- Reusable currency or claim behavior: `currency/`; content-specific ways to earn currency belong in a dependent mod.
- Event trigger/schedule/handler contract: `events/`; event effects belong in the content mod.
- Portal template, access, or lifecycle behavior: `portals/`; update client prompt/group/rendering when packet or presentation contracts change.
- Admin page rendering or automatic field submission: `admin/` and client `AdminPanelScreen`; content module pages stay in their owning mod.
- MobForge template/script schema: keep model, YAML codec, validator, legacy network editor, and integrated admin script editor in sync. Pocket script IDs, capture origins, changed chunks, preferred spawns, and editor dimensions live in `PocketEditorData`; `PocketBuildTrackingMixin` indexes edited chunks, whole-build capture reads edited chunks and the previous template footprint, and `PocketEditorService` debounces automatic captures. Runtime instance creation starts attached scripts through `PocketEditorService`. Portal links use a reserved Exit template ID; the old destination field is read only for save migration. Admission fields persist access and building permission; instance leases persist admitted rosters and visitors for re-entry. For Java integrations, use `api/mobforge/` and [mobforge-api.md](mobforge-api.md).

Build with `.\gradlew.bat build` from this repository, or from `../EchelonCore` to build both through the composite project. A package build alone does not verify Minecraft runtime interactions.
