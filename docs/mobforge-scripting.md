# MobForge scripting manual

MobForge scripts are plain UTF-8 `.mfs` files in `config/mobforge/scripts/`. They look like Python, but run through CWP's bounded game-script interpreter. They cannot import Python modules, access files, call Java methods, or execute arbitrary Python code. The script ID in the first line must match the filename, without `.mfs`, and use lowercase letters, digits, `_`, or `-`.

```python
script plate_encounter

# Coordinates in a pocket script are authored in the pocket editor.
eventbox(name="plate", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2})
variable(name="charge", operation="set", value="0")
event(condition="portal_used(\"cwp:entrance\")", variable="charge", operation="increment", value="1")

while kills("raid_add") < 6, max_iterations=1000:
    if players_in("plate") >= 2 and powered(10, 64, 5):
        trigger(event="cwp:plate_ready")
    else:
        sleep(duration="1s")

if var("charge") >= 1:
    portal(portal="cwp:boss_gate", action="add", location="arena", position={"x":20,"y":64,"z":10})
```

## Layout and execution

- `script <id>` starts a file. Empty lines and `#` comments are allowed.
- Commands use named arguments: `mob_spawn(template="raid_zombie", count=3, location={"x":0,"y":64,"z":0})`.
- Use four spaces per indentation level. `if <condition>:`, `else:`, and `while <condition>:` define blocks. A block needs at least one command. The text format adds the matching end steps automatically.
- `while` accepts `max_iterations=<1..100000>` after the condition. The default is 1000. Each completed iteration yields until the next server tick, so live player, mob, portal, and redstone checks can change. An infinite true condition eventually reaches the configured cap and cancels that run.
- Scripts execute at most 256 operations per tick and wait/sleep across ticks. A run's variables, kill counts, watches, and portal counters belong to that run in that instance.
- Strings use quotes. Numeric and boolean values use `3`, `2.5`, `true`, or `false`. Positions use `{"x":0,"y":64,"z":0}`. For `variable` and `event`, `value` is a quoted whole number or `"true"`/`"false"`.

## Conditions

Use conditions in `if`, `while`, `wait`, and `event`. Combine them with `and`, `or`, and `not` (in that precedence order: `not`, then `and`, then `or`). Supported checks are:

| Check | Example | Meaning |
| --- | --- | --- |
| `kills(id)` | `kills("adds") >= 6` | Count mob deaths by `mob_spawn` tag, MobForge template ID, entity type ID, definition ID, or MobForge tag. |
| `portal_used(id)` | `portal_used("cwp:gate") >= 1` | Count successful uses of a portal ID in this run. |
| `players_in(box)` | `players_in("plate") >= 2` | Count players or matching mobs, according to the event box mode. |
| `box_triggers(box)` | `box_triggers("plate") >= 2` | Count transitions from empty to occupied in an event box. |
| `powered(x, y, z)` | `powered(10, 64, 5)` | Check redstone power at block coordinates. Pocket editor coordinates translate to the active instance. This is the editor's single redstone check. |
| `var(name)` | `var("charge") >= 3` | Read a run variable; unset variables read as zero. |
| `dead(tag)` | `dead("boss")` | True once every mob spawned with this tag is dead. |
| `health_percent(tag)` | `health_percent("boss") <= 0.5` | Combined health of the living mobs with this tag, as a fraction of their combined maximum. |
| `phase(tag)` | `phase("boss") == "enraged"` | Phase of the first living MobForge mob with this tag. |

The older `${kills.raid_add >= 6}` style still works in imported scripts and in the in-game condition fields. Conditions are deliberately limited: arithmetic, arbitrary function calls, and general Python expressions are not supported.

## Commands

The in-game admin script editor exposes the same commands as steps. All IDs that refer to registered content use their full namespace, such as `cwp:gate` or `minecraft:diamond`.

| Command | Example | Effect |
| --- | --- | --- |
| `mob_spawn` | `mob_spawn(template="raid_zombie", location={"x":0,"y":64,"z":0}, offset={"x":2,"y":0,"z":2}, count=3, tag="adds")` | Spawn mobs. Give exactly one of `template=` (a MobForge template) or `mob_id=` (an entity type or MobForge ID, such as `"minecraft:zombie"`). `count` defaults to 1. With `template=`, optional `ai={"mode":"nearest_player","combat":true,"range":32}` overrides the template AI. The optional `tag` names these mobs so `kills("adds")`, `dead("adds")`, `health_percent("adds")`, `phase("adds")`, `mob_cast` and `mob_phase` can refer to them. Several `mob_spawn` steps can share a tag. |
| `sleep` | `sleep(duration="1s")` | Pause; durations accept ticks (`40t`), seconds (`5s`), milliseconds (`500ms`), or minutes (`2m`). |
| `wait` | `wait(condition="dead(\"boss\")", timeout="5m")` | Poll until true, or cancel this run on timeout. |
| `mob_cast` | `mob_cast(mob="boss", ability="cwp:slam")` | Every living MobForge mob with this tag casts the ability. |
| `mob_phase` | `mob_phase(mob="boss", phase="enraged")` | Every living MobForge mob with this tag changes phase. |
| `trigger` | `trigger(event="cwp:plate_ready")` | Emit a script event. |
| `variable` | `variable(name="charge", operation="increment", value="1")` | Set or increment a run variable. |
| `event` | `event(condition="kills(\"raid_add\")", variable="charge", operation="increment", value="1")` | Register a watch. A bare `kills` or `portal_used` condition fires for each new occurrence; other conditions fire on false-to-true changes. |
| `eventbox` | `eventbox(name="plate", min={"x":0,"y":64,"z":0}, max={"x":2,"y":66,"z":2}, mode="players", player_variable="entered", count_variable="inside")` | Save every entering player's ID in `entered`, and continuously update the current occupancy in numeric variable `inside`. With `mode="mobs", mob_id="minecraft:zombie"`, the count tracks matching mobs. The maximum block is included. |
| `portal` | `portal(portal="cwp:gate", action="add", location="arena", position={"x":20,"y":64,"z":10})` | Place a copy of a saved portal preset at this position in this world or instance. `location` is the name of this copy: one preset can have several copies with different names, adding the same name again moves that copy, and `action="delete", location="arena"` removes just that copy. `action="delete"` without a location removes every copy of the preset in this instance, including its original placement. Coordinates portals also accept `target={...}`. |
| `building` | `building(build_id="cwp:bridge", action="add", location={"x":5,"y":64,"z":5})` | Paste saved blocks with their minimum corner here. `action="delete"` clears the entire pasted range, including player changes. |
| `particles` | `particles(particle_id="minecraft:flame", location={"x":0,"y":65,"z":0}, offset={"x":1,"y":1,"z":1}, count=20, speed=0.1)` | Show a particle effect. Particles that require extra data are unsupported. |
| `explosion` | `explosion(location={"x":0,"y":65,"z":0}, power=2)` | Create an explosion that damages entities but leaves blocks intact. |
| `sound` | `sound(sound_id="minecraft:entity.experience_orb.pickup", location={"x":0,"y":65,"z":0})` | Play a sound effect at the location. |
| `effect` | `effect(effect_id="minecraft:speed", player_id="entered", action="apply", amplifier=0)` | Apply an infinite effect to every player ID stored in `entered`. Omit `player_id` to target every player in the instance. Amplifier 0 is level I; the effect ID names the effect, not its level. Use `action="remove"` to remove that effect from the same targets. A literal player UUID also works. |
| `encounter_start` | `encounter_start()` | Begin raid encounter restrictions; each player has one revive to use. First clears everything the script manages (see below). |
| `encounter_end` | `encounter_end(spawn={"x":-15,"y":64,"z":-10})` | Finish the raid encounter, reset revive and once-per-attempt entry state, and save a checkpoint for everyone inside. First clears everything the script manages (see below). The optional `spawn` becomes the rally, respawn and rejoin point, and is where a run resumed from this checkpoint starts. Outside an encounter, ordinary respawning is available. |
| `wipe` | `wipe()` | Fail the encounter, for example on an enrage timer. Everyone still standing is downed in place as a spectator (they do not die, so nothing drops) and an "Encounter failed" banner shows. After 5 seconds the encounter resets like a normal wipe. If `encounter_end()` runs within those 5 seconds, the reset is cancelled, everyone is brought back at the new spawn, and a "Cleared in the clutch!" banner shows. Operators can test it with `/cwp wipe` inside a raid. |
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

eventbox(name="start", min={"x":0,"y":64,"z":0}, max={"x":3,"y":67,"z":3})
wait(condition="players_in(\"start\") >= 6", timeout="30m")
encounter_start()
mob_spawn(template="raid_add", location={"x":12,"y":64,"z":12}, count=6, tag="adds")
while kills("adds") < 6, max_iterations=36000:
    sleep(duration="1s")
portal(portal="cwp:boss_gate", action="add", location="after_adds", position={"x":25,"y":64,"z":12})
encounter_end()
```

Configure the pocket's entrance portal as **Private** with instance mode **Raid** (or **Dungeon**), and set its party limit to the team size. A projected Coordinates portal with **Once per attempt** can serve as the encounter entrance. On a wipe, the pocket scripts restart and each player's once-per-attempt entry record clears.

A wipe, `encounter_end()`, or raid instance end removes all effects from players in that instance and clears the script's variables. The player list and live count variables can be reused in a later encounter.

Use **Buildings** in `/cwp admin` to name and save a selected block range. The saved copy persists after the source blocks are removed. Scripts can paste or clear that copy at any location. Add `encounter_start()` before the fight if pre-fight sections should allow unlimited respawns.

When you enter the pocket editor, attached scripts show the non-air blocks of every building spawn as translucent purple blocks at the scripted coordinates. This is a preview only. In a live instance, the coordinates shift with the pocket so the building keeps the same position relative to the saved arena. Building copies are cleared on a wipe or when an instance slot is released, including copies outside the arena's captured range.
Scripted portal `add` locations are previewed there in the same purple, using each saved portal's shape, plane, width, and height. The preview does not open a portal or trigger travel.

## Encounter boundaries and checkpoints

`encounter_start()` and `encounter_end()` both clear everything the script manages in the instance before doing anything else: mobs the script spawned, pasted buildings, portal locations the script added, eventboxes, redstone sensors, and their counters. Re-add anything that should still exist after the boundary, such as an exit portal or bridge, with steps after it. `encounter_end()` also clears the script's variables (existing behavior); `encounter_start()` keeps them.

Each `encounter_end()` gives every party member in the instance a raid checkpoint for 24 hours. A new run, or a run restarted after a wipe, starts directly after the party leader's last completed `encounter_end()`. If that step has a `spawn`, players are moved there and it stays the rally point. Loot, achievements and other rewards right after it are skipped until the next `encounter_start()`, so they are not given twice. Without a `spawn`, players stay at the pocket's normal spawn.

## Deaths in raids and dungeons

Raids and dungeons (portals set to **Dungeon** access) use the same rules. Players never really die inside one. Instead they go down: no death screen, they become spectators at their **revive point**, and a "Revive <name>" marker appears there. Going down and being revived never touch the inventory. A downed spectator cannot fly more than 10 blocks from the revive point.

- The revive point is where they went down. If they fell out of the world, it is the last spot where they stood on solid ground, and they are moved there.
- If they go down again within 5 seconds of getting back up, or within 20 seconds when falling out of the world or drowning, the revive point is the checkpoint spawn instead (the last `encounter_end` spawn, or the pocket's spawn).
- Outside an encounter, after 15 seconds they can fly into their revive point to get back up there. The action bar counts down and then says so.
- In a dungeon, teammates can revive as often as they like. During an encounter, a dungeon that keeps inventory allows self-revive after 60 seconds; a dungeon without keep-inventory has no self-revive during encounters.
- In a raid, during an encounter there is no self-revive and each teammate has one revive per attempt.
- Reviving: right-click the marker and stay within 5 blocks for 2 seconds. The encounter wipes when everyone is down.

## Inventory orbs

Items are never dropped in a raid. Only when a wipe actually resets the encounter (not when a clutch clear cancels it), and only if the raid portal does not keep inventory, is each downed player's whole inventory stored in an **inventory orb** at their revive marker. Their inventory is then empty. The orb glows and shows "<name>'s inventory". Only its owner can right-click it to get every item back into its original slot. Items picked up since then move to free slots or, if there is no room, to the postmaster, and anything that still fits nowhere stays in the orb. If the same player wipes again before claiming it, the old orb and its contents are destroyed and the new inventory takes its place. Orbs survive encounter resets and server restarts, but an unclaimed orb is lost when the instance closes. Players who were inside when the encounter started but are offline during the wipe are included: the inventory they logged out with goes into their orb right away, and their inventory is emptied when they log back in. If that orb was destroyed by another wipe or the instance closed in the meantime, their inventory is still emptied. Logging back in before any wipe changes nothing.

## Wipes

When every party member is down, or a `wipe()` step runs, the encounter fails: an "Encounter failed" banner shows and the encounter resets 5 seconds later, back to the checkpoint and rally point, with everyone's previous game mode restored. The script keeps running during those 5 seconds, so if it reaches `encounter_end()` in time (for example, the boss dies to damage already dealt), the encounter counts as cleared: rewards and the checkpoint are given, everyone respawns at that step's spawn, and a "Cleared in the clutch!" banner shows.

## Editing, migration, and errors

Use the **MobForge** or **Pocket → Mob scripts** section in `/cwp admin` to add, insert, move, edit, and save steps. The editor saves `.mfs` source and remains useful if you prefer forms to text. To edit as text, modify `config/mobforge/scripts/<id>.mfs` and run `/mobforge reload` to load it. Failed files are logged with their filename; the server keeps other valid scripts.

Existing `.yml` and `.yaml` scripts still load. Saving one from the in-game editor converts it to `.mfs` and removes its old YAML file after the new file is written and validated. Keep a copy before editing an existing script by hand. Mob templates and other CWP configuration files still use their own formats.

## Renamed steps

Older scripts still load, but the editor and saved scripts use the new names: `event_box` is now `eventbox`, `wait_until` is now `wait`, `portal_location` is now `portal`, `spawn` and `spawn_mobs` are now `mob_spawn`, and `set_phase` is now `mob_phase`. `spawn_entity(id=…, at=…, save_as=…)` can no longer be added, but old scripts that use it still run: its `save_as` name works like a `mob_spawn` tag. Both `portal` and `building` use `action="add"` or `action="delete"`. The old `spawn`/`despawn` (building) and `remove`/`clear` (portal) values are still accepted and are converted to `add`/`delete`.
