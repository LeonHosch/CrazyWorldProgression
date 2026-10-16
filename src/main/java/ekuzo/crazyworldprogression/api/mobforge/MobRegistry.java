package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class MobRegistry {
    private final Map<Identifier, MobDefinition> entries = new LinkedHashMap<>();
    private final Map<String, Set<MobDefinition>> byTag = new LinkedHashMap<>();
    private Map<Identifier, MobDefinition> frozenEntries = Map.of();
    private Map<String, Set<MobDefinition>> frozenTags = Map.of();
    private boolean frozen;

    public synchronized void register(MobDefinition definition) {
        if (frozen) throw new IllegalStateException("MobForge mob registration closed; register during Fabric mod initialization");
        Objects.requireNonNull(definition, "mob definition");
        if (entries.putIfAbsent(definition.id(), definition) != null)
            throw new IllegalArgumentException("Duplicate MobForge mob: " + definition.id());
        for (String tag : definition.tags()) byTag.computeIfAbsent(tag, ignored -> new LinkedHashSet<>()).add(definition);
    }
    public synchronized Optional<MobDefinition> get(Identifier id) { return Optional.ofNullable(entries.get(id)); }
    public synchronized Map<Identifier, MobDefinition> all() { return frozen ? frozenEntries : Map.copyOf(entries); }
    public synchronized Set<MobDefinition> findByTag(String tag) {
        return frozen ? frozenTags.getOrDefault(tag, Set.of()) : Set.copyOf(byTag.getOrDefault(tag, Set.of()));
    }

    public synchronized void freeze(AbilityRegistry abilities, BehaviorRegistry behaviors) {
        if (frozen) return;
        for (MobDefinition definition : entries.values()) {
            Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(definition.entityType());
            if (typeId == null || BuiltInRegistries.ENTITY_TYPE.getValue(typeId) != definition.entityType())
                throw new IllegalStateException("MobForge mob " + definition.id() + " uses an unregistered EntityType");
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(definition.id()) && !definition.id().equals(typeId))
                throw new IllegalStateException("MobForge mob " + definition.id() + " collides with a different Minecraft EntityType " + typeId);
            for (Identifier id : definition.abilities()) if (abilities.get(id).isEmpty())
                throw new IllegalStateException("MobForge mob " + definition.id() + " references unknown ability " + id);
            for (Identifier id : definition.behaviors()) if (behaviors.get(id).isEmpty())
                throw new IllegalStateException("MobForge mob " + definition.id() + " references unknown behavior " + id);
        }
        frozenEntries = Map.copyOf(entries);
        Map<String, Set<MobDefinition>> tags = new LinkedHashMap<>();
        byTag.forEach((tag, definitions) -> tags.put(tag, Set.copyOf(definitions)));
        frozenTags = Map.copyOf(tags);
        frozen = true;
    }
}
