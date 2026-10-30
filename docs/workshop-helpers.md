# Workshop helpers and building placement

## Builders and creative mode

A **builder** is a player with operator level 2 or higher (`permissions.builder: op2`, the default; `op1` lowers it to level 1). The check is `CwpPermissions.isBuilder`.

- Only builders may be in creative or spectator mode. Anyone else is switched to survival at login and within a second of entering either mode, with a chat message. Spectators inside instance worlds are left alone, because raids put downed players into spectator there. Operators are builders, so they cannot lock themselves out.
- Creative has its own inventory. Every change into or out of creative (any command or way) puts away the current inventory, armor, offhand, accessories, buff food and ammo, and brings back the other mode's. XP, effects and the ender chest are shared. The first switch after this update starts the other side empty; nothing is lost, it is kept for the switch back.
- The accessory, buff food and ammo slots also show on the creative inventory tab, right of the player (accessories and buff food on top, ammo below). Their effects work the same in every mode.

## World helpers outside workshops

Builders in creative mode see and move world objects within 128 blocks with **F8** and **F9**, in every world except instance worlds:

| Category | What | Moves |
| --- | --- | --- |
| Portals | Every named location of every portal (the marker is the portal's bottom centre) | x, y, z |
| Anomalies and boss areas | Veil anomalies and other restrictions; a boss encounter's area is one too | x, y, z |
| Bonus areas | Drawn as a circle on the ground | x and z only (they have no height) |
| Boss spawns (Echelon) | Where each boss of a boss encounter waits, with a facing arrow; moving one resets that encounter | x, y, z |

In a workshop these show together with the script helpers. Moves save at once to the object's own data, like editing it in the admin panel. Other mods add kinds with `WorkshopHelpers.registerProvider(key, provider)`.

## Helpers

Inside a workshop (a pocket's editor world), designers see the things the pocket's attached scripts place but players never see. Designers are builders (see above). Everyone else, and anyone outside a workshop, receives nothing: the server sends the helper list only to designers in that workshop, about once a second, and clears it when they leave or stop being designers.

| Category | Comes from | Drawn as |
| --- | --- | --- |
| Event boxes | `eventbox` steps | Outlined box (corners inclusive, like the script) |
| Event areas | `area` steps | Cylinder: circles at ± height, radius around the center |
| Mob spawns | `mob_spawn` steps (template or mob ID) | Marker, label with mob, count and tag; arrow to the AI target when the step sets one |
| Portal spawns | `portal(action="add")` steps | Marker with preset/location; arrow to the target when set. The portal shape itself is the existing purple preview |
| Building spawns | `building` steps | Outlined box of the building's size (label shows add/delete and `replace`); the existing purple block preview stays |
| Player spawns | Workshop entry, `encounter_end(spawn=…)`, `finish_instance(spawn=…)` | Marker |
| Redstone sensors | `redstone` steps | Outlined block |
| Block replacements | `replace_blocks` steps with corners | Outlined box |
| Auras | `aura` steps (not `remove`) | Circle of the aura radius |
| Effects | `particles`, `explosion`, `sound` with a location | Marker |

Every helper is labelled with its name and the script step that defines it (`script_id #step`). Labels show within 40 blocks, shapes within 160.

### Menu

Press **F8** in a workshop (Controls → CWP admin → *Workshop helpers menu*), run `/cwp workshop`, or click **Helpers** next to Enter/Leave in the pocket's admin editor. The menu has a switch per category with its count, **Show all**, **Show none**, and **Labels** on/off. These choices are saved per player (by UUID) on the designer's own game in `config/crazy-world-progression-workshop-helpers.properties`.

The bottom row works on the categories you have switched on:

- **Previous** / **Next** teleport you to the previous or next helper, in script and step order.
- **Nearest** names the closest helper and its script step without moving you.
- **List** prints the helpers with a count per category in chat. Click an entry to put a `/tp` to it in the chat box.

The same actions are available as `/cwp workshop list|next|previous|nearest [category]` (game masters). The server checks every request: you must be a designer inside a workshop.

### Keys

All inside a workshop, all rebindable under Controls → CWP admin:

| Key | Action |
| --- | --- |
| F6 | Open this workshop's pocket editor in the admin panel (game master) |
| F7 | Open the script attached to this workshop's pocket in the admin script editor (game master). With several attached, the first opens and chat names the count; pick the others in the pocket editor. With none, chat says so |
| F8 | Open the helper menu |
| F9 | Move mode on/off |
| Arrow keys (+Shift, +Alt) | Move the selection in move mode |

Vanilla binds none of F6–F9 by default, and neither do JourneyMap or Simple Voice Chat.

### Moving helpers

Turn on **Move mode** in the menu or with **F9**. While it is on inside a workshop, clicks never break, place or use anything:

- **Left click** a shown helper to add it to the selection (selected helpers get a thick white outline). Helpers are picked through blocks; the nearest one along your crosshair wins, and a box you stand inside counts only when nothing else is in line.
- **Right click** a selected helper to deselect it. Right click at no helper to deselect everything.
- **Arrow keys** move every selected helper together: Up north (−Z), Down south (+Z), Left west (−X), Right east (+X). **Alt+Up/Down** moves up/down. Hold **Shift** to move 5 blocks. The arrow keys can be rebound under Controls → CWP admin.

The server moves them (same designer check as the helpers) and saves at once. Only the line of each moved step is rewritten in the script's `.mfs` file; other lines, indentation and comments stay as written. A box moves with both corners. Arrow targets (a mob's AI target, a portal's target) are absolute points and stay where they are. Moving the workshop entry changes the pocket's spawn. In the workshop, buildings are only previewed, so a moved building spawn moves its purple preview within about two seconds; running instances keep the script version they started with, and new instances place the building at the new spot in its placement mode. A `powered(x, y, z)` condition is not changed when a redstone sensor moves.

Turning move mode off, leaving the workshop or disconnecting clears the selection.

## Building placement mode

`building(...)` steps place a saved building in one of two modes:

- **`skip_air`** (default): only the building's own blocks are placed. Blocks already standing in the building's empty space stay. `action="delete"`, a wipe, an encounter boundary and instance cleanup remove only the building's non-air positions, so the arena around and inside it is kept. Blocks the building overwrote are not restored.
- **`replace`** (the old behaviour): the building's air is written too, clearing its whole box, and removal clears the whole box.

Choose per step with `mode="replace"` or `mode="skip_air"`, for example `building(build_id="cwp:bridge", action="add", location={…}, mode="replace")`. Steps without a mode use `buildings.placement_mode` in `config/crazy-world-progression.yml` (`skip_air` unless you set `replace`). Scripts saved before this keep loading; their YAML only gets a `mode:` key once you choose one.

Each placement in an instance remembers its mode, so it is removed the way it was placed even if the config changes. Placements saved by older versions are treated as `replace`, which is how they were placed. If a building's saved blocks are deleted while a `skip_air` copy still stands, its removal falls back to clearing the box.

## Not built yet (suggestions)

- Undo for the last building placement. Buildings are only placed by scripts in instances today; a designer "place building here" tool in the workshop would come first.
- Duplicate an event box or area from the menu (copy a step with an offset).
- Undo for moves.
- Click a helper to open its script step in the admin script editor.
- Drag box corners in the world to edit event boxes.
- Per-helper colours from the script, and filtering by script.
