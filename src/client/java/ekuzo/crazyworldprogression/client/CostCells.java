package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.currency.CurrencyDefinition;
import ekuzo.crazyworldprogression.currency.CurrencyRegistry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Costs drawn as fixed-size cells: a 16-pixel icon box (item, or currency icon scaled to fit) and then the amount,
 * so spacing stays the same for any icon size and any number of digits. Shared by the reforge and resmith windows.
 */
final class CostCells {
    /** Width of one cost: the icon box plus room for "×999". */
    static final int CELL = 42;
    private static final int CURRENCY_ICON_SOURCE_SIZE = 32;

    record Cost(String kind, String target, int amount, String label) { }

    /** Where a hovered cost's tooltip applies, in screen coordinates. */
    interface Hover { void add(int x, int y, int width, int height, List<Component> lines); }

    private CostCells() { }

    /**
     * One row of cells from x (relative to the window at leftPos/topPos) until right. have gives what the player
     * owns of a cost; amounts the player cannot pay are drawn in the style's bad color.
     */
    static void row(GuiGraphicsExtractor graphics, Font font, ReforgeScreenStyle style, List<Cost> costs, int x, int y,
                    int right, int leftPos, int topPos, ToLongFunction<Cost> have, Hover hover) {
        for (Cost cost : costs) {
            if (x + CELL > right) break;
            Identifier id = Identifier.tryParse(cost.target());
            if (cost.kind().equals("currency")) {
                CurrencyDefinition currency = id == null ? null : CurrencyRegistry.get(id);
                if (currency != null) {
                    float scale = Math.min(16f / currency.iconWidth(), 16f / currency.iconHeight());
                    int width = Math.max(1, Math.round(currency.iconWidth() * scale));
                    int height = Math.max(1, Math.round(currency.iconHeight() * scale));
                    graphics.blit(RenderPipelines.GUI_TEXTURED, currency.icon(), x + (16 - width) / 2,
                            y + (16 - height) / 2, currency.iconU(), currency.iconV(), width, height,
                            currency.iconWidth(), currency.iconHeight(), CURRENCY_ICON_SOURCE_SIZE, CURRENCY_ICON_SOURCE_SIZE);
                }
            } else if (id != null) {
                var item = BuiltInRegistries.ITEM.getOptional(id);
                if (item.isPresent()) graphics.item(new ItemStack(item.get()), x, y);
            }
            long owned = have.applyAsLong(cost);
            graphics.text(font, "×" + cost.amount(), x + 18, y + 5,
                    owned >= cost.amount() ? style.textColor() : style.badColor(), false);
            hover.add(leftPos + x, topPos + y, CELL - 2, 16, List.of(Component.literal(cost.label()),
                    Component.literal("Needs " + cost.amount() + ", you have " + owned)));
            x += CELL;
        }
    }
}
