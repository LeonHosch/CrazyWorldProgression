# Crazy World Progression features — AI context

Snapshot of the checked-in implementation on 2026-10-16. CWP (`crazy-world-progression`, version `1.0.0`) is a reusable Minecraft 26.2/Fabric/Java 25 framework. It does not bundle Echelon's gameplay content. Read [AI_STRUCTURE.md](AI_STRUCTURE.md) for code ownership and [../README.md](../README.md) for schemas and commands. Source code is authoritative when summaries differ.

## Framework capabilities

| Feature | Current behavior | Main source |
| --- | --- | --- |
| Currencies | Content mods register namespaced player or global currencies. CWP provides non-negative wallets, persistence, generated balance/mutation commands, and UI snapshots. | `currency/`; `command/CurrencyCommands.java`, `BalanceCommands.java` |
| Claims | Per-player unique claim ledger with duplicate and lifetime-limit checks, suitable for one-time rewards. | `currency/ClaimLedgerService.java` |
| Skill trees | Loads registered `.yml`/`.yaml` sources at server startup/reload, validates nodes and prerequisites, supports global/personal unlocks, multi-currency costs, branch limits, purchase policies, and stat expressions. Server validates purchases and sends snapshots to the client tree screen. | `progression/skilltrees/`; `progression/ProgressionService.java`; client `SkillTreeScreen.java` |
| Baseline and stats | Registered baseline YAML sources and skill stat functions feed player attributes, global totals, and the stat sheet. | `progression/skilltrees/BaselineStatRegistry.java`, `SkillStatRegistry.java`, `SkillStatSheetService.java` |
| Random events | Loads registered event YAML, rolls in-game-phase or real-time triggers, applies time availability/blackout rules, persists active instances and roll cursors, and invokes content-mod lifecycle handlers. Concrete effects are supplied by the content mod. | `events/` |
| Portals and pockets | Creates a pocket before editing, assigns each pocket a persistent private build dimension, tracks edited chunks, and automatically saves all built blocks near an editable preferred spawn after a pause, periodically while building, on leaving, and on shutdown. Empty generated chunks are excluded. Each pocket stores multiple MobForge script attachments; they start in newly created runtime instances with coordinates translated from the editor dimension and mob spawns grounded. The saved pocket list has a separate Active instances page. Places visible portals with optional calendar and periodic time settings, entry policy, building permission, public shared or private group instances, re-entry into an existing lease, empty-private-instance cleanup, and confirmation UI. Public instances ignore groups, stay active while empty, and may have zero lifetime for no automatic expiry. Portals authored inside pockets project into every running copy; Links to Exit returns to the main world, while a pocket link opens another instance. Eight bundled runtime void dimensions are leased, reconstructed, evacuated, cleared, and reused. | `portals/`; `mixin/PocketBuildTrackingMixin.java`; `admin/AdminScriptEditor.java`; `command/PortalCommands.java`; client portal classes |
| Area selection | Server-authoritative cuboid selection with optional anchor and owner ID, client visualization, and extension listener. | `selection/` |
| Admin dashboard | `/cwp admin` or Ctrl+Shift+A shows CWP and content-mod modules. Reopening in a server session returns to the last module/page; client scroll position is restored on reopening. Server provides pages/actions; UI has standard, preview-card, and compact editor layouts, autocomplete, feedback, and server permission checks. | `admin/`; client `AdminPanelScreen.java`, `AdminPanelClient.java` |
| MobForge | Built into the CWP JAR. YAML mob templates and scripts under `config/mobforge/`, separate admin-panel mob and script lists, integrated per-mob and per-script editors, spawning, AI modes, script execution, per-player pocket-instance currency and item rewards, and a Java API for registered mobs, abilities, behaviors, and events. | `mobforge/`, `admin/AdminMobEditor.java`, `admin/AdminScriptEditor.java`, `api/mobforge/`; [mobforge.md](mobforge.md), [mobforge-api.md](mobforge-api.md) |

## Integration contract

- Content mods register definitions/sources during their common initializer. CWP loads authoritative YAML when the server starts; remote clients receive snapshots and do not need the content mod's local YAML to parse a tree.
- The server stores wallets, unlocks, claims, events, portal state, and instance leases in world data. Client packets request actions; services validate them on the server.
- Keep CWP independent of content-mod classes. Put Echelon-specific currencies, Veil logic, rewards, event effects, and policies in the sibling EchelonCore mod.
- Raid scripts have run-scoped variables, watched event conditions, event boxes, tagged-mob kill counts, redstone sensors, portal-use counts, bounded If/Else and Loop control flow, and steps to add, remove, or clear named locations of a portal preset in one active instance. A portal can disable its initial placement; one preset ID can render and trigger at several script-created locations at once. See [mobforge.md](mobforge.md).
- For MobForge, YAML templates/scripts and Java-registered mob definitions are distinct sources. Template-defined mobs use YAML as their source of truth. See the dedicated MobForge docs for exact schema and AI behavior.

## Limits and current scope

- Currency earning and event visual/gameplay effects require content-mod logic.
- The bundled portal dimension pool permits eight concurrent leases unless another mod registers more predeclared empty slots.
- CWP's own repository has no Echelon currency or tree content. The sibling `../EchelonCore` checkout supplies that integration.
