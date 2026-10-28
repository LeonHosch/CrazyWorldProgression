package ekuzo.crazyworldprogression.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A picture for each enchantment. Where a status effect fits (Sharpness → Strength, Feather Falling → Slow
 * Falling, Mending → Regeneration) its icon is used; otherwise an item the enchantment is made for, preferring the
 * diamond (then iron) version. Modded enchantments without a matching effect get an item through their item tags.
 */
final class EnchantIcons {
    private static final Map<String, ItemStack> CACHE = new HashMap<>();
    private static final List<String> PREFERRED = List.of("diamond_", "iron_", "bow", "crossbow", "trident", "mace",
            "fishing_rod", "shears", "elytra");

    /** Vanilla enchantments (by path) and the status effect whose icon fits them. */
    private static final Map<String, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> EFFECTS = Map.ofEntries(
            Map.entry("protection", net.minecraft.world.effect.MobEffects.RESISTANCE),
            Map.entry("projectile_protection", net.minecraft.world.effect.MobEffects.RESISTANCE),
            Map.entry("blast_protection", net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE),
            Map.entry("fire_protection", net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE),
            Map.entry("feather_falling", net.minecraft.world.effect.MobEffects.SLOW_FALLING),
            Map.entry("respiration", net.minecraft.world.effect.MobEffects.WATER_BREATHING),
            Map.entry("aqua_affinity", net.minecraft.world.effect.MobEffects.CONDUIT_POWER),
            Map.entry("depth_strider", net.minecraft.world.effect.MobEffects.DOLPHINS_GRACE),
            Map.entry("riptide", net.minecraft.world.effect.MobEffects.DOLPHINS_GRACE),
            Map.entry("soul_speed", net.minecraft.world.effect.MobEffects.SPEED),
            Map.entry("swift_sneak", net.minecraft.world.effect.MobEffects.SPEED),
            Map.entry("sharpness", net.minecraft.world.effect.MobEffects.STRENGTH),
            Map.entry("smite", net.minecraft.world.effect.MobEffects.STRENGTH),
            Map.entry("bane_of_arthropods", net.minecraft.world.effect.MobEffects.STRENGTH),
            Map.entry("power", net.minecraft.world.effect.MobEffects.STRENGTH),
            Map.entry("impaling", net.minecraft.world.effect.MobEffects.STRENGTH),
            Map.entry("density", net.minecraft.world.effect.MobEffects.STRENGTH),
            Map.entry("breach", net.minecraft.world.effect.MobEffects.WEAKNESS),
            Map.entry("wind_burst", net.minecraft.world.effect.MobEffects.WIND_CHARGED),
            Map.entry("efficiency", net.minecraft.world.effect.MobEffects.HASTE),
            Map.entry("quick_charge", net.minecraft.world.effect.MobEffects.HASTE),
            Map.entry("fortune", net.minecraft.world.effect.MobEffects.LUCK),
            Map.entry("looting", net.minecraft.world.effect.MobEffects.LUCK),
            Map.entry("luck_of_the_sea", net.minecraft.world.effect.MobEffects.LUCK),
            Map.entry("lure", net.minecraft.world.effect.MobEffects.LUCK),
            Map.entry("unbreaking", net.minecraft.world.effect.MobEffects.ABSORPTION),
            Map.entry("mending", net.minecraft.world.effect.MobEffects.REGENERATION),
            Map.entry("vanishing_curse", net.minecraft.world.effect.MobEffects.INVISIBILITY),
            Map.entry("binding_curse", net.minecraft.world.effect.MobEffects.BAD_OMEN),
            Map.entry("thorns", net.minecraft.world.effect.MobEffects.INSTANT_DAMAGE));

    private EnchantIcons() { }

    /** Draws the enchantment's picture as a 16×16 icon: a fitting effect icon if there is one, else an item. */
    static void draw(net.minecraft.client.gui.GuiGraphicsExtractor graphics, String enchantmentId, int x, int y) {
        Identifier id = Identifier.tryParse(enchantmentId);
        var effect = id != null && id.getNamespace().equals("minecraft") ? EFFECTS.get(id.getPath()) : null;
        if (effect != null) {
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    net.minecraft.client.gui.Hud.getMobEffectSprite(effect), x - 1, y - 1, 18, 18);
            return;
        }
        graphics.item(icon(enchantmentId), x, y);
    }

    static Holder<Enchantment> enchantment(Identifier id) {
        var level = Minecraft.getInstance().level;
        if (id == null || level == null) return null;
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT, id)).orElse(null);
    }

    static ItemStack icon(String enchantmentId) {
        return CACHE.computeIfAbsent(enchantmentId, id -> {
            Holder<Enchantment> holder = enchantment(Identifier.tryParse(id));
            if (holder == null) return new ItemStack(Items.ENCHANTED_BOOK);
            var definition = holder.value().definition();
            var items = definition.primaryItems().orElse(definition.supportedItems());
            List<Item> candidates = items.stream().map(Holder::value).toList();
            for (String preferred : PREFERRED)
                for (Item item : candidates)
                    if (BuiltInRegistries.ITEM.getKey(item).getPath().startsWith(preferred)) return new ItemStack(item);
            return candidates.isEmpty() ? new ItemStack(Items.ENCHANTED_BOOK) : new ItemStack(candidates.getFirst());
        });
    }

    static String name(String enchantmentId) {
        Holder<Enchantment> holder = enchantment(Identifier.tryParse(enchantmentId));
        return holder == null ? enchantmentId : holder.value().description().getString();
    }

    static String roman(int level) {
        return switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; default -> Integer.toString(level); };
    }
}
