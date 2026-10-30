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
 * A mount that looks like a vanilla mob (zombie horse, camel husk, zoglin, ...): drawn with that mob's own model,
 * texture and walk animation, with a saddle where the mob has one, and ridden like every other collectible mount.
 * A content mod registers one with {@link #register} in its main initializer; CWP gives it its renderer on the client.
 */
public class LookMount extends CwpMount implements MobLook {
    private static final Map<EntityType<?>, MiniPet.Look> LOOKS = new HashMap<>();

    public LookMount(EntityType<? extends LookMount> type, Level level) {
        super(type, level);
    }

    @Override
    public MiniPet.Look look() { return LOOKS.get(getType()); }

    /**
     * Registers a mount looking like {@code vanilla}: its entity type (named {@code id}, the mob's size, the rider
     * sitting {@code riderHeight} blocks up) and its collectible with the given speed and jump strength.
     */
    public static EntityType<LookMount> register(Identifier id, String name, Rarity rarity, EntityType<?> vanilla,
                                                 double speed, double jumpStrength, float riderHeight) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
        EntityType<LookMount> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.<LookMount>of(LookMount::new, MobCategory.MISC)
                        .sized(vanilla.getWidth(), vanilla.getHeight()).passengerAttachments(riderHeight)
                        .clientTrackingRange(10).build(key));
        FabricDefaultAttributeRegistry.register(type, createMountAttributes());
        LOOKS.put(type, MiniPet.Look.of(vanilla, 1.0F, false));
        Collectibles.registerMount(new Collectibles.MountDefinition(id, name, rarity, type, speed, jumpStrength));
        return type;
    }

    /** Every registered vanilla-look mount type, for giving them their renderer. */
    public static List<EntityType<?>> types() { return List.copyOf(LOOKS.keySet()); }
}
