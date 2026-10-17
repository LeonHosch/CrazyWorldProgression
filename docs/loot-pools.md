# Loot pools

Open the CWP admin panel and choose **Loot Pools**. Create a namespaced pool ID and display name. Pools are saved in the world, so they remain available after a restart. Edit a pool to add item, player currency, or nested pool rewards. Item fields suggest every registered item ID, including items from other mods, and show the actual item icon.

An **Independent** pool checks every reward separately. Set each reward's chance to a percentage, including decimals with up to two places. For example, a currency reward at 100%, a sword at 10%, and a nested armor pool at 25% are three independent rolls. Set Min and Max to roll a quantity for an item or currency.

A **Pick one** pool chooses exactly one entry using weights. Four armor pieces with weight 1 each have equal odds. A nested pool can refer to another nested pool, with up to 16 levels. Circular references and missing pool IDs are rejected when saving. In a Pick one pool, chance fields are ignored; its parent entry's chance determines whether it is reached from an Independent pool.

Add `loot(lootpool_id="cwp:raid_rewards")` to a pocket script to roll the pool once for each player in the instance. Set `player="entered"` to use the player list collected by an event box, or set `player` to a UUID. Loot pools can be edited without changing scripts that reference their ID. The older `reward_loot` command still rolls a vanilla loot table for existing scripts.

Every targeted player gets an independent roll. An item reward has a **Show in chat** option. When enabled, everyone sees a message naming the player and the item. The item name uses its inventory rarity color and custom-name style.

Item rewards go into the player's inventory first. Any amount that does not fit goes to their persistent **Postmaster**, which has 20 usable collection slots. Players can open it with `/postmaster` and take items into their inventory; the postmaster does not accept manual deposits. They receive a message when loot is sent there and a warning when more than 10 slots are occupied. If both inventory and postmaster lack space, the remainder drops on the floor as ordinary loot that any player can pick up.
