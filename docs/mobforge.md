# MobForge

For the current Python-like `.mfs` script format, see the [scripting manual](mobforge-scripting.md). Existing YAML scripts remain readable and convert to `.mfs` when saved through the admin editor.

MobForge is the mob encounter system built into Crazy World Progression for Minecraft 26.2. Install the CWP JAR on the client and server; no separate MobForge JAR is needed. For template-defined mobs, YAML files in `config/mobforge/templates/` are the single source of truth. Open `/cwp admin` → **MobForge** to create and edit mob templates and scripts, spawn registered mobs, run scripts, and inspect content-mod registrations. The in-game script editor saves Python-like `.mfs` files through server-validated packets.

Content mods can also register namespaced mobs, abilities, and behaviors through the [developer API](mobforge-api.md). YAML templates remain the source of truth for template-defined mobs; registered Java definitions are immutable code registrations. Both can be spawned through CWP's unified spawn service.

## Commands

All modifying commands require the vanilla administrator permission.

```text
/mobforge reload
/mobforge template list
/mobforge template info <id>
/mobforge editor [id]
/mobforge spawn <id> [x y z] [count]
/mobforge purge [template]
/mobforge script list
/mobforge script info <id>
/mobforge script run <id>
/mobforge script purge <id>
/mobforge script editor [id]
/mobforge ai <selector> vanilla
/mobforge ai <selector> target-position <x y z>
/mobforge ai <selector> target-player <player>
/mobforge ai <selector> nearest-player
/mobforge attribute <selector> <attribute> <value>
```

Console, command blocks, and functions can use the non-graphical commands. Players using CWP can open the graphical editors. Spawned mobs remain vanilla entity types.

## Template example

```yaml
id: raid_zombie
entity: minecraft:zombie
name: Raid Zombie
multipliers:
  movement_speed: 1.25
  scale: 1.2
attributes:
  max_health: 40.0
  attack_damage: 8.0
  armor: 4.0
equipment:
  main_hand:
    id: minecraft:iron_sword
    count: 1
properties:
  persistent: true
  burns_in_daylight: true
  can_pick_up_loot: false
  silent: false
  glowing: false
  invulnerable: false
  no_gravity: false
  despawn_behavior: vanilla
loot:
  drop_vanilla_loot: false
  xp: 0
tags:
  - raid
  - undead
ai:
  mode: target_position
  combat: true
  target:
    x: 0
    y: 80
    z: 0
  arrival_radius: 3.0
```

Supported AI modes are `vanilla`, `target_position`, `target_player`, and `nearest_player`. Path recalculation is staggered at one-second intervals; templates are parsed only on startup, reload, or editor save.

`multipliers` scale the entity type's own default value: `scale: 1.2` makes it 20% bigger, `movement_speed: 1.25` 25% faster. The editor uses multipliers for size, movement speed and attack speed, and absolute `attributes` for health, attack damage, follow range, armor, toughness, knockback resistance and attack knockback. In YAML, `max_health`, `attack_damage` and `follow_range` may also be multipliers; the editor shows them as absolute values. One attribute cannot be in both sections.

Movement speed above `0.70` (absolute, or default times multiplier) is rejected because vanilla node navigation becomes unstable and visibly rubber-bands; most hostile mobs walk at about `0.20`–`0.35`.

`/mobforge editor [id]` and the **MobForge** tab in `/cwp admin` open the same editor in the admin panel. Entity type and equipment are picked with type-to-search fields (with a picture of the entity and item icons), and multipliers show the entity's defaults next to them. Equipment components in YAML are kept as they are; the editor does not change them.

## Legacy YAML wave scripts

Scripts are loaded from `config/mobforge/scripts/`. They execute `steps` from top to bottom. Numeric sleep values are seconds; explicit units such as `40t`, `5s`, `500ms`, and `2m` are also supported.

```yaml
id: three_wave_invasion

steps:
  - spawn:
      template: raid_zombie
      location:
        x: 700
        y: 70
        z: 400
      offset:
        x: 10
        y: 0
        z: 10
      count: 30
      ai:
        mode: target_position
        combat: true
        target: {x: 0, y: 80, z: 0}
        arrival_radius: 4

  - sleep: 10s

  - spawn:
      template: elite_skeleton
      location: {x: 700, y: 70, z: 400}
      offset: 12
      count: 10

  - sleep: 5s

  - type: spawn
    template: boss_warden
    location: {x: 700, y: 70, z: 400}
    count: 1
```

Pocket scripts can reward every player currently inside that instance at a chosen timeline step:

```yaml
  - reward_currency: {currency: echeloncore:echelon_points, amount: 12}
  - reward_item: {item: minecraft:diamond, count: 3}
  - reward_loot: {loot_table: minecraft:chests/simple_dungeon, rolls: 2}
```

Currency rewards require a registered player-scoped currency. Item rewards accept 1–2304 items per player; loot-table rewards make 1–32 separate chest-table rolls for each player. Excess items are dropped at the player's position when their inventory is full. Reward steps require a pocket instance and fail in a standalone world script run.

## Raid checks and portal presets

The in-panel editor and `.mfs` scripts support **Variable**, **Watch event**, **Event box**, **Redstone sensor**, **If / Else / End if**, **Loop / End loop**, and **Portal location** steps. Variables, kill counts, portal use counts, event boxes, and watches belong to one script run in one active pocket instance. A watch changes its variable when its condition changes from false to true. Bare `${kills.raid_add}` and `${portal.cwp:raid_gate}` watches instead fire once per new kill or successful portal use. Mob tags are Minecraft entity tags, including custom MobForge template tags. Event box counts use players' feet; its maximum block is included. Redstone sensors check both a block's own powered state and incoming power.

Conditions accepted by **If**, **Loop**, **Wait until**, and **Watch event** include `${var.charge >= 3}`, `${box.plate >= 2}`, `${kills.raid_add >= 6}`, `${redstone.switch == true}`, and `${portal.cwp:raid_gate >= 1}`, alongside existing mob death, health, and phase checks. Variable values may be whole numbers or `true`/`false` (represented as 1/0). Loops are bounded by their configured maximum iterations; scripts also execute at most 256 steps per server tick. Add a Sleep step inside loops that wait for player or world actions.

**Portal location** uses the ID of a saved portal as a preset. Disable **Initial portal active** in the portal editor to make a preset with no starting location. `add` places or replaces a named location, `remove` deletes one named location, and `clear` removes all script locations and hides the preset's original projected location in that instance. Multiple named locations of the same portal ID can be active together. Its links, style, schedule, and entry policy are shared by every location. Script placements are scoped to the running pocket instance and disappear when that instance expires. All authored XYZ positions are translated from the pocket editor to each active instance.

`/mobforge purge` kills currently loaded MobForge entities but deliberately leaves scripts running. `/mobforge script purge <id>` cancels every active run of that script and kills its currently loaded entities. Script-spawned entities carry persistent script and run metadata.

The **MobForge** tab in `/cwp admin` opens separate mob and script lists. Each YAML mob has an in-panel editor for identity, attributes, equipment, properties, loot, tags, and AI. A pocket's **Mob scripts** section can attach existing scripts, create scripts, open the in-panel editor, and remove attachments. `/mobforge script editor [id]` opens the full in-panel editor. The editor supports Spawn and Sleep plus named `mob_spawn`, `wait`, `mob_cast`, `mob_phase`, `trigger`, `reward_currency`, `reward_item`, and `reward_loot` steps. The admin editor offers ID suggestions and validates the complete script on save. Each Spawn step can keep its template AI or override it with `vanilla`, `target_position`, `target_player`, or `nearest_player` settings. Invalid saves report the server error and retain the draft. Attached scripts start for each newly created portal instance and are canceled on expiry.

Build CWP with Java 25 using `./gradlew build`.
