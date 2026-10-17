# MobForge developer API

MobForge is an internal CWP subsystem in the CWP JAR. CWP contains frameworks, never game-specific mobs. CWP must never depend on a content mod or import its classes; content mods depend on CWP's public API. The API is in `ekuzo.crazyworldprogression.api.mobforge`; the YAML, command, editor, and AI implementation is in `ekuzo.crazyworldprogression.mobforge`.

## Registration and lifecycle

Register abilities and behaviors, then immutable mob definitions during a dependent mod's Fabric `ModInitializer.onInitialize()`. Declare `crazy-world-progression` in that mod's `fabric.mod.json` dependencies. Registrations freeze at `SERVER_STARTING`, after Fabric mod initializers run. Duplicates and late registrations fail. Missing ability or behavior references fail before the server opens. Definition IDs must use the content mod's namespace; `minecraft` and `crazy-world-progression` are reserved. The same namespaced ID becomes available to scripts automatically.

```java
public final class ExampleMobContent {
    public static void register() {
        Identifier guardian = Identifier.fromNamespaceAndPath("example", "guardian");
        Identifier pulse = Identifier.fromNamespaceAndPath("example", "pulse");
        Identifier guard = Identifier.fromNamespaceAndPath("example", "guard_position");

        MobForgeApi.abilities().register(pulse, () -> new MobAbility() {
            @Override public long cooldownTicks() { return 100; }
            @Override public void execute(MobAbilityContext context) {
                // Content-owned effects use context.world(), caster(), target(), position().
            }
        });
        MobForgeApi.behaviors().register(guard, (world, handle, mob) -> {
            // Runs every five ticks. Vanilla goals remain available.
        });
        MobForgeApi.mobs().register(MobDefinition.builder(guardian, EntityTypes.ZOMBIE)
                .displayName("Guardian")
                .attribute(Attributes.MAX_HEALTH, 100)
                .tag("boss")
                .ability(pulse)
                .behavior(guard)
                .build());
    }
}
```

The entity type can be vanilla or registered by a content mod, including a fully custom implementation. The definition is a layer over that type. Definition tags are MobForge tags, separate from Minecraft registry tags. `mobs().findByTag("boss")` returns an immutable snapshot. Ability factories create an instance per cast; `canCast` and `cooldownTicks` control execution. Behavior logic can read the handle's phase and choose when to cast. MobForge does not replace vanilla AI goals for these registered definitions. The older YAML template AI modes retain their existing behavior.

## Spawning and runtime

Use `MobForgeApi.spawn(id, context)` or `EntitySpawnService.spawn(id, context)`. Resolution order is: registered MobForge definition, existing YAML template under the `crazy-world-progression` namespace, then Minecraft `EntityType`. Content definitions cannot use CWP's template namespace. A definition may intentionally share its ID with its own Minecraft `EntityType`; the definition takes precedence. Unknown IDs and rejected entity creation produce errors naming the ID and world.

```java
SpawnContext context = new SpawnContext(world, position, 0, 0,
        encounterId, pocketInstanceId, scriptExecutionId, Map.of());
ScriptEntityHandle entity = MobForgeApi.spawn(guardian, context);
```

The caller supplies the actual `ServerLevel`; MobForge never substitutes the Overworld. `MobForgeHandle` adds definition ID, tags, phase, ability casting, and an immutable `MobRuntime` snapshot. It does not expose mutable internal state. Definition ID, phase, encounter ID, pocket instance ID, script execution ID, and ability cooldown ticks are stored as entity tags and survive normal entity saves. Static definitions are registered again at mod initialization; they are not serialized per world. Transient spawn metadata stays in `SpawnContext` and is delivered to spawn listeners, not persisted. There is no global map of all spawned mobs to scan or clean up.

`MobForgeEvents.register(listener)` provides spawn, damage, death, ability start/completion, and phase change callbacks. Listeners run on the server thread, and a failing listener is logged without stopping other listeners. Behavior callbacks run every five ticks per registered `Mob`; a behavior that throws is disabled for that entity and logged once.

## YAML scripts

The existing `config/mobforge/scripts/` runner remains the CWP script engine. Its legacy `spawn` and `sleep` steps still work. New steps use namespaced IDs and explicit, limited capabilities:

```yaml
id: guardian_wave
steps:
  - spawn_entity:
      id: example:guardian
      at: arena_center
      save_as: guardian
  - wait:
      condition: '${guardian.health_percent <= 0.5}'
      timeout: 5m
  - mob_cast:
      mob: '${guardian}'
      ability: example:pulse
  - set_phase:
      mob: '${guardian}'
      phase: enraged
  - wait:
      condition: '${guardian.phase == enraged}'
  - wait:
      condition: '${guardian.dead}'
  - trigger:
      event: example:guardian_defeated
```

Conditions support `dead`, `health_percent` comparisons, and phase equality/inequality. They do not evaluate Java code, load classes, run commands, or read files. `spawn_entity` uses the same registry as the public spawn API, so no second script registration is needed. Call `MobForge.SCRIPT_EXECUTOR.run(script, world, anchors, encounterId, pocketInstanceId)` with named `Map<String, Vec3>` anchors; `/mobforge script run` supplies `origin` at the command source. `ScriptSignals.register(listener)` receives named triggers and per-run failures. A run that hits an invalid reference or an unloaded entity is canceled and logged, leaving the server running. Death is tracked separately from chunk unload for handles held by active scripts.

`wait` (formerly `wait_until`) has a 24-hour default timeout and accepts a shorter `timeout` duration. Completed, failed, or canceled runs discard their handles.

The CWP admin panel has a MobForge tab for YAML mob templates and scripts. Its script editor supports Spawn, Sleep, `spawn_entity`, `wait`, `mob_cast`, `set_phase`, and `trigger`. Entity and ability pickers use identifiers sent by the server. Server validation reports save errors in the editor and keeps rejected changes unsaved. Java-registered MobDefinitions, ability factories, and behaviors appear as read-only registrations; their implementations remain owned by the registering content mod.

## Pocket dimensions and encounters

MobForge owns no Pocket Dimension or encounter lifecycle. `PortalRegistry.InstanceListener` receives `MinecraftServer` and `ActiveInstance`. An instance exposes `world(server)` and a stable `instanceId()` for the lifetime of its lease. Content or encounter code can use these to create a `SpawnContext` or start a script with the instance world and ID. Expiring a pocket lease cancels its active scripts before the world is cleared. The same API works in Overworld, Nether, End, static custom worlds, and pocket instances. The dependency direction is Pocket/encounter integration to MobForge API; no MobForge dependency on portal internals exists.

World and entity mutations must occur on the server thread. Spawn contexts reject non-finite coordinates. Script operations are deliberately restricted to handle capabilities. The runner keeps only active script handles and clears them when runs end or the server stops.
