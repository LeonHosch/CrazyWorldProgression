# Crazy World Progression

Crazy World Progression is a Fabric mod for server-wide and per-player progression. It provides four currencies, YAML-defined skill trees, global kingdom upgrades controlled by an elected king, personal skill progression, persistent server-side state, and a vanilla-inspired skill-tree screen.

The project is currently under active development. See [Current limitations](#current-limitations) before using it on a production server.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Java | 25 or newer |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | 0.158.0+26.2 |

SnakeYAML 2.6 is included inside the built mod JAR. Install Crazy World Progression and Fabric API on both the server and every connecting client; the server owns progression state and validation, while the client supplies the skill-tree screen and assets.

## Installation

1. Install Fabric Loader for Minecraft 26.2.
2. Download Fabric API for the same Minecraft version.
3. Copy Fabric API and the Crazy World Progression mod JAR into the instance's `mods/` directory.
4. For multiplayer, repeat the previous step for the dedicated server and every client.
5. Start the game or server. Back up an existing world before first testing a development build.

## Features

- A shared global progression balance and three per-player currencies.
- Global skill trees whose upgrades affect every player and can only be unlocked by the elected king.
- Personal skill trees with independent unlocks and effects for every player.
- Any number of YAML files, each displayed as a separate global or personal tab.
- Multiple prerequisites, automatically arranged nodes, and orthogonal connecting paths.
- Permanent branch choices configured with `following`.
- English and German tree text and UI translations.
- Server-authoritative purchases, balances, prerequisites, king checks, and excluded branches.
- Persistent balances, unlocks, king state, election candidate, and Powerful Soul claim history.
- Administrative commands for inspecting and debugging every currency.

## Currencies

| Currency | Short name | Scope | Intended acquisition | Used by |
| --- | --- | --- | --- | --- |
| Kingdom Points | KP | One balance shared by the server | Awarded manually | Global trees |
| Echelon Points | EP | Per player | Automatic and manual rewards | Personal trees |
| Fakhrul Currency | FC | Per player | Automatic rewards; the name is still temporary | Global and personal trees |
| Powerful Souls | PS | Per player | A limited number of unique automatic claims | Advanced personal nodes |

For a global purchase, KP comes from the shared kingdom balance and FC comes from the elected king. The resulting skill and its benefits are global. Personal purchases deduct the executing player's EP, FC, and PS and only unlock the skill for that player.

Powerful Soul limits are tracked separately from the spendable balance. Each automatic award should call `PlayerProgressionService.claimPowerfulSoul(...)` with a unique claim ID and a lifetime limit. Spending a soul does not erase its claim, so it cannot be earned repeatedly. The administrative `/ps give`, `/ps take`, and `/ps set` commands intentionally change only the debugging balance and do not change claim history.

The skill-tree screen shows KP and the king's FC on global tabs. Personal tabs show the viewing player's EP, FC, and PS. Currency assets live in `src/main/resources/assets/crazy-world-progression/currencies/`.

## Using the skill tree

Open the screen with `/skill` or the experience-bottle button beside the recipe-book button in the player inventory.

The UI behaves similarly to the vanilla advancements screen:

- Select global tabs on the left and personal tabs on the right.
- Drag the tree canvas to inspect larger trees.
- Hover a node to see its localized name, status, description, and cost.
- Hold a normal available node for 1.5 uninterrupted seconds to unlock it.
- Hold a choice node directly following a restricted split for 3 uninterrupted seconds.
- Moving away or releasing the mouse before the timer completes cancels the attempt.
- Unaffordable cost entries are greyed out individually.

The client animation is only feedback. The server validates the request again before deducting currency and saving an unlock.

## Commands

Commands marked **Admin** require Minecraft's gamemaster permission level.

### Balances

| Command | Permission | Result |
| --- | --- | --- |
| `/balance` | Player | Shows KP and all of the executing player's currencies |
| `/balance <target>` | Admin | Shows KP and all currencies for a player or selector |
| `/kp` or `/kingdompoints` | Player | Shows the shared KP balance |
| `/ep` or `/echelonpoints` | Player | Shows the executing player's EP |
| `/fc` or `/fakhrul` | Player | Shows the executing player's FC |
| `/ps` or `/powerfulsouls` | Player | Shows the executing player's PS |
| `/ep <target>` and equivalent FC/PS commands | Admin | Shows one personal currency for a player or selector |

`/balance ep` and `/ep balance` are intentionally not supported. Use `/ep` for a single currency and `/balance` for all currencies.

### Currency administration

KP is global and therefore has no target argument:

```text
/kp give <amount>
/kp take <amount>
/kp set <amount>
```

Personal currency mutations are amount-first and accept from one to 32 explicit names or selectors. Duplicate profiles are changed only once.

```text
/ep give <amount> <target1> [target2] ...
/ep take <amount> <target1> [target2] ...
/ep set  <amount> <target1> [target2] ...
```

The same syntax works with `/fc`, `/ps`, and their long aliases. For example:

```text
/ep give 100 Name1 Name2 Name3
/fc take 5 Name1 Name2
/ps set 2 @a
```

`take` stops at zero rather than producing a negative balance. `set` accepts zero; `give` and `take` require at least one.

### King and skill-tree commands

| Command | Permission | Result |
| --- | --- | --- |
| `/skill` | Player | Opens the skill-tree screen |
| `/king` | Player | Shows the elected king and active vote candidate |
| `/king vote start <candidate>` | Admin | Starts an election for exactly one candidate |
| `/king clear` | Admin | Clears both the elected king and any active election |

Starting a vote records and announces the candidate; it does not immediately appoint that player.

## Skill-tree YAML

Every `.yml` or `.yaml` file in `src/main/resources/default-skilltrees/` becomes one tab. Files are loaded in filename order. The filename without its extension becomes the stable tree ID.

The included files are:

- `kingdom-progression.yml` for the global kingdom tree.
- `player-progression.yml` for personal progression.

### Example

```yaml
# One file creates one tab.
skilltree: personal
name: Explorer Progression
gname: Entdeckerfortschritt
icon: minecraft:compass

skills:
  - id: prepared_explorer
    name: Prepared Explorer
    gname: Vorbereiteter Entdecker
    description: Gain half a heart.
    beschreibung: Erhalte ein halbes Herz.
    icon: minecraft:apple
    previous: none
    following: 1
    costs:
      echelonPoints: 10
      fakhrulCurrency: 0
      powerfulSouls: 0
    stats:
      - addHealth(1)

  - id: miner_path
    name: Miner Path
    gname: Bergbaupfad
    description: Gain 2% mining speed.
    beschreibung: Erhalte 2 % mehr Abbaugeschwindigkeit.
    icon: minecraft:iron_pickaxe
    previous:
      - Prepared Explorer
    following: 0
    costs:
      echelonPoints: 25
    stats:
      - addMiningSpeed(2)

  - id: runner_path
    name: Runner Path
    gname: Läuferpfad
    description: Gain 2% movement speed.
    beschreibung: Erhalte 2 % mehr Bewegungsgeschwindigkeit.
    icon: minecraft:leather_boots
    previous: Prepared Explorer
    following: 0
    costs:
      echelonPoints: 25
    stats:
      - addMovementSpeed(2)
```

In this example, `Prepared Explorer` has two direct successors and `following: 1`. Unlocking either `Miner Path` or `Runner Path` permanently excludes the other path and its descendants.

### Root fields

| Field | Required | Meaning |
| --- | --- | --- |
| `skilltree` | Yes | `global` or `personal` |
| `name` | No | English tab title; defaults to a readable form of the filename |
| `gname` | No | German tab title; falls back to `name` |
| `icon` | No | Namespaced vanilla item ID used by the tab |
| `skills` | Yes | YAML list containing the nodes |

The default tab icon is `minecraft:golden_helmet` for global trees and `minecraft:player_head` for personal trees.

### Node fields

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | Recommended | Stable save-data ID; otherwise generated from `name` |
| `name` | Yes | English display name and prerequisite reference name |
| `gname` | No | German name; falls back to `name` |
| `description` | No | English hover description |
| `beschreibung` | No | German description; falls back to `description` |
| `icon` | No | Namespaced item ID; defaults to `minecraft:book` |
| `previous` | No | `none`, one English node name, or a YAML list of names |
| `following` | No | Maximum number of direct successor branches that may be chosen; `0` means unlimited |
| `costs` | No | Non-negative whole-number currency costs; omitted values are zero |
| `stats` | No | One expression or a YAML list of internal stat expressions |

`previous` always references the English `name`, not `id` or `gname`. Every listed prerequisite must be unlocked before the node is available. Multiple prerequisites are supported.

Keep both filenames and explicit node IDs stable after publishing a tree. Unlocks are persisted as `<tree-id>/<node-id>`, so changing either ID makes existing save data refer to the old definition. Display names and descriptions may be changed safely when an explicit `id` is present, but matching `previous` references must also be updated after renaming an English node.

### Allowed costs

Global nodes may use only:

```yaml
costs:
  kingdomPoints: 20
  fakhrulCurrency: 3
```

Personal nodes may use only:

```yaml
costs:
  echelonPoints: 50
  fakhrulCurrency: 5
  powerfulSouls: 1
```

A global node containing EP or PS, or a personal node containing KP, is rejected when the server loads the trees.

### Restricted branches

`following` belongs to the parent node and limits how many of its direct successors can be entered. For example, a parent with three direct children and `following: 2` initially displays `2/3` over the fork. After two different branches have been unlocked, the remaining unchosen branch and its descendants become permanently excluded.

- Open restricted branches and their ratio are red.
- A selected branch becomes green.
- Excluded branches and nodes become dark grey.
- The ratio changes to the excluded-path color when no choices remain.
- Direct choice nodes require a three-second hold; other available nodes require 1.5 seconds.

Restrictions are calculated independently per player in personal trees and once for the entire server in global trees.

### Localization

When the client language code begins with `de_`, the screen uses `gname` and `beschreibung`. Every other language uses `name` and `description`. Missing German text falls back to English.

General UI strings are stored in:

- `src/main/resources/assets/crazy-world-progression/lang/en_us.json`
- `src/main/resources/assets/crazy-world-progression/lang/de_de.json`

### Stats and effects

Stats are internal expressions and are deliberately not shown in the node tooltip. The built-in translations are:

| Expression | Effect |
| --- | --- |
| `addHealth(1)` | Adds one max-health point, equal to half a heart |
| `addMiningSpeed(2)` | Adds 2% block-breaking speed |
| `addMovementSpeed(2)` | Adds 2% movement speed |

Values from every unlocked global and personal skill are accumulated. Effects are recalculated after a purchase and restored when a player joins or respawns.

New translation names are registered in `SkillStatRegistry.initialize()`. Attribute-backed translations can be added there with `registerAttribute(...)`. Other behavior can use the public `SkillStatRegistry.register(...)` API; its callback receives the cumulative configured value and should apply that total idempotently.

### Validation

The loader rejects invalid definitions before gameplay, including:

- Duplicate YAML keys, tree IDs, node IDs, or English node names.
- Missing prerequisites, self-dependencies, and dependency cycles.
- Negative or fractional costs and invalid `following` values.
- Unknown or malformed stat functions.
- Currencies that are not permitted for the tree scope.
- An empty skill-tree directory.

Errors include the YAML filename to make the faulty definition easier to locate.

### Development and packaged loading

In a Loom development run, skill trees are read directly from `src/main/resources/default-skilltrees/`. You can edit the tracked YAML without rebuilding the JAR, but the trees are loaded at server startup, so restart the development server or client world to apply changes.

Released builds read the same directory from inside the packaged mod JAR. Editing a released tree therefore requires rebuilding the JAR. The mod does not currently copy these files to an external `config/` directory and has no in-game reload command.

## Reward integration API

Currency services are the intended integration points for future gameplay reward listeners:

- `KingdomProgressionService.addKingdomPoints(...)` changes shared KP.
- `PlayerProgressionService.credit(..., PersonalCurrency.ECHELON_POINTS, ...)` awards EP.
- `PlayerProgressionService.credit(..., PersonalCurrency.FAKHRUL_CURRENCY, ...)` awards FC.
- `PlayerProgressionService.claimPowerfulSoul(...)` awards one unique PS while enforcing duplicate and lifetime limits.

All values are non-negative `long` balances. Credits use overflow checking, debits stop at zero, and purchases validate every required currency before changing persistent state.

## Persistence and authority

Progression is stored in the world's server-side `SavedData`:

- Kingdom data contains shared KP, the elected king, the active candidate, and global unlocks.
- Player data contains EP, FC, spendable PS, unique PS claim IDs, and personal unlocks keyed by UUID.

Clients receive read-only snapshots for rendering. The server remains authoritative for balances, prerequisites, branch exclusions, global ownership, and purchases. This also allows an administrator to inspect or change profiles that are not currently represented by an online player entity when Minecraft can resolve the profile.

## Other gameplay systems

The repository also currently contains two progression-adjacent systems that run independently of YAML skills:

### Veil

The veil is centered on world spawn and is rendered on the client.

- From 1,000 to 1,200 blocks, players take 2 magic damage every five seconds.
- Beyond 1,200 blocks, players take 5 magic damage every second.

The radii are configured by constants in `VeilManager.java` and shared with the client renderer.

### Baseline player modifiers

Every player receives these transient modifiers on join and respawn:

- 2× block-breaking speed.
- 1.4× movement speed.
- 4 fewer maximum-health points, equal to two hearts.

These values live in `PlayerModifications.java` and are separate from cumulative skill-tree stats.

## Project structure

```text
src/main/java/ekuzo/crazyworldprogression/
├── CrazyWorldProgression.java       Server startup and all initialization ownership
├── command/                         Player and administrative commands
├── progression/
│   ├── kingdom/                     Shared KP, king state, and global unlock persistence
│   ├── player/                      Per-player currencies, PS claims, and unlock persistence
│   └── skilltrees/                  YAML loading, validation, effects, purchases, and networking
└── veil/                             Server-side veil behavior

src/client/java/ekuzo/crazyworldprogression/
├── client/                           Inventory integration, screen, and graph layout
└── veil/                             Client-side veil renderer

src/main/resources/
├── assets/crazy-world-progression/  Language, GUI, currency, and mod assets
├── default-skilltrees/               Tracked YAML tree definitions
└── fabric.mod.json                   Fabric mod metadata
```

Server initialization is intentionally centralized in `CrazyWorldProgression.java`; client-only initialization is centralized in `CrazyWorldProgressionClient.java`.

## Building and running

Clone the repository and use the included Gradle wrapper.

Windows:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
```

Linux or macOS:

```bash
./gradlew build
./gradlew runClient
./gradlew runServer
```

Build output is written to `build/libs/`. Use the remapped mod JAR rather than the sources JAR. The development configuration also defines a second client named `client2`, useful for testing multiplayer behavior with a separate `Player` account.

## Current limitations

- The election command can start or clear one candidate, and the service can appoint a winner, but vote casting, counting, timing, and automatic resolution are not implemented yet.
- Gameplay events that automatically award EP, FC, or PS are not connected yet. The persistence and safe award APIs are ready for those integrations; administration commands are currently the available in-game testing path.
- Skill-tree YAML is bundled into release JARs rather than copied to an external server configuration directory.
- There is no skill-tree reload command; definitions are loaded on server startup.

## License

Crazy World Progression is licensed under the [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`). The same identifier is declared in `fabric.mod.json`.
