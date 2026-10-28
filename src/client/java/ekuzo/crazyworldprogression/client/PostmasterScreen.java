package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.client.reforge.PostStyle;
import ekuzo.crazyworldprogression.client.reforge.ReforgeScreenStyle;
import ekuzo.crazyworldprogression.loot.PostmasterMenu;
import ekuzo.crazyworldprogression.loot.PostmasterService;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The postmaster in its own post office look (ReforgeScreenStyle.postmaster()): the mail sits on a stamp-edged
 * panel in cream pigeonholes, the seven slots past the twenty usable ones are sealed hatches, and the title shows
 * how full the postmaster is.
 */
public final class PostmasterScreen extends AbstractContainerScreen<PostmasterMenu> {
    private static final int WIDTH = 176, HEIGHT = 168;

    public PostmasterScreen(PostmasterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        inventoryLabelY = HEIGHT - 94;
    }

    static void initialize() {
        MenuScreens.register(PostmasterMenu.TYPE, PostmasterScreen::new);
    }

    /** Slots that hold mail right now (live, so it follows what the player takes out). */
    private int used() {
        int used = 0;
        for (int index = 0; index < PostmasterService.SLOTS; index++) if (menu.slots.get(index).hasItem()) used++;
        return used;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        ReforgeScreenStyle style = ReforgeScreenStyle.postmaster();
        int x = leftPos, y = topPos;
        style.window(graphics, x, y, WIDTH, HEIGHT);
        style.panel(graphics, x + 5, y + 15, WIDTH - 10, 3 * 18 + 6);
        for (Slot slot : menu.slots) {
            if (slot.index < 27 && PostmasterMenu.sealed(slot.index)) {
                if (style instanceof PostStyle post) post.sealedSlot(graphics, x + slot.x, y + slot.y);
                else style.slot(graphics, x + slot.x, y + slot.y);
            } else style.slot(graphics, x + slot.x, y + slot.y);
        }
        style.itemSocket(graphics, x + WIDTH - 16, y + 9, net.minecraft.util.Util.getMillis());
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ReforgeScreenStyle style = ReforgeScreenStyle.postmaster();
        int used = used();
        graphics.text(font, title, 8, 6, style.titleColor(), false);
        String count = used + "/" + PostmasterService.SLOTS;
        int color = used >= PostmasterService.SLOTS ? style.badColor()
                : used > PostmasterService.SLOTS / 2 ? style.accentColor() : style.mutedColor();
        graphics.text(font, Component.literal(count), WIDTH - 30 - font.width(count), 6, color, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, style.mutedColor(), false);
    }
}
