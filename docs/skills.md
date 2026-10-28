# Skills

Six skills level from 0 to 10 by doing things: **cooking, fishing, farming, mining, dungeoning and fighting**. They are separate from the stat levels (`addCookingLevel`, `addFishingLevel`, `addFarmingLevel`) that gate recipes, catches and crops; those still come from skill trees and sets. `/skills` shows each skill's level and XP. Gaining XP shows "+5 Mining XP (120/150)" on the action bar, and a level-up gets a chat message and a sound.

## Levels

Total XP needed per level ([Skills](../src/main/java/ekuzo/crazyworldprogression/skills/Skills.java)):

| Level | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Total XP | 50 | 150 | 350 | 700 | 1,200 | 2,000 | 3,200 | 5,000 | 7,500 | 11,000 |

## XP sources

| Skill | How | XP |
| --- | --- | --- |
| Fishing | Catching items from a fishing loot pool | The entry's **XP per item** × the amount caught |
| Farming | Breaking a fully grown crop (or a melon/pumpkin grown from a stem) | By the crop's farming level: 0 → 1, 1 → 3, 2 → 8, 3 → 15, then level × (level + 2). Normal crops are level 0. |
| Cooking | Cooking a dish at a cauldron or smoker | Same table, by the recipe's cooking level |
| Mining | Breaking an ore without Silk Touch | Coal and copper 1, iron 5, redstone 5, lapis 8, gold 10, nether gold 3, nether quartz 2, emerald 15, diamond 20, ancient debris 25 (deepslate variants the same) |
| Dungeoning | The `skill_xp(amount=…)` script step | Whatever the script gives |
| Fighting | Killing a mob | MobForge mobs: their template's **Fighting XP**. Vanilla mobs (and templates with -1): zombie, skeleton, spider and similar 1, phantom/piglin/vex 2, creeper, witch, blaze, pillager, vindicator, guardian, shulker, hoglin 3, enderman, ghast, wither skeleton, piglin brute, breeze 5, evoker 8, ravager 15, elder guardian and warden 50, wither 100, ender dragon 200, other hostile mobs 1, passive mobs 0 |

Ores and melons/pumpkins a player placed give no XP when broken again, so silk-touch or place-and-break loops do not work. Creative mode gives no block XP. MobForge mobs drop no vanilla XP orbs; vanilla mobs still do.

Code: [SkillXp](../src/main/java/ekuzo/crazyworldprogression/skills/SkillXp.java).
