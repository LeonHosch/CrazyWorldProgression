# Loot pools

Open the CWP admin panel and choose **Loot Pools**. The tab works like Pockets, Portals and Buildings: click **Create**, enter a namespaced pool ID such as `cwp:raid_rewards` and a display name, and the pool opens in its editor. Pools are saved in the world, so they remain available after a restart.

In the editor, add entries from the **Add** section at the bottom:

- **Item**: type part of an item's name or ID (for example `sword` or `diamond`). A dropdown lists matching items, including items from other mods, with their icons. Click one, or use the arrow keys and Enter. Set a chance and an amount, either a number like `2` or a range like `1-3`. Optional XP per item and level values are available below the item fields.
- **Nested pool**: set its chance and click **Add nested pool**. The editor opens the new nested pool so you can fill it. Nested pools can contain more nested pools, up to 16 levels deep.
- **Currency**: credits a player currency. This row only appears when a player-scoped currency exists.

Each entry row has **Edit** (chance, amount, optional stat values, chat announcement), **↑ / ↓** to reorder, and **Remove**. Nested pool rows also have **Open**. A nested pool's **Back** button returns to the pool that contains it.

An item's optional **Level** is a minimum level for the stat associated with the roll. A blank value means no level requirement. Fishing rolls check it against the player's fishing level (the `addFishingLevel` stat from skill trees and sets): entries above it are left out, as if they were not in the pool, so a pick-one pool shares its odds among the rest. Other rolls do not check levels yet.

The **Fishing Journal** (book + any fish) lists every fishing biome or pocket and its fish. Caught fish show as themselves, uncaught ones as a grey fish, and fish above the player's fishing level as a black fish.

How a pool rolls:

- **A top-level pool rolls every entry on its own.** A 100% entry always drops, and a 25% entry drops in about 1 of 4 rolls. Entries do not affect each other. For example, a currency reward at 100%, a sword at 10% and a nested armor pool at 25% are three separate rolls.
- **A nested pool first rolls its own chance in its parent. If that roll succeeds, it picks exactly one of its entries.** Inside a nested pool, each entry's percentage is its share of the pick. For example, four armor pieces at 25% each are equally likely. If the shares do not add up to 100%, they are scaled. The editor shows the total and each entry's real odds. A nested entry that is itself a nested pool continues the same way.

Chances accept up to two decimal places, such as `2.5`. Removing a nested pool entry also deletes that nested pool, unless another pool or a script still uses it. Deleting a top-level pool deletes the nested pools that only it uses. Nested pools are not listed on the main Loot Pools page; open them from their parent.

Pools saved before this change keep their odds. Old **Pick one** weights are converted to shares when the world loads.

Add `loot(lootpool_id="cwp:raid_rewards")` to a pocket script to roll the pool once for each player in the instance. Set `player="entered"` to use the player list collected by an event box, or set `player` to a UUID. Loot pools can be edited without changing scripts that reference their ID. The older `reward_loot` command still rolls a vanilla loot table for existing scripts.

Every targeted player gets an independent roll. Each item entry has a **Chat: off / Chat: on** toggle in the pool table, and the same setting is on its Edit page. It is off by default. When it is on and a player rolls that item, everyone sees "Player just got [Item]" in chat, or "Player just got 3x [Item]" for more than one. Hovering the item shows its tooltip, and the name uses its rarity color and custom name.

Item rewards go into the player's inventory first, but only as much as really fits. This also applies in creative mode, where Minecraft would otherwise delete items that do not fit. Whatever does not fit goes to the player's persistent **Postmaster**, which has 20 usable collection slots. The player gets one chat message per roll with a clickable **[Open postmaster]** link. Players can also open it with `/postmaster` and take items into their inventory; the postmaster does not accept manual deposits. After a roll, players with a postmaster more than half full (over 10 of 20 slots) get a warning to clear it. If the postmaster is full too, the remainder drops on the ground at the player's feet, where any player can pick it up, and the player is told so. Loot is only rolled for players who are online in the pocket instance, so there is no offline delivery.
