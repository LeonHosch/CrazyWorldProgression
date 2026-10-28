package ekuzo.crazyworldprogression.client.style;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** CWP's own look: a dark slate window with a violet frame and vanilla buttons. */
public class CwpPanelStyle implements PanelStyle {
    @Override
    public void backdrop(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, 0xA0000000);
    }

    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF7C5CDA);
        graphics.fill(x, y, x + width, y + height, 0xF012151B);
    }

    @Override
    public void divider(GuiGraphicsExtractor graphics, int x, int top, int bottom) {
        graphics.fill(x, top, x + 1, bottom, 0xFF2E3440);
    }

    @Override public int title() { return 0xFFF2F2F5; }

    @Override public int text() { return 0xFFE8E8E8; }

    @Override public int muted() { return 0xFF9CA3AF; }

    @Override public int heading() { return 0xFFE6DFFF; }

    @Override public int accent() { return 0xFFFFD866; }
}
