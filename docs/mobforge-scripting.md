# MobForge scripting manual

MobForge scripts are plain UTF-8 `.mfs` files in `config/mobforge/scripts/`. They look like Python, but run through CWP's bounded game-script interpreter. They cannot import Python modules, access files, call Java methods, or execute arbitrary Python code. The script ID in the first line must match the filename, without `.mfs`, and use lowercase letters, digits, `_`, or `-`.

```python
script plate_encounter

# Coordinates in a pocket script are authored in the pocket editor.
event_box(name="plate", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2})
variable(name="charge", operation="set", value="0")
event(condition="portal_used(\"cwp:entrance\")", variable="charge", operation="increment", value="1")

while kills("raid_add") < 6, max_iterations=1000:
    if players_in("plate") >= 2 and powered(10, 64, 5):
        trigger(event="cwp:plate_ready")
    else:
        sleep(duration="1s")

if var("charge") >= 1:
    portal_location(portal="cwp:boss_gate", action="add", location="arena", position={"x":20,"y":64,"z":10})
```

## Layout and execution

- `script <id>` starts a file. Empty lines and `#` comments are allowed.
- Commands use named arguments: `spawn(template="raid_zombie", count=3, location={"x":0,"y":64,"z":0})`.
- Use four spaces per indentation level. `if <condition>:`, `else:`, and `while <condition>:` define blocks. A block needs at least one command. The text format adds the matching end steps automatically.
- `while` accepts `max_iterations=<1..100000>` after the condition. The default is 1000. Each completed iteration yields until the next server tick, so live player, mob, portal, and redstone checks can change. An infinite true condition eventually reaches the configured cap and cancels that run.
- Scripts execute at most 256 operations per tick and wait/sleep across ticks. A run's variables, kill counts, watches, and portal counters belong to that run in that instance.
- Strings use quotes. Numeric and boolean values use `3`, `2.5`, `true`, or `false`. Positions use `{"x":0,"y":64,"z":0}`. For `variable` and `event`, `value` is a quoted whole number or `"true"`/`"false"`.

## Conditions

Use conditions in `if`, `while`, `wait_until`, and `event`. Combine them with `and`, `or`, and `not` (in that precedence order: `not`, then `and`, then `or`). Supported checks are:

| Check | Example | Meaning |
| --- | --- | --- |
| `kills(id)` | `kills("cwp:raid_add") >= 6` | Count mob deaths by MobForge template ID, entity type ID, definition ID, or tag. |
| `portal_used(id)` | `portal_used("cwp:gate") >= 1` | Count successful uses of a portal ID in this run. |
| `players_in(box)` | `players_in("plate") >= 2` | Count players or matching mobs, according to the event box mode. |
| `box_triggers(box)` | `box_triggers("plate") >= 2` | Count transitions from empty to occupied in an event box. |
| `powered(x, y, z)` | `powered(10, 64, 5)` | Check redstone power at block coordinates. Pocket editor coordinates translate to the active instance. This is the editor's single redstone check. |
| `var(name)` | `var("charge") >= 3` | Read a run variable; unset variables read as zero. |
| `dead(handle)` | `dead("boss")` | Check a named spawned entity handle. |
| `health_percent(handle)` | `health_percent("boss") <= 0.5` | Check a mob's health fraction. |
| `phase(handle)` | `phase("boss") == "enraged"` | Check a MobForge mob phase. |

The older `${kills.raid_add >= 6}` style still works in imported scripts and in the in-game condition fields. Conditions are deliberately limited: arithmetic, arbitrary function calls, and general Python expressions are not supported.

## Commands

The in-game admin script editor exposes the same commands as steps. All IDs that refer to registered content use their full namespace, such as `cwp:gate` or `minecraft:diamond`.

| Command | Example | Effect |
| --- | --- | --- |
| `spawn` | `spawn(template="raid_zombie", location={"x":0,"y":64,"z":0}, offset={"x":2,"y":0,"z":2}, count=3)` | Spawn MobForge template mobs. Optional `ai={"mode":"nearest_player","combat":true,"range":32}` overrides template AI. |
| `spawn_mobs` | `spawn_mobs(mob_id="minecraft:zombie", location={"x":0,"y":64,"z":0}, offset={"x":2,"y":0,"z":2}, count=3)` | Spawn mobs by entity or MobForge ID at a position. |
| `sleep` | `sleep(duration="1s")` | Pause; durations accept ticks (`40t`), seconds (`5s`), milliseconds (`500ms`), or minutes (`2m`). |
| `spawn_entity` | `spawn_entity(id="minecraft:zombie", at="origin", save_as="boss")` | Spawn an entity at an anchor and save a handle. `pocket_spawn` is available in pocket scripts. |
| `wait_until` | `wait_until(condition="dead(\"boss\")", timeout="5m")` | Poll until true, or cancel this run on timeout. |
| `mob_cast` | `mob_cast(mob="${boss}", ability="cwp:slam")` | Ask a MobForge handle to cast an ability. |
| `set_phase` | `set_phase(mob="${boss}", phase="enraged")` | Change a MobForge mob's phase. |
| `trigger` | `trigger(event="cwp:plate_ready")` | Emit a script event. |
| `variable` | `variable(name="charge", operation="increment", value="1")` | Set or increment a run variable. |
| `event` | `event(condition="kills(\"raid_add\")", variable="charge", operation="increment", value="1")` | Register a watch. A bare `kills` or `portal_used` condition fires for each new occurrence; other conditions fire on false-to-true changes. |
| `event_box` | `event_box(name="plate", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2}, mode="players", player_variable="entered", count_variable="inside")` | Save every entering player's ID in `entered`, and continuously update the current occupancy in numeric variable `inside`. With `mode="mobs", mob_id="minecraft:zombie"`, the count tracks matching mobs. The maximum block is included. |
| `portal_location` | `portal_location(portal="cwp:gate", action="add", location="arena", position={"x":20,"y":64,"z":10})` | Spawn a saved portal at this position in a world or instance. Location names distinguish copies of one portal. `remove` and `clear` remove locations. |
| `building` | `building(build_id="cwp:bridge", action="spawn", location={"x":5,"y":64,"z":5})` | Paste saved blocks with their minimum corner here. `action="despawn"` clears the entire pasted range, including player changes. |
| `particles` | `particles(particle_id="minecraft:flame", location={"x":0,"y":65,"z":0}, offset={"x":1,"y":1,"z":1}, count=20, speed=0.1)` | Show a particle effect. Particles that require extra data are unsupported. |
| `explosion` | `explosion(location={"x":0,"y":65,"z":0}, power=2)` | Create an explosion that damages entities but leaves blocks intact. |
| `sound` | `sound(sound_id="minecraft:entity.experience_orb.pickup", location={"x":0,"y":65,"z":0})` | Play a sound effect at the location. |
| `effect` | `effect(effect_id="minecraft:speed", player_id="entered", action="apply", amplifier=0)` | Apply an infinite effect to every player ID stored in `entered`. Omit `player_id` to target every player in the instance. Amplifier 0 is level I; the effect ID names the effect, not its level. Use `action="remove"` to remove that effect from the same targets. A literal player UUID also works. |
| `encounter_start` | `encounter_start()` | Begin raid encounter restrictions; each player has one revive to use. |
| `encounter_end` | `encounter_end()` | Finish the raid encounter and reset revive and once-per-attempt entry state. Outside an encounter, ordinary respawning is available. |
| `redstone_sensor` | `redstone_sensor(name="lever", position={"x":10,"y":64,"z":5})` | Legacy named redstone check; existing scripts still load. Use `powered(x, y, z)` for new scripts. |
| `reward_currency` | `reward_currency(currency="cwp:coins", amount=100)` | Credit each player currently in the pocket instance; currency must be player scoped. |
| `reward_item` | `reward_item(item="minecraft:diamond", count=1)` | Give each player currently in the pocket instance an item. |
| `reward_loot` | `reward_loot(loot_table="minecraft:chests/simple_dungeon", rolls=1)` | Roll a chest loot table separately for each player in the instance. |
| `loot` | `loot(lootpool_id="cwp:raid_rewards", player="entered")` | Roll a saved CWP loot pool for players stored in `entered`. Omit `player` to roll separately for every player in the pocket instance. A literal player UUID also works. |
| `achievement` | `achievement(achievement_id="echelon-core:mysteries/root", player="entered")` | Complete a real Minecraft advancement for players saved in `entered`. Omit `player` for everyone in the script's world. A literal player UUID also works. Advancements can unlock titles. |
| `raid_complete` | `raid_complete()` | Legacy alias for `encounter_end()` in a raid instance. |

The keys inside `ai={...}` use the existing MobForge AI fields: `mode`, `combat`, `target` (a position), `arrival_radius`, `player`, `range`, and any template-specific AI settings. `template_default` means omit `ai` entirely.

## Raid example

```python
script boss_encounter

event_box(name="start", min={"x":0,"y":64,"z":0}, max={"x":3,"y":67,"z":3})
wait_until(condition="players_in(\"start\") >= 6", timeout="30m")
encounter_start()
spawn(template="raid_add", location={"x":12,"y":64,"z":12}, count=6)
while kills("raid_add") < 6, max_iterations=36000:
    sleep(duration="1s")
portal_location(portal="cwp:boss_gate", action="add", location="after_adds", position={"x":25,"y":64,"z":12})
encounter_end()
```

Configure the pocket's entrance portal as **Private** with **Raid mode**, and set its group limit to the team size. A projected Coordinates portal with **Once per attempt** can serve as the encounter entrance. On a wipe, the pocket scripts restart and each player's once-per-attempt entry record clears.

A wipe, `encounter_end()`, or raid instance end removes all effects from players in that instance and clears the script's variables. The player list and live count variables can be reused in a later encounter.

Use **Buildings** in `/cwp admin` to name and save a selected block range. The saved copy persists after the source blocks are removed. Scripts can paste or clear that copy at any location. Add `encounter_start()` before the fight if pre-fight sections should allow unlimited respawns.

When you enter the pocket editor, attached scripts show the non-air blocks of every building spawn as translucent purple blocks at the scripted coordinates. This is a preview only. In a live instance, the coordinates shift with the pocket so the building keeps the same position relative to the saved arena. Building copies are cleared on a wipe or when an instance slot is released, including copies outside the arena's captured range.
Scripted portal `add` locations are previewed there in the same purple, using each saved portal's shape, plane, width, and height. The preview does not open a portal or trigger travel.

## Editing, migration, and errors

Use the **MobForge** or **Pocket → Mob scripts** section in `/cwp admin` to add, insert, move, edit, and save steps. The editor saves `.mfs` source and remains useful if you prefer forms to text. To edit as text, modify `config/mobforge/scripts/<id>.mfs` and run `/mobforge reload` to load it. Failed files are logged with their filename; the server keeps other valid scripts.

Existing `.yml` and `.yaml` scripts still load. Saving one from the in-game editor converts it to `.mfs` and removes its old YAML file after the new file is written and validated. Keep a copy before editing an existing script by hand. Mob templates and other CWP configuration files still use their own formats.
