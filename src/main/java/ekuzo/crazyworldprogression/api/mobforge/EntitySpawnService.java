package ekuzo.crazyworldprogression.api.mobforge;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeSpawner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;

import java.util.Objects;
import java.util.function.Predicate;

/** Registered definitions, legacy YAML templates, then Minecraft EntityTypes. */
public final class EntitySpawnService {
    private EntitySpawnService() { }

    public enum Resolution { MOB_DEFINITION, YAML_TEMPLATE, ENTITY_TYPE }

    public static Resolution resolve(Identifier id) {
        return resolve(id, MobForge.mobs(),
                candidate -> candidate.getNamespace().equals(CrazyWorldProgression.MOD_ID)
                        && MobForge.TEMPLATES.get(candidate.getPath()).isPresent(),
                BuiltInRegistries.ENTITY_TYPE::containsKey);
    }

    /** Explicit resolver also lets integrations test precedence without loading a server world. */
    public static Resolution resolve(Identifier id, MobRegistry mobs, Predicate<Identifier> templateExists,
                                     Predicate<Identifier> entityTypeExists) {
        Objects.requireNonNull(id, "spawn ID");
        Objects.requireNonNull(mobs, "mob registry");
        Objects.requireNonNull(templateExists, "template resolver");
        Objects.requireNonNull(entityTypeExists, "entity type resolver");
        if (mobs.get(id).isPresent()) return Resolution.MOB_DEFINITION;
        if (templateExists.test(id)) return Resolution.YAML_TEMPLATE;
        if (entityTypeExists.test(id)) return Resolution.ENTITY_TYPE;
        throw new IllegalArgumentException("Unknown spawn ID " + id + " (no MobForge definition, template, or Minecraft EntityType)");
    }

    public static ScriptEntityHandle spawn(Identifier id, SpawnContext context) {
        Objects.requireNonNull(id, "spawn ID");
        Objects.requireNonNull(context, "spawn context");
        Resolution resolution = resolve(id);
        MobDefinition definition = resolution == Resolution.MOB_DEFINITION ? MobForge.mobs().get(id).orElseThrow() : null;
        if (definition != null) return spawnDefinition(definition, context);
        if (resolution == Resolution.YAML_TEMPLATE) {
            var template = MobForge.TEMPLATES.get(id.getPath());
            if (template.isPresent()) {
                Entity entity = MobForgeSpawner.spawn(context.world(), template.get(), context.position().x, context.position().y, context.position().z);
                attachContext(entity, context);
                return new EntityHandle(entity);
            }
        }
        Entity entity = BuiltInRegistries.ENTITY_TYPE.getValue(id).create(context.world(), EntitySpawnReason.COMMAND);
        if (entity == null) throw new IllegalStateException("EntityType " + id + " cannot be created in " + context.world().dimension().identifier());
        position(entity, context);
        if (!context.world().addFreshEntity(entity)) throw new IllegalStateException("World " + context.world().dimension().identifier() + " rejected entity " + id);
        attachContext(entity, context);
        return new EntityHandle(entity);
    }

    private static ScriptEntityHandle spawnDefinition(MobDefinition definition, SpawnContext context) {
        Entity entity = definition.entityType().create(context.world(), EntitySpawnReason.COMMAND);
        if (entity == null) throw new IllegalStateException("MobForge " + definition.id() + " EntityType cannot be created in " + context.world().dimension().identifier());
        position(entity, context);
        if (!definition.displayName().isBlank()) entity.setCustomName(Component.literal(definition.displayName()));
        if (!definition.attributes().isEmpty()) {
            if (!(entity instanceof LivingEntity living)) throw new IllegalStateException("MobForge " + definition.id() + " has attributes but its EntityType is not living");
            definition.attributes().forEach((attribute, value) -> {
                var instance = living.getAttribute(attribute);
                if (instance == null) throw new IllegalStateException("MobForge " + definition.id() + " EntityType lacks attribute " + attribute);
                instance.setBaseValue(value);
            });
            living.setHealth(living.getMaxHealth());
        }
        MobForgeEntityData.setDefinition(entity, definition.id());
        attachContext(entity, context);
        if (!context.world().addFreshEntity(entity)) throw new IllegalStateException("World " + context.world().dimension().identifier() + " rejected MobForge mob " + definition.id());
        RegisteredMobHandle handle = new RegisteredMobHandle(entity);
        MobForgeEvents.spawned(handle, context);
        return handle;
    }
    private static void position(Entity entity, SpawnContext context) {
        entity.setPos(context.position().x, context.position().y, context.position().z);
        entity.setYRot(context.yaw());
        entity.setXRot(context.pitch());
    }
    private static void attachContext(Entity entity, SpawnContext context) {
        MobForgeEntityData.setEncounter(entity, context.encounterId());
        MobForgeEntityData.setPocket(entity, context.pocketInstanceId());
        MobForgeEntityData.setExecution(entity, context.scriptExecutionId());
    }
}
