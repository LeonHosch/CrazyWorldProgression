# MobForge

MobForge is the mob encounter system built into Crazy World Progression for Minecraft 26.2. Install the CWP JAR on the client and server; no separate MobForge JAR is needed. For template-defined mobs, YAML files in `config/mobforge/templates/` are the single source of truth. Open `/cwp admin` → **MobForge** to create and edit mob templates and scripts, spawn registered mobs, run scripts, and inspect content-mod registrations. The in-game editor reads and saves the YAML schema through server-validated packets.

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
attributes:
  max_health: 40.0
  movement_speed: 0.3
  attack_damage: 8.0
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

`movement_speed` is the absolute vanilla attribute value, not a percentage or multiplier. Most hostile mobs use approximately `0.20`–`0.35`; MobForge rejects values above `0.70` because vanilla node navigation becomes unstable and visibly rubber-bands at extreme speeds.

## YAML wave scripts

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

`/mobforge purge` kills currently loaded MobForge entities but deliberately leaves scripts running. `/mobforge script purge <id>` cancels every active run of that script and kills its currently loaded entities. Script-spawned entities carry persistent script and run metadata.

The **MobForge** tab in `/cwp admin` opens the script editor. `/mobforge script editor [id]` remains a command shortcut. The editor supports legacy Spawn and Sleep plus named `spawn_entity`, `wait_until`, `mob_cast`, `set_phase`, and `trigger` steps. It supplies server-authoritative entity and ability ID pickers. Each legacy Spawn step can keep its template AI or override it with `vanilla`, `target_position`, `target_player`, or `nearest_player` settings. A save rejected by the server leaves the draft marked unsaved and shows the error in the editor.

Build CWP with Java 25 using `./gradlew build`.
