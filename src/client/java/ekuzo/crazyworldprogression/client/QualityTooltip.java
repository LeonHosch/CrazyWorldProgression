package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.farming.ProduceQuality;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;

import java.util.Locale;

/**
 * Under the name: "Quality: Superior" (the quality in its colour) for produce, food and gear with a quality (gear
 * without one shows Normal), and for any food
 * how much hunger and saturation it restores, after its quality (as FoodQualityMixin applies it).
 */
final class QualityTooltip {
    private QualityTooltip() { }

    static void initialize() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (stack.isEmpty() || lines.isEmpty()) return;
            ProduceQuality.Quality quality = ProduceQuality.get(stack);
            // Gear without a quality (crafted or looted) is Normal; repairs roll it (ItemDurability).
            if (quality == null && stack.isDamageableItem()) quality = ProduceQuality.Quality.NORMAL;
            int at = 1;
            if (quality != null) lines.add(at++, Component.literal("Quality: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(quality.label()).withStyle(quality.color())));
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) return;
            int hunger = food.nutrition();
            double saturation = food.saturation();
            if (quality != null) {
                hunger = (int) Math.round(hunger * ProduceQuality.NUTRITION[quality.ordinal()]);
                saturation *= ProduceQuality.SATURATION[quality.ordinal()];
            }
            lines.add(at, Component.literal("Hunger: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("+" + hunger).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("  Saturation: ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("+" + number(saturation)).withStyle(ChatFormatting.YELLOW)));
        });
    }

    private static String number(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
