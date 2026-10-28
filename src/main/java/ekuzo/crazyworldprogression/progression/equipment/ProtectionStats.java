package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Protection stats: Minecraft's protection system, shown and extended as player stats.
 *
 * <p>The enchantments keep working the vanilla way (data/minecraft/enchantment overrides lower them to
 * {@link #PROTECTION}% per level for Protection and {@link #SPECIALIZED}% for Fire, Blast and Projectile Protection
 * against their damage type; vanilla caps the total at 80%). Each kind has a stat that shows what the worn
 * enchantments give ({@code addProtection}, {@code addFireProtection}, {@code addBlastProtection},
 * {@code addProjectileProtection}). Skill trees and sets can add to them: whatever comes on top of the enchantments
 * goes into vanilla's calculation for the matching hits (EnchantmentProtectionMixin), under the same cap.
 */
public final class ProtectionStats {
    public static final double PROTECTION = 1.0;
    public static final double SPECIALIZED = 3.0;
    /** Vanilla protection points: each one lowers damage by 4%. */
    private static final double PERCENT_PER_POINT = 4.0;
    private static final List<EquipmentSlot> ARMOR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final Map<ServerPlayer, List<Integer>> LAST = new WeakHashMap<>();

    /** One protection stat: its enchantment, its percent per level and the damage it covers (null: all damage). */
    public enum Kind {
        ALL("addProtection", "protection", Enchantments.PROTECTION, PROTECTION, null),
        FIRE("addFireProtection", "fire_protection", Enchantments.FIRE_PROTECTION, SPECIALIZED, DamageTypeTags.IS_FIRE),
        BLAST("addBlastProtection", "blast_protection", Enchantments.BLAST_PROTECTION, SPECIALIZED, DamageTypeTags.IS_EXPLOSION),
        PROJECTILE("addProjectileProtection", "projectile_protection", Enchantments.PROJECTILE_PROTECTION, SPECIALIZED,
                DamageTypeTags.IS_PROJECTILE);

        public final String stat;
        public final String key;
        public final ResourceKey<Enchantment> enchantment;
        public final double perLevel;
        public final TagKey<DamageType> damage;

        Kind(String stat, String key, ResourceKey<Enchantment> enchantment, double perLevel, TagKey<DamageType> damage) {
            this.stat = stat;
            this.key = key;
            this.enchantment = enchantment;
            this.perLevel = perLevel;
            this.damage = damage;
        }

        /** The reduction (in percent) the worn enchantments of this kind give. */
        public double fromEnchantments(ServerPlayer player) { return levels(player, enchantment) * perLevel; }

        boolean covers(DamageSource source) { return damage == null || source.is(damage); }
    }

    private ProtectionStats() { }

    public static void initialize() {
        for (Kind kind : Kind.values()) {
            SkillStatRegistry.registerPercentStat(kind.stat, "stat.crazy-world-progression." + kind.key, (player, value) -> { });
            SkillStatRegistry.registerDynamicBonus(kind.stat, kind::fromEnchantments);
        }
        // Armor changed: refresh the stat totals when the protection levels worn changed.
        ServerEntityEvents.EQUIPMENT_CHANGE.register((entity, slot, previous, next) -> {
            if (!(entity instanceof ServerPlayer player) || !ARMOR.contains(slot)) return;
            List<Integer> now = java.util.Arrays.stream(Kind.values()).map(kind -> levels(player, kind.enchantment)).toList();
            if (!now.equals(LAST.put(player, now))) SkillStatRegistry.refresh(player);
        });
    }

    /** Vanilla protection points to add against {@code source} for what skill trees and sets add to the stats. */
    public static float extraPoints(ServerPlayer player, DamageSource source) {
        double extra = 0;
        for (Kind kind : Kind.values())
            if (kind.covers(source)) extra += SkillStatRegistry.getTotal(player, kind.stat) - kind.fromEnchantments(player);
        return (float) (extra / PERCENT_PER_POINT);
    }

    /** Total level of {@code enchantment} over the worn armor. */
    public static int levels(LivingEntity entity, ResourceKey<Enchantment> enchantment) {
        var lookup = entity.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> holder = lookup.get(enchantment).orElse(null);
        if (holder == null) return 0;
        int total = 0;
        for (EquipmentSlot slot : ARMOR) total += EnchantmentHelper.getItemEnchantmentLevel(holder, entity.getItemBySlot(slot));
        return total;
    }
}
