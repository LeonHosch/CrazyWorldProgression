package ekuzo.crazyworldprogression.fletching;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Map;

/**
 * Fletched arrows (ArrowType), their Bleeding and Stunned effects, and the fletching table (FletchingNetworking).
 * Heavy arrows lose speed right after they are shot.
 */
public final class Fletching {
    public static final Map<ArrowType, Item> ARROWS = registerArrows();
    /** Damage every second that grows with the target's max health, up to a cap (ArrowType.BLEED_*). */
    public static final Holder<MobEffect> BLEEDING = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
            CrazyWorldProgression.id("bleeding"), new Bleeding());
    /** Can neither move nor jump. */
    public static final Holder<MobEffect> STUNNED = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
            CrazyWorldProgression.id("stunned"), new Stunned());
    private static final String SLOWED = "cwp_heavy_slowed";
    /** Told about every stun, also on mobs that are immune to it (they may still get their cast interrupted). */
    private static final java.util.List<java.util.function.BiConsumer<LivingEntity, net.minecraft.world.entity.Entity>> STUN_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** An explosive arrow's weak blast: ArrowType.EXPLOSION_DAMAGE at the center, less with distance and cover. */
    public static final net.minecraft.world.level.ExplosionDamageCalculator ARROW_BLAST = new net.minecraft.world.level.ExplosionDamageCalculator() {
        @Override
        public float getEntityDamageAmount(net.minecraft.world.level.Explosion explosion, net.minecraft.world.entity.Entity entity, float seen) {
            double distance = Math.sqrt(entity.distanceToSqr(explosion.center())) / (explosion.radius() * 2);
            return (float) (ArrowType.EXPLOSION_DAMAGE * seen * Math.max(0, 1 - distance));
        }
    };

    private Fletching() { }

    public static void initialize() {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                        Identifier.withDefaultNamespace("combat")))
                .register(output -> {
                    Item previous = Items.ARROW;
                    for (Item arrow : ARROWS.values()) {
                        output.insertAfter(previous, arrow);
                        previous = arrow;
                    }
                });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AbstractArrow arrow && entity.tickCount == 0 && !entity.entityTags().contains(SLOWED)
                    && FletchedArrowItem.type(arrow) == ArrowType.HEAVY) {
                arrow.setDeltaMovement(arrow.getDeltaMovement().scale(ArrowType.HEAVY_SPEED));
                arrow.addTag(SLOWED);
            }
        });
        FletchingNetworking.initialize();
    }

    /**
     * Stuns {@code target} for {@code ticks} (a mob can refuse the effect through canBeAffected). Listeners hear about
     * it either way, so stun-immune bosses can still have a cast interrupted.
     */
    public static void stun(LivingEntity target, int ticks, net.minecraft.world.entity.Entity source) {
        for (var listener : STUN_LISTENERS) listener.accept(target, source);
        target.addEffect(new net.minecraft.world.effect.MobEffectInstance(STUNNED, ticks), source);
    }

    /** Called with the target and source of every stun, before the effect is applied (or refused). */
    public static void onStun(java.util.function.BiConsumer<LivingEntity, net.minecraft.world.entity.Entity> listener) {
        STUN_LISTENERS.add(listener);
    }

    private static Map<ArrowType, Item> registerArrows() {
        Map<ArrowType, Item> arrows = new EnumMap<>(ArrowType.class);
        for (ArrowType type : ArrowType.values()) {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, CrazyWorldProgression.id(type.path()));
            arrows.put(type, Registry.register(BuiltInRegistries.ITEM, key,
                    new FletchedArrowItem(type, new Item.Properties().setId(key))));
        }
        return arrows;
    }

    private static final class Bleeding extends MobEffect {
        Bleeding() { super(MobEffectCategory.HARMFUL, 0x9B1010); }

        @Override
        public boolean shouldApplyEffectTickThisTick(int remaining, int amplifier) { return remaining % 20 == 0; }

        @Override
        public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
            float damage = (float) Math.min(ArrowType.BLEED_MAX, ArrowType.BLEED_BASE + ArrowType.BLEED_SHARE * entity.getMaxHealth());
            entity.hurtServer(level, level.damageSources().generic(), damage + amplifier);
            return true;
        }
    }

    private static final class Stunned extends MobEffect {
        Stunned() {
            super(MobEffectCategory.HARMFUL, 0xE8D44D);
            addAttributeModifier(Attributes.MOVEMENT_SPEED, CrazyWorldProgression.id("stunned_speed"), -1.0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            addAttributeModifier(Attributes.JUMP_STRENGTH, CrazyWorldProgression.id("stunned_jump"), -1.0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        }
    }
}
