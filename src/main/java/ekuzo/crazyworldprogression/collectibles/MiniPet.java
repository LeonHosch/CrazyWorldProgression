package ekuzo.crazyworldprogression.collectibles;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A pet that looks like a smaller vanilla mob: the game draws it with that mob's own model, texture and animations
 * (MiniPetRenderer on the client), but it behaves like every other collectible pet. A content mod registers one with
 * {@link #register} in its main initializer; CWP gives it its renderer on the client.
 */
public class MiniPet extends CwpPet implements MobLook {
    /**
     * How a mini pet looks: the vanilla mob it copies, its size compared to that mob, whether it hovers above the
     * ground (flying mobs) instead of walking, whether the mob is a baby, whether it acts as if under water (fish), and
     * an optional item model drawn around it (for example a water bubble), with that model's size in blocks.
     */
    public record Look(EntityType<?> vanilla, float scale, boolean hovers, boolean baby, boolean submerged,
                       Identifier shell, float shellSize) {
        public static Look of(EntityType<?> vanilla, float scale, boolean hovers) {
            return new Look(vanilla, scale, hovers, false, false, null, 0);
        }

        public Look asBaby() { return new Look(vanilla, scale, hovers, true, submerged, shell, shellSize); }

        /** Under water inside the given item model (assets/<namespace>/items/<path>.json), shellSize blocks wide. */
        public Look inShell(Identifier model, float size) { return new Look(vanilla, scale, hovers, baby, true, model, size); }
    }

    private static final Map<EntityType<?>, Look> LOOKS = new HashMap<>();

    public MiniPet(EntityType<? extends MiniPet> type, Level level) {
        super(type, level);
    }

    @Override
    public Look look() { return LOOKS.get(getType()); }

    /** Hovering looks (bee, ghast, fish in a bubble, ...) fly after their owner. */
    @Override
    protected boolean flies() {
        Look look = look();
        return look != null && look.hovers();
    }

    /**
     * Registers a mini pet: its entity type (named {@code id}, sized to the scaled mob, never saved) and its
     * collectible, so players can unlock and select it. Call it during mod initialization.
     */
    public static EntityType<MiniPet> register(Identifier id, String name, Rarity rarity, EntityType<?> vanilla, float scale,
                                               boolean hovers) {
        return register(id, name, rarity, Look.of(vanilla, scale, hovers));
    }

    /** As above, with a full {@link Look} (babies, fish in a bubble). */
    public static EntityType<MiniPet> register(Identifier id, String name, Rarity rarity, Look look) {
        EntityType<?> vanilla = look.vanilla();
        float scale = look.scale();
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
        EntityType<MiniPet> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.<MiniPet>of(MiniPet::new, MobCategory.MISC)
                        .sized(Math.max(0.2F, vanilla.getWidth() * scale), Math.max(0.2F, vanilla.getHeight() * scale))
                        .noSave().clientTrackingRange(10).build(key));
        FabricDefaultAttributeRegistry.register(type, createPetAttributes());
        LOOKS.put(type, look);
        Collectibles.registerPet(new Collectibles.PetDefinition(id, name, rarity, type));
        return type;
    }

    /** Every registered mini pet type, for giving them their renderer. */
    public static List<EntityType<?>> types() { return List.copyOf(LOOKS.keySet()); }
}
