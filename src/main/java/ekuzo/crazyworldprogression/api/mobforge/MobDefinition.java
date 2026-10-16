package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.core.Holder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable content definition. The Minecraft entity type remains owned by its registering mod. */
public record MobDefinition(Identifier id, EntityType<?> entityType, String displayName,
                            Map<Holder<Attribute>, Double> attributes, Set<String> tags,
                            Set<Identifier> abilities, Set<Identifier> behaviors,
                            Map<Identifier, String> metadata) {
    public MobDefinition {
        Objects.requireNonNull(id, "mob ID");
        Objects.requireNonNull(entityType, "entity type for " + id);
        displayName = displayName == null ? "" : displayName;
        attributes = Map.copyOf(attributes);
        tags = Set.copyOf(tags);
        abilities = Set.copyOf(abilities);
        behaviors = Set.copyOf(behaviors);
        metadata = Map.copyOf(metadata);
        if (id.getNamespace().equals("minecraft") || id.getNamespace().equals(CrazyWorldProgression.MOD_ID))
            throw new IllegalArgumentException("Mob definition ID must use a content namespace: " + id);
        for (var entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || !Double.isFinite(entry.getValue()) || entry.getValue() < 0)
                throw new IllegalArgumentException("Invalid attribute value for mob " + id + ": " + entry);
            if (entry.getKey().equals(Attributes.MAX_HEALTH) && entry.getValue() <= 0)
                throw new IllegalArgumentException("Max health must be greater than zero for mob " + id);
        }
        for (String tag : tags) {
            if (tag == null || !tag.matches("[a-z0-9_./-]{1,64}"))
                throw new IllegalArgumentException("Invalid MobForge tag for " + id + ": " + tag);
        }
    }

    public static Builder builder(Identifier id, EntityType<?> entityType) { return new Builder(id, entityType); }

    public static final class Builder {
        private final Identifier id;
        private final EntityType<?> entityType;
        private String displayName = "";
        private final Map<Holder<Attribute>, Double> attributes = new LinkedHashMap<>();
        private final Set<String> tags = new LinkedHashSet<>();
        private final Set<Identifier> abilities = new LinkedHashSet<>();
        private final Set<Identifier> behaviors = new LinkedHashSet<>();
        private final Map<Identifier, String> metadata = new LinkedHashMap<>();

        private Builder(Identifier id, EntityType<?> entityType) {
            this.id = Objects.requireNonNull(id, "mob ID");
            this.entityType = Objects.requireNonNull(entityType, "entity type");
        }
        public Builder displayName(String value) { displayName = Objects.requireNonNull(value); return this; }
        public Builder attribute(Holder<Attribute> attribute, double value) { attributes.put(Objects.requireNonNull(attribute), value); return this; }
        public Builder tag(String tag) { tags.add(Objects.requireNonNull(tag)); return this; }
        public Builder ability(Identifier id) { abilities.add(Objects.requireNonNull(id)); return this; }
        public Builder behavior(Identifier id) { behaviors.add(Objects.requireNonNull(id)); return this; }
        public Builder metadata(Identifier key, String value) { metadata.put(Objects.requireNonNull(key), Objects.requireNonNull(value)); return this; }
        public MobDefinition build() { return new MobDefinition(id, entityType, displayName, attributes, tags, abilities, behaviors, metadata); }
    }
}
