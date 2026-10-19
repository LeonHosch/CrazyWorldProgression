# Public portals, dungeons and parties

Portal access is configured per placed portal in the CWP admin portal editor or with `/cwp portals access <portal_id> public` and `/cwp portals access <portal_id> dungeon <max_players>` (`private` still works as the old name). Existing portals load as public. The dungeon party limit is 1–100 players.

Public portals share one active pocket instance per portal for everyone, and the party limit is ignored. They stay active when empty. A public lifetime of zero means the instance never expires automatically; an administrator can still expire it. **Dungeons** need a positive lifetime and give each party (or a solo player) its own instance. Multiple dungeon instances can run at once while registered pocket slots are free. When all slots are occupied, entry fails with an availability message until cleanup frees one.

## Parties

Open the party screen with **O** (rebindable under Controls → Parties), the **Party** button in the Esc menu, or `/party`. The left side shows your party; the right side is the **party browser**.

- **Creating:** give the party a name, pick its visibility and click **Create party**. A party has up to 5 members by default; the leader can set 1–10.
- **Visibility:**
  - **Public** parties appear in the browser and anyone can **Join** until the party is full.
  - **Semi-private** parties appear in the browser, but **Request** sends a join request that the leader accepts or denies (on the party screen, or with the clickable chat message).
  - **Private** parties never appear in the browser. The leader invites players by name (with name completion) and they accept on the party screen or with the clickable chat message.
- **Leader tools:** rename, change visibility and size, accept or deny requests, invite, make someone else leader, kick, and disband.
- **Leaving:** **Leave party** (or `/party leave`). If the leader leaves, the next member becomes leader; the last member leaving ends the party.
- Parties are saved with the world, including offline members.
- Commands: `/party create [name]`, `/party invite <player>`, `/party accept <leader>`, `/party decline <leader>`, `/party join <leader>` (joins or requests), `/party requests accept|deny <player>`, `/party kick <player>`, `/party promote <player>`, `/party leave`, `/party disband`. `/portal group …` still works and uses the same parties.

## Entering a dungeon

Every party has a hidden ID. A dungeon instance belongs to the party that opened it, so entering the same portal again always leads to your party's own instance. Only the party leader can open a new instance, and only when every online party member is within 20 blocks of the portal; otherwise the party is told who is missing (and how far away). Offline members do not block the start and can follow later by walking into the portal while the dungeon has room. While the instance is being built the party waits at the portal and is then brought in together. A player who leaves the party loses access to its instance (and is taken out of it, unless leaving is blocked during a strict encounter). If they then enter the same portal, solo or with another party, a new instance is created for them. Without a party, a player has a personal instance in the same way.

`once_per_instance` allows a player to enter a later instance. `once_ever` remains a portal-wide persistent restriction, while re-entry into your party's still-active instance remains allowed.

## Raid attempts

Pocket portals keep each player's inventory on death by default. The portal editor can turn **Keep inventory on death** off for a pocket destination. That portal then shows a prominent red item-loss warning and requires a second entry confirmation. Every member of a private group must confirm before the group enters. This setting applies to the instance opened by that portal; ordinary worlds retain their own gamerule.

Every pocket portal has an **instance mode** that decides what happens when a player dies inside:

| Mode | Death | Revive |
| --- | --- | --- |
| No Revive | Sent out of the instance at once (items drop where they died unless the portal keeps inventory) | — |
| Player Revive | Down as a spectator at a marker | Another player revives them within 1 minute, otherwise they are sent out |
| Unlimited Revive | Down as a spectator at a marker | Other players can revive; after 15 seconds they can revive themselves by flying into their revive point |
| Dungeon (private only) | Down | Encounters, wipes and checkpoints; unlimited teammate revives. During an encounter, self-revive after 60 seconds when keeping inventory, none without |
| Raid (private only) | Down | Like Dungeon, but during encounters each player has one revive and there is no self-revive |

Reviving takes 2 seconds: right-click the marker and stay within 5 blocks. Self-revive anywhere it is allowed needs 15 seconds down. Old portals keep their behaviour: raids become Raid, other private portals Dungeon, public portals No Revive. Entering a private portal sets the party's size limit to the portal's party limit. For a raid, choose **Raid** and set the party limit to the team size (for example, six). A living teammate can interact with a marker to revive; spectators do not count in script event boxes or use encounter portals.

When every online participant in the instance is down, the attempt wipes. Players return to the pocket rally spawn, revive charges and `once_per_attempt` portal records clear, pocket scripts restart, script spawned mobs are removed, and script placed portal locations reset. The instance remains available for the retry. Add a **Raid complete** step to the encounter script to reset charges and entry records after a successful attempt. A portal with **Coordinates** and **Once per attempt** makes a prompt free lobby entrance that each player can use once until a wipe or completion. Raid instances still obey their lease lifetime. Empty private raid instances expire once no player or pending respawn remains inside.

Raid players reconnecting inside their active instance stay there. A disconnected downed player returns as a spectator until revived or the team wipes. If the instance has expired, they return to the main world.

Pocket lifecycle listeners receive separate creation and expiry callbacks for every instance. `PortalService.activeInstances(server)` returns all leases, and `PortalService.expire(server, portalId)` expires all leases for that portal. Instances use unique registered dimension slots. The pocket world remains the source for entity spawning and script context.

Administrators can list the individual leases with `/cwp portals instances list`, expire every instance of a portal with `/cwp portals instances expire <portal_id>`, or expire one lease with `/cwp portals instances expire-slot <slot_dimension>`.

## Leaving, rejoining and inviting

- `/leave` takes you out of the instance you are in, back to where you entered it.
- `/partyleave` (party leader only) takes everyone out and ends the instance. 
- During an encounter of a raid whose portal does not keep inventory, `/leave` and `/partyleave` are unavailable until the encounter ends or wipes.
- Party members can come back to their dungeon while it is running: walk into its portal again, or log back in if you logged out inside. An empty dungeon waits 5 minutes for its party before it is released, unless its scripts have completely finished: then it closes as soon as the last player leaves. A raid also stays open while any party member is dead or waiting to respawn.
- To bring someone new into a running dungeon, add them to your party. They join by walking into the dungeon's entrance portal while a slot is free.

## Raid checkpoints

Every completed encounter (`encounter_end()` in the raid script) gives each party member inside a checkpoint for that raid portal. A checkpoint lasts 24 hours from the last completed encounter and is saved with the world.

A new instance of the raid starts at the **party leader's** checkpoint. For example, after clearing the first encounter, the next run starts at the second one, and a "Resuming at encounter 2" banner appears. The script continues directly after the completed `encounter_end(...)`, and players are moved to that step's `spawn`. Loot and achievements right after it are not given again. A wipe restarts at the checkpoint as well.

The raid's entry screen shows your checkpoint and how long it lasts. Its **Reset** button (click twice) clears it, so your next run as leader starts at the first encounter.

