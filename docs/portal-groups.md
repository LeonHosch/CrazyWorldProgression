# Public and private portals

Portal access is configured per placed portal in the CWP admin portal editor or with `/cwp portals access <portal_id> public` and `/cwp portals access <portal_id> private <max_players>`. Existing portals load as public. The private limit is 1–100 players.

Public portals share one active pocket instance per portal. Players who confirm entry while it is active enter that same instance. Private portals create a new instance for each accepted group, including a solo player. Multiple private instances can run concurrently while registered pocket slots are free. The default CWP slot pool is finite; when all slots are occupied, entry fails with an availability message until cleanup frees one.

## Forming a private group

Use `/portal group` for the in-game group screen. It shows members and online players, accepts a player name for invitations, and lets an invited player accept. The same actions are available by command:

- `/portal group invite <player>`
- `/portal group accept <leader>`
- `/portal group leave`
- `/portal group list`

Invitations expire after one minute. Only the leader can invite or confirm private travel. All accepted members must be online, eligible under the portal's entry policy, in the portal's world, and within 20 blocks of the portal surface when the leader confirms. Travel does not begin if any member is too far away. The group size must fit the configured limit. The whole group enters together. The saved instance records its fixed admitted roster; unused capacity never opens to other players. A subsequent group receives a different instance. Groups and invitations are temporary server-session state; admitted instance rosters persist with pocket leases.

`once_per_instance` allows a player to enter a later private instance. `once_ever` remains a portal-wide persistent restriction. Players reconnecting inside a pocket are returned to a safe world spawn, as in the existing pocket lifecycle; a sealed private instance does not admit new arrivals after creation.

Pocket lifecycle listeners receive separate creation and expiry callbacks for every instance. `PortalService.activeInstances(server)` returns all leases, and `PortalService.expire(server, portalId)` expires all leases for that portal. Instances use unique registered dimension slots. The pocket world remains the source for entity spawning and script context.

Administrators can list the individual leases with `/cwp portals instances list`, expire every instance of a portal with `/cwp portals instances expire <portal_id>`, or expire one lease with `/cwp portals instances expire-slot <slot_dimension>`.
