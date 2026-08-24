# Crazy World Progression

Crazy World Progression is a Fabric framework for server-authoritative currencies and YAML skill trees. It intentionally defines no gameplay currency, ruler role, world border, or concrete tree of its own. Dependent mods register those pieces during initialization.

## Features

- Any number of registered player-owned or global currencies.
- Generic persistent wallets with non-negative `long` balances.
- Generated balance and administration commands for every currency.
- YAML trees supplied by any dependent mod.
- Personal and global unlock persistence, prerequisites, exclusive branches, and atomic server-thread purchases.
- Dynamic client snapshots and currency badges; the UI has no fixed currency slots.
- Per-tree purchase policies for roles, factions, permissions, or other application rules.
- Extensible stat-expression handlers.
- Compatibility migration for saves created before the Echelon systems were separated.

## Registering currencies

Register definitions from a dependent mod's `ModInitializer` before the server starts:

```java
Identifier renown = Identifier.fromNamespaceAndPath("example", "renown");

CurrencyRegistry.register(new CurrencyDefinition(
        renown,
        "Renown",
        "RN",
        CurrencyDefinition.CurrencyScope.PLAYER,
        Identifier.fromNamespaceAndPath("example", "currencies/renown-32x32.png"),
        0, 0, 32, 32,
        ChatFormatting.AQUA,
        List.of("renown", "rn")
));
```

Use `CurrencyService.getBalance`, `credit`, `debit`, and `setBalance` for rewards and integrations. Global definitions use one server-wide wallet; player definitions use the supplied UUID.

Every registered definition receives its configured commands, including balance display and operator-only `give`, `take`, and `set` operations. `/balance` shows all registered global currencies plus all player currencies belonging to the selected player.

## Registering skill trees

Place `.yml` files in a resource directory owned by your mod, then register that source:

```java
SkillTreeRegistry.registerSource("example", "example-mod", "skilltrees");
```

The namespace is used for tree IDs and for unqualified currency IDs in that source. A file named `adventurer.yml` becomes `example:adventurer`.

```yaml
skilltree: personal
name: Adventurer
icon: minecraft:compass

skills:
  - name: Trailblazer
    description: Move 5% faster.
    previous: none
    following: 0
    costs:
      renown: 25
      another-mod:tokens: 2
    stats:
      - addMovementSpeed(5)
```

`costs` is an open mapping. Keys may be local paths such as `renown` or complete identifiers such as `another-mod:tokens`; there is no fixed list or maximum. Every referenced currency must be registered before the server loads trees.

Built-in stat expressions are `addHealth(number)`, `addMiningSpeed(number)`, and `addMovementSpeed(number)`. Add more with `SkillStatRegistry.register`.

## Purchase policies

Trees are purchasable by default. A dependent mod can attach application-specific authorization:

```java
SkillTreeRegistry.registerPurchasePolicy(treeId, (player, tree) ->
        hasRequiredRole(player)
                ? SkillTreeRegistry.PurchaseDecision.ALLOWED
                : SkillTreeRegistry.PurchaseDecision.denied("You need the required role."));
```

The policy is checked while creating the client snapshot and again during the authoritative purchase. The denial reason is displayed by the generic screen.

## Build

Requires Java 25, Minecraft 26.2, Fabric Loader 0.19.3, and Fabric API.

```powershell
.\gradlew.bat build
```

EchelonCore is the reference dependent mod and contains the currencies, king policy, veil, baseline player modifiers, and concrete trees that previously lived here.
