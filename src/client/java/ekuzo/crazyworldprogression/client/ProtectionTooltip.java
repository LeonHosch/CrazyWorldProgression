package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.progression.equipment.ProtectionStats;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Map;

/**
 * Under each protection enchantment of an item, the stat it changes: Protection IV shows "-4% damage taken", Fire
 * Protection IV "-12% fire damage taken" (see ProtectionStats).
 */
final class ProtectionTooltip {
    private static final Map<ResourceKey<Enchantment>, String> KEYS = Map.of(
            Enchantments.PROTECTION, "protection", Enchantments.FIRE_PROTECTION, "fire_protection",
            Enchantments.BLAST_PROTECTION, "blast_protection", Enchantments.PROJECTILE_PROTECTION, "projectile_protection");

    private ProtectionTooltip() { }

    static void initialize() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (stack.isEmpty() || !stack.isEnchanted()) return;
            for (var entry : stack.getEnchantments().entrySet()) {
                Holder<Enchantment> enchantment = entry.getKey();
                String key = enchantment.unwrapKey().map(KEYS::get).orElse(null);
                if (key == null) continue;
                int level = entry.getIntValue();
                double percent = level * (key.equals("protection") ? ProtectionStats.PROTECTION : ProtectionStats.SPECIALIZED);
                Component line = Component.literal("  ").append(Component.translatable("tooltip.crazy-world-progression." + key,
                        "-" + (percent == Math.floor(percent) ? Integer.toString((int) percent) : Double.toString(percent))))
                        .withStyle(ChatFormatting.BLUE);
                String name = Enchantment.getFullname(enchantment, level).getString();
                int at = -1;
                for (int i = 0; i < lines.size(); i++) if (lines.get(i).getString().equals(name)) at = i;
                if (at >= 0) lines.add(at + 1, line);
                else lines.add(line);
            }
        });
    }
}
