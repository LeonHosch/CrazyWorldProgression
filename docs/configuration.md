# Configuration

CWP keeps all of its settings in one file, `config/crazy-world-progression.yml`. CWP creates the file the first time the game or server starts, and rewrites it on every start. That way new settings appear in it, each with an explanation. Changes take effect after a restart.

The file has three sections:

- **modules:** turns each part of the framework on or off.
- **worlds:** sets how many instance and workshop worlds CWP creates.
- **performance:** the tick time budget for block work, in milliseconds. These settings used to live in `crazy-world-progression-performance.properties`. CWP copies them over once, and after that it no longer reads the old file.
- **permissions:** who is a builder (may use creative and spectator, helpers and workshops).
- **buildings:** how script `building` steps without their own `mode` place and remove saved buildings.

## Parts

Each part is `true` (on) or `false` (off). A part that is off does nothing. It has no events or commands, no screens or admin pages, and its mixins are not applied, so it makes no changes to the game. Its items, menus, effects and saved data stay registered, though. A world that used the part still loads with its items and data intact, and switching the part back on brings everything back.

| Part | What it covers |
|---|---|
| `skill_trees` | YAML skill trees, the skill tree screen and `/skilltree` |
| `character_sheet` | The character sheet beside the inventory |
| `skills` | Skill levels gained by doing, and their passives |
| `equipment` | Equipment sets, protection stats and set effects |
| `smithing` | Craftsmanship: item quality from crafting, the smithing table, repairs and durability |
| `reforge` | Reforging and research |
| `fletching` | Arrow crafting and special arrow effects |
| `ammo` | Ammo slots and ammo switching |
| `accessories` | Accessory slots and the buff food slot |
| `farming` | Farming levels, produce quality, crossbreeding and animal produce |
| `cooking` | Cooking and food quality |
| `fishing` | Fishing loot, the fishing journal and fishing levels |
| `mob_loot` | Loot pools for mob drops |
| `storage` | Chest qualities, scrolling chests, chest filters, quicksort, sort buttons and item locks |
| `social` | Friends, clans, clan chat, clan missions and chest locks |
| `collectibles` | Collectibles and mounts |
| `divine_protection` | Divine protection |
| `respawn` | Respawn feedback and instant respawn |
| `titles` | Player titles |
| `currency` | Currency wallets and commands |
| `postmaster` | The postmaster |
| `random_events` | Random world events |
| `world_boundary` | The advanced world boundary, area restrictions and bonus areas |
| `portals` | Portals, pockets and workshops, instances, raids, parties, buildings and waypoints |
| `mobforge` | Custom mobs, their AI and encounter scripts |

Some things are always on because the parts depend on them: the stat system, the admin panel, area selection and the tick budget.

For example, a mod that only needs skill trees can set `skill_trees: true` and turn every other part off. A server set up this way runs no portal worlds at all.

On a server, each player's game reads its own copy of this file to decide which screens and client features to load. Ship the same file in your modpack. If the copies differ, nothing breaks: CWP only sends a packet when the other side listens for it. A player whose copy turns a part off simply doesn't get that part's screens.

### Parts another mod needs

A content mod can keep the parts it needs switched on by declaring them in its `fabric.mod.json`:

```json
"custom": {
  "crazy-world-progression": {
    "requires_modules": ["skill_trees", "portals"]
  }
}
```

A required part stays on even if the file says `false`. CWP logs a warning when that happens and notes it in the file next to the part. EchelonCore requires `mobforge`, `world_boundary`, `currency`, `collectibles`, `fletching`, `reforge`, `equipment`, `skill_trees`, `random_events`, `divine_protection` and `accessories`. The admin panel's **CWP status** page lists every part and which mods need it.

## Worlds

```yaml
worlds:
  instances: 8
  workshops: 32
```

- **instances:** how many instances (dungeon runs, raids, public instances) can run at once. Each one needs its own world.
- **workshops:** how many pockets can exist. Each pocket is built in its own workshop world.

Both can be set from 0 to 256. Every world is a loaded world, so only create as many as you need. With `portals` off, CWP creates no worlds, not even the shared portal workshop.

Minecraft can't add or remove worlds while a server is running, so CWP writes them as a data pack at startup. The pack goes in `.crazy-world-progression/worlds-pack` in the game folder and is always enabled. Lowering a count is safe:

- Pockets assigned to a workshop above the new count are hidden until you raise it again. Their saved builds aren't touched.
- The next load logs one `Failed to parse saved data for 'SavedDataType[minecraft:world_clocks]'` error for the removed instance worlds' clocks. It is harmless.

## Performance

```yaml
performance:
  tick_buffer_ms: 10.0
  min_budget_ms: 2.0
  max_budget_ms: 30.0
  script_max_ms: 30.0
  cleanup_floor_ms: 1.0
```

See [performance](performance.md) for how the budget is shared between scripts, instance building and cleanup.

## Permissions

```yaml
permissions:
  builder: op2
```

`op2` (default): operator level 2 or higher is a builder. `op1`: level 1 or higher. Only builders may be in creative or spectator mode. See [workshop helpers](workshop-helpers.md#builders-and-creative-mode).

## Buildings

```yaml
buildings:
  placement_mode: skip_air
```

`skip_air` (default) places only a building's own blocks and removes only those. `replace` writes its air as well and clears its whole box on removal (the behaviour before this setting). A step's own `mode` wins. See [workshop helpers](workshop-helpers.md#building-placement-mode).
