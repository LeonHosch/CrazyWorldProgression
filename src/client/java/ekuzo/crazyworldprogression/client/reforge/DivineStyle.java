package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** CWP's default Divine Protection window: deep blue stone with gold trim and a slowly breathing halo. */
public class DivineStyle implements ReforgeScreenStyle {
    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xF0141826);
        graphics.outline(x, y, width, height, 0xFFD9B45A);
    }

    @Override
    public void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF1B2134);
        graphics.outline(x, y, width, height, 0xFF5A4C2A);
    }

    @Override
    public void slot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF0E111B);
        graphics.fill(x, y, x + 17, y + 17, 0xFF2A3047);
        graphics.fill(x, y, x + 16, y + 16, 0xFF191E2E);
    }

    @Override
    public void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long timeMillis) {
        int left = centreX - 15, top = centreY - 15;
        double pulse = 0.5 + 0.5 * Math.sin(timeMillis / 700.0);
        graphics.fill(left - 3, top - 3, left + 33, top + 33, (int) (0x18 + 0x38 * pulse) << 24 | 0xFFD978);
        graphics.fill(left, top, left + 30, top + 30, 0xFF151A2A);
        graphics.outline(left, top, 30, 30, 0xFFD9B45A);
        graphics.fill(left + 6, top + 6, left + 24, top + 24, 0xFF20273B);
        // Rays at the corners and edge centres that brighten in turn.
        int[][] rays = {{14, -3}, {33, 14}, {14, 33}, {-3, 14}, {-2, -2}, {31, -2}, {31, 31}, {-2, 31}};
        for (int ray = 0; ray < rays.length; ray++) {
            double glow = 0.5 + 0.5 * Math.sin(timeMillis / 300.0 + ray * 0.8);
            int alpha = (int) (0x50 + 0xAF * glow);
            graphics.fill(left + rays[ray][0], top + rays[ray][1], left + rays[ray][0] + 2, top + rays[ray][1] + 2,
                    alpha << 24 | 0xFFF1C8);
        }
    }

    @Override public int titleColor() { return 0xFFF7EBC8; }

    @Override public int textColor() { return 0xFFEDE8DA; }

    @Override public int mutedColor() { return 0xFFA3A6B8; }

    @Override public int accentColor() { return 0xFFF0C95A; }

    @Override public int categoryColor() { return 0xFFE2C77A; }
}
