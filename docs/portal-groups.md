# Public and private portals

Portal access is configured per placed portal in the CWP admin portal editor or with `/cwp portals access <portal_id> public` and `/cwp portals access <portal_id> private <max_players>`. Existing portals load as public. The private limit is 1–100 players.

Public portals share one active pocket instance per portal regardless of group membership, and the private group limit is ignored. They stay active when empty. A public lifetime of zero means the instance never expires automatically; an administrator can still expire it. Private portals require a positive lifetime and create a new instance for each accepted group, including a solo player. A previous visitor who returns while their lease is active re-enters that same instance without creating another. A private instance begins cleanup when its last player leaves. Multiple private instances can run concurrently while registered pocket slots are free. The default CWP slot pool is finite; when all slots are occupied, entry fails with an availability message until cleanup frees one.

## Forming a private group

Use `/portal group` for the in-game group screen. It shows members and online players, accepts a player name for invitations, and lets an invited player accept. The same actions are available by command:

- `/portal group invite <player>`
- `/portal group accept <leader>`
- `/portal group leave`
- `/portal group list`

Invitations expire after one minute. Only the leader can invite or confirm private travel. All accepted members must be online, eligible under the portal's entry policy, in the portal's world, and within 20 blocks of the portal surface when the leader confirms. Travel does not begin if any member is too far away. The group size must fit the configured limit. The whole group enters together. The saved instance records its fixed admitted roster; unused capacity never opens to other players. A subsequent group receives a different instance. Groups and invitations are temporary server-session state; admitted instance rosters persist with pocket leases.

`once_per_instance` allows a player to enter a later private instance. `once_ever` remains a portal-wide persistent restriction, while re-entry into a still-active lease remains allowed. Players reconnecting inside a pocket are returned to a safe world spawn, as in the existing pocket lifecycle; admitted players can re-enter while their instance still exists, but its fixed roster does not admit new arrivals after creation.

## Raid attempts

Pocket portals keep each player's inventory on death by default. The portal editor can turn **Keep inventory on death** off for a pocket destination. That portal then shows a prominent red item-loss warning and requires a second entry confirmation. Every member of a private group must confirm before the group enters. This setting applies to the instance opened by that portal; ordinary worlds retain their own gamerule.

Enable **Raid mode** on a private pocket portal and set its group limit to the team size (for example, six). Raid members who die respawn as spectators at the pocket rally spawn. A death marker appears at their death location. A living teammate can interact with the marker to revive them; each teammate has one revive charge per attempt. Spectators do not count in script event boxes or use encounter portals.

When every online participant in the instance is down, the attempt wipes. Players return to the pocket rally spawn, revive charges and `once_per_attempt` portal records clear, pocket scripts restart, script spawned mobs are removed, and script placed portal locations reset. The instance remains available for the retry. Add a **Raid complete** step to the encounter script to reset charges and entry records after a successful attempt. A portal with **Coordinates** and **Once per attempt** makes a prompt free lobby entrance that each player can use once until a wipe or completion. Raid instances still obey their lease lifetime. Empty private raid instances expire once no player or pending respawn remains inside.

Raid players reconnecting inside their active instance stay there. A disconnected downed player returns as a spectator until revived or the team wipes. If the instance has expired, they return to the main world.

Pocket lifecycle listeners receive separate creation and expiry callbacks for every instance. `PortalService.activeInstances(server)` returns all leases, and `PortalService.expire(server, portalId)` expires all leases for that portal. Instances use unique registered dimension slots. The pocket world remains the source for entity spawning and script context.

Administrators can list the individual leases with `/cwp portals instances list`, expire every instance of a portal with `/cwp portals instances expire <portal_id>`, or expire one lease with `/cwp portals instances expire-slot <slot_dimension>`.

## Leaving, rejoining and inviting

- `/leave` takes you out of the instance you are in, back to where you entered it.
- `/partyleave` (party leader only) takes everyone out and ends the instance. The party leader is the group leader at the time of travel.
- Everyone in the party that started a private instance can come back while it is running: walk into its portal again, or log back in if you logged out inside. An empty private instance waits 5 minutes for its party before it is released. A raid also stays open while any party member is dead or waiting to respawn.
- Inside any running private instance (raid or not), `/party invite <player>` invites someone into it when a slot is free (the portal's max players). The invite lasts 5 minutes. To join, the invited player must go to that instance's entrance portal: walking into it joins, and the **[Join]** link (`/party accept <player>`) only works within 8 blocks of it. Outside an instance, `/party invite` and `/party accept` form a group before travel, the same as `/portal group invite` and `/portal group accept`.

## Raid checkpoints

Every completed encounter (`encounter_end()` in the raid script) gives each party member inside a checkpoint for that raid portal. A checkpoint lasts 24 hours from the last completed encounter and is saved with the world.

A new instance of the raid starts at the **party leader's** checkpoint. For example, after clearing the first encounter, the next run starts at the second one, and a "Resuming at encounter 2" banner appears. The script continues directly after the completed `encounter_end(...)`, and players are moved to that step's `spawn`. Loot and achievements right after it are not given again. A wipe restarts at the checkpoint as well.

The raid's entry screen shows your checkpoint and how long it lasts. Its **Reset** button (click twice) clears it, so your next run as leader starts at the first encounter.

