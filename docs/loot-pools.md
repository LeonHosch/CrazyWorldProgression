# Loot pools

Open the CWP admin panel and choose **Loot Pools**. The tab works like Pockets, Portals and Buildings: click **Create**, enter a namespaced pool ID such as `cwp:raid_rewards` and a display name, and the pool opens in its editor. Pools are saved in the world, so they remain available after a restart.

In the editor, add entries from the **Add** section at the bottom:

- **Item**: type part of an item's name or ID (for example `sword` or `diamond`). A dropdown lists matching items, including items from other mods, with their icons. Click one, or use the arrow keys and Enter. Set a chance and an amount, either a number like `2` or a range like `1-3`.
- **Nested pool**: set its chance and click **Add nested pool**. The editor opens the new nested pool so you can fill it. Nested pools can contain more nested pools, up to 16 levels deep.
- **Currency**: credits a player currency. This row only appears when a player-scoped currency exists.

Each entry row has **Edit** (chance, amount, chat announcement), **↑ / ↓** to reorder, and **Remove**. Nested pool rows also have **Open**. A nested pool's **Back** button returns to the pool that contains it.

How a pool rolls:

- **A top-level pool rolls every entry on its own.** A 100% entry always drops, and a 25% entry drops in about 1 of 4 rolls. Entries do not affect each other. For example, a currency reward at 100%, a sword at 10% and a nested armor pool at 25% are three separate rolls.
- **A nested pool first rolls its own chance in its parent. If that roll succeeds, it picks exactly one of its entries.** Inside a nested pool, each entry's percentage is its share of the pick. For example, four armor pieces at 25% each are equally likely. If the shares do not add up to 100%, they are scaled. The editor shows the total and each entry's real odds. A nested entry that is itself a nested pool continues the same way.

Chances accept up to two decimal places, such as `2.5`. Removing a nested pool entry also deletes that nested pool, unless another pool or a script still uses it. Deleting a top-level pool deletes the nested pools that only it uses. Nested pools are not listed on the main Loot Pools page; open them from their parent.

Pools saved before this change keep their odds. Old **Pick one** weights are converted to shares when the world loads.

Add `loot(lootpool_id="cwp:raid_rewards")` to a pocket script to roll the pool once for each player in the instance. Set `player="entered"` to use the player list collected by an event box, or set `player` to a UUID. Loot pools can be edited without changing scripts that reference their ID. The older `reward_loot` command still rolls a vanilla loot table for existing scripts.

Every targeted player gets an independent roll. An item reward has a **Show in chat** option. When enabled, everyone sees a message naming the player and the item. The item name uses its inventory rarity color and custom-name style.

Item rewards go into the player's inventory first. Any amount that does not fit goes to their persistent **Postmaster**, which has 20 usable collection slots. Players can open it with `/postmaster` and take items into their inventory; the postmaster does not accept manual deposits. They receive a message when loot is sent there and a warning when more than 10 slots are occupied. If both inventory and postmaster lack space, the remainder drops on the floor as ordinary loot that any player can pick up.
