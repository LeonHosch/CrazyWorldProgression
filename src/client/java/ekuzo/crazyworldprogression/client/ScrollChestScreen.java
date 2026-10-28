package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.smithing.ScrollChestMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The window of a chest with more than six rows: the normal six-row chest window, scrolled a row at a time with the
 * mouse wheel or the arrows beside it (ScrollChestMenu).
 */
public final class ScrollChestScreen extends ContainerScreen {
    private final ScrollChestMenu scrollMenu;

    public ScrollChestScreen(net.minecraft.world.inventory.ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.scrollMenu = (ScrollChestMenu) menu;
    }

    static void initialize() {
        MenuScreens.<net.minecraft.world.inventory.ChestMenu, ScrollChestScreen>register(ScrollChestMenu.TYPE, ScrollChestScreen::new);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("▲"), ignored -> scroll(0))
                .bounds(leftPos + imageWidth + 2, topPos + 18, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), ignored -> scroll(1))
                .bounds(leftPos + imageWidth + 2, topPos + 18 + ScrollChestMenu.VISIBLE_ROWS * 18 - 16, 16, 16).build());
    }

    private void scroll(int button) {
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0) {
            scroll(vertical > 0 ? 0 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int first = scrollMenu.offset() + 1, last = scrollMenu.offset() + ScrollChestMenu.VISIBLE_ROWS;
        String rows = "Rows " + first + "–" + last + " of " + scrollMenu.rows();
        graphics.text(font, rows, leftPos + imageWidth - 8 - font.width(rows), topPos + 6, 0xFF404040, false);
        // Scroll bar between the arrows.
        int barTop = topPos + 36, barHeight = ScrollChestMenu.VISIBLE_ROWS * 18 - 36;
        graphics.fill(leftPos + imageWidth + 8, barTop, leftPos + imageWidth + 12, barTop + barHeight, 0xFF555555);
        int steps = Math.max(1, scrollMenu.rows() - ScrollChestMenu.VISIBLE_ROWS);
        int knob = Math.max(8, barHeight * ScrollChestMenu.VISIBLE_ROWS / scrollMenu.rows());
        int knobY = barTop + (barHeight - knob) * scrollMenu.offset() / steps;
        graphics.fill(leftPos + imageWidth + 7, knobY, leftPos + imageWidth + 13, knobY + knob, 0xFFC6C6C6);
    }
}
