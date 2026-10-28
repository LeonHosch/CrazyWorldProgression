package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.progression.equipment.EquipmentSetRegistry;
import ekuzo.crazyworldprogression.progression.equipment.EquipmentSetService;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * Item tooltips of set pieces: the set name, how many pieces are worn, and each bonus under its piece count. Stat
 * boosts come first, abilities last, and only the first line of a bonus shows the count. Abilities show their name;
 * holding Shift adds their description, wrapped and broken where the text has line breaks.
 */
final class EquipmentSetTooltip {
    private static final String INDENT = "    ";
    private static final int WRAP_WIDTH = 200;

    private EquipmentSetTooltip() { }

    static void initialize() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (stack.isEmpty()) return;
            var sets = EquipmentSetService.setsOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (sets.isEmpty()) return;
            Minecraft minecraft = Minecraft.getInstance();
            var player = minecraft.player;
            boolean details = minecraft.hasShiftDown();
            boolean anyAbility = false;
            for (EquipmentSetRegistry.EquipmentSet set : sets) {
                int worn = player == null ? 0 : EquipmentSetService.worn(player, set);
                lines.add(Component.empty());
                lines.add(Component.literal(set.name() + " (" + worn + "/" + set.pieces().size() + ")")
                        .withStyle(worn > 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY));
                for (EquipmentSetRegistry.Bonus bonus : set.bonuses()) {
                    boolean active = worn >= bonus.pieces();
                    ChatFormatting color = active ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY;
                    List<String> ordered = new ArrayList<>();
                    for (String stat : bonus.stats()) if (!SkillStatRegistry.isUnlock(stat)) ordered.add(stat);
                    for (String stat : bonus.stats()) if (SkillStatRegistry.isUnlock(stat)) ordered.add(stat);
                    boolean first = true;
                    for (String stat : ordered) {
                        String prefix = first ? "(" + bonus.pieces() + ") " : INDENT;
                        first = false;
                        if (!SkillStatRegistry.isUnlock(stat)) {
                            lines.add(Component.literal(prefix).append(SkillStatRegistry.describe(stat)).withStyle(color));
                            continue;
                        }
                        anyAbility = true;
                        lines.add(Component.literal(prefix).append(SkillStatRegistry.abilityName(stat))
                                .withStyle(color, ChatFormatting.BOLD));
                        if (details) for (String line : wrap(minecraft, SkillStatRegistry.describe(stat).getString()))
                            lines.add(Component.literal(INDENT + line)
                                    .withStyle(active ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
                    }
                }
            }
            if (anyAbility && !details) lines.add(Component.translatable("tooltip.crazy-world-progression.ability_details")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        });
    }

    /** Splits at the text's own line breaks, then wraps each part to the tooltip width. */
    private static List<String> wrap(Minecraft minecraft, String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("\\n|\n")) {
            List<FormattedText> wrapped = minecraft.font.getSplitter().splitLines(part, WRAP_WIDTH, Style.EMPTY);
            if (wrapped.isEmpty()) out.add("");
            for (FormattedText line : wrapped) out.add(line.getString());
        }
        return out;
    }
}
