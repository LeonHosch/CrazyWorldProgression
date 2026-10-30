# Modrinth release kit

Everything needed to publish CWP on Modrinth. Section 1 is the project page body (paste it into the description editor), section 2 the version changelog, section 3 the fields to fill in.

---

## 1. Project page

**Summary (the one line under the title, max 120 characters):**

> A modular RPG and MMO framework: skill trees, item quality, instanced dungeons, parties, clans, custom mobs and more.

**Description:**

```markdown
# Crazy World Progression

**Crazy World Progression (CWP)** is a framework for building RPG and MMO servers and content mods on Fabric. It brings the systems an RPG server needs (progression, crafting quality, instanced dungeons, social features, custom mobs), so your mod or server can focus on its own world and story.

Every part can be switched on or off in one config file. If you only need skill trees, turn everything else off and CWP adds nothing else to your game.

> CWP is a framework. It ships no currencies, skill trees, dungeons or quests of its own. Those come from your content mod, your config files or the in-game admin panel.

## What's inside

**Progression**
- Skill trees written in YAML, with an in-game skill tree screen and a character sheet
- Skill levels gained by doing (mining, fishing, cooking, farming, craftsmanship, ...) with proficiency ranks and passives
- Equipment sets, protection stats and set effects

**Crafting and items**
- Item quality from Miserable to Perfect, rolled by the crafter's skill
- Craftsmanship, repairs, reforging and research
- Fletching with special arrows (bleeding, stun, explosive, grappling, ...) and ammo slots
- Farming with produce quality and crossbreeding; cooking dishes whose quality depends on their ingredients
- Fishing loot pools with biome, weather and time conditions, plus a fishing journal

**World and instances**
- Portals into instanced copies of builds you make in your own workshop worlds: dungeons per party, shared public instances and raids with checkpoints
- Encounter scripting and custom mobs with their own AI and abilities (MobForge)
- Random world events, an advanced world boundary, bonus areas and area restrictions
- Loot pools for mob drops, given straight to the killer

**Social**
- Parties (with Simple Voice Chat groups if it is installed)
- Friends, clans with ranks, clan chat and clan missions
- Chest locks (public, private, friends, clan)

**Storage**
- Chest qualities from 18 to 54 slots, and scrolling chests when two large chests merge
- Chest filters, quicksort into nearby chests, sort buttons and item locks

**For server owners**
- One admin panel (`/cwp admin`) to edit pockets, portals, mobs, scripts, loot pools, recipes and more in game
- Currencies with generated commands, titles, a postmaster for rewards
- Performance tools: a per-tick time budget for block work and `/cwp performance`

## Modular

Everything is configured in `config/crazy-world-progression.yml`:

- turn any of the 25 parts on or off
- choose how many instance and workshop worlds exist
- tune the per-tick time budget for block work

A part that is off adds no events, commands, screens or game changes. Its items and saved data stay known, so a world that used it keeps loading.

## For developers

CWP has APIs for currencies, skill tree stats, portals and instance lifecycles, MobForge mobs and abilities, admin panel pages, collectibles, map data and window styles (`PanelStyle`), so your content mod can give CWP's windows its own look. Declare the parts your mod needs in your `fabric.mod.json` and CWP keeps them on.

Documentation, examples and the source are on [GitHub](https://github.com/LeonHosch/CrazyWorldProgression). [EchelonCore](https://github.com/LeonHosch/EchelonCore) is the reference content mod built on CWP.

## Requirements

- Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API, Java 25
- Install on both the server and the client
- Optional: Simple Voice Chat (party voice groups), JourneyMap (map integration)

CWP is in beta (0.x). Expect changes before 1.0.
```

---

## 2. Version 0.6.0 changelog

```markdown
- Workshop helpers: inside a workshop, builders see event boxes, areas, mob, portal, building and player spawns, sensors and more, with labels and facing arrows; a menu (F8) switches categories, teleports between helpers and lists them
- Move mode (F9): select helpers with left click and move them with the arrow keys (Shift: 5 blocks, Alt: up/down); scripts keep their comments
- F6/F7 open the workshop's pocket editor and script
- In creative, builders can also show and move portals, anomalies and bonus areas outside workshops (never in instances)
- Builders (operator level 2+, configurable) are the only players allowed in creative or spectator; creative has its own inventory
- Accessory, buff food and ammo slots in the creative inventory
- Buildings placed by scripts skip air by default and remove only their own blocks; `mode="replace"` or `buildings.placement_mode: replace` keeps the old behaviour
```

## Version 0.5.0 changelog

```markdown
First public beta.

- One config file, `config/crazy-world-progression.yml`: switch each of the 25 parts on or off, choose how many instance and workshop worlds exist, and tune the tick time budget (the old performance properties file is imported automatically)
- Mods can keep the parts they need switched on (`requires_modules` in fabric.mod.json)
- Instance and workshop worlds are created at startup from the config
- The client settings screen is now "CWP Settings"; content mods can give it their own name
- Packets are only sent to games that listen for them, so a client without a part (or without CWP) is never disconnected
```

---

## 3. Upload checklist

| Field | Value |
|---|---|
| Project type | Mod |
| Name | Crazy World Progression |
| URL slug | `crazy-world-progression` |
| Summary | see section 1 |
| Categories | Library, Adventure, Game Mechanics (optionally Management) |
| Environment | Client and server: **required** on both |
| License | AGPL-3.0-only (the repository's `LICENSE`) |
| Source code | https://github.com/LeonHosch/CrazyWorldProgression |
| Issue tracker | https://github.com/LeonHosch/CrazyWorldProgression/issues |
| Icon | `src/main/resources/assets/crazy-world-progression/icon.png` |
| Gallery | screenshots of the skill tree, admin panel, party and clan windows, a scrolling chest |

**Version upload**

| Field | Value |
|---|---|
| File | `crazy-world-progression-0.6.0.jar` (not the `-sources` jar) |
| Version number | `0.6.0` |
| Version title | `0.6.0` |
| Release channel | **Beta** |
| Loaders | Fabric |
| Game versions | 26.2 |
| Dependencies | Fabric API: required · Simple Voice Chat: optional · JourneyMap: optional |
| Changelog | see section 2 |

Moderation usually looks at: a clear description of what the mod does, working source and issue links, a license that matches the repository, and a jar without bundled third-party mods. The only bundled libraries are SnakeYAML and SnakeYAML Engine (Apache 2.0, jar-in-jar), which is allowed.

When EchelonCore is published later, add CWP as a **required** dependency of it.
