# CWP architecture rules

1. CWP contains frameworks, not game-specific content.
2. CWP never imports content-mod classes.
3. Scripts address registered mobs with namespaced IDs.
4. MobForge works in any caller-supplied `ServerLevel`.
5. Pocket Dimension worlds are normal valid MobForge worlds.
6. Content mods register mobs through the public CWP API.
7. Registering a mob makes its ID available to the script resolver automatically.
8. Abilities and behaviors are reusable registered components.
9. Immutable definitions and per-entity runtime state remain separate.
10. Integrations depend on public APIs without circular module dependencies.

CWP must never depend on a content mod. Content mods depend on CWP's public API. See [MobForge API](mobforge-api.md) for registration, spawning, scripts, events, persistence, and pocket integration.
