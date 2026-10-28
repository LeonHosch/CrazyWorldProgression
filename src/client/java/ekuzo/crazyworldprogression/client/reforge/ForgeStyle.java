package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** CWP's default resmith (anvil) window: dark iron panels with ember accents and a glowing forge socket. */
public class ForgeStyle implements ReforgeScreenStyle {
    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xF2121212);
        graphics.outline(x, y, width, height, 0xFF6B5A4A);
        graphics.outline(x + 2, y + 2, width - 4, height - 4, 0x55E07A2E);
    }

    @Override
    public void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF1C1A18);
        graphics.outline(x, y, width, height, 0xFF4A3E33);
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x40E07A2E);
    }

    @Override
    public void slot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF0B0A09);
        graphics.fill(x, y, x + 17, y + 17, 0xFF3A332D);
        graphics.fill(x, y, x + 16, y + 16, 0xFF1F1C19);
    }

    @Override
    public void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long time) {
        double pulse = 0.5 + 0.5 * Math.sin(time / 380.0);
        int glow = (int) (0x30 + 0x60 * pulse);
        int left = centreX - 17, top = centreY - 17;
        graphics.fill(left - 3, top - 3, left + 37, top + 37, (glow / 3) << 24 | 0xFF8A2E);
        graphics.fill(left, top, left + 34, top + 34, 0xFF151311);
        graphics.outline(left, top, 34, 34, 0xFF6B5A4A);
        graphics.outline(left + 7, top + 7, 20, 20, glow << 24 | 0xFF9A3E);
        graphics.fill(left + 8, top + 8, left + 26, top + 26, 0xFF2A1A12);
        // Rivets.
        int[][] corners = {{left + 2, top + 2}, {left + 30, top + 2}, {left + 2, top + 30}, {left + 30, top + 30}};
        for (int[] corner : corners) graphics.fill(corner[0], corner[1], corner[0] + 2, corner[1] + 2, 0xFF9A8B7A);
        // Sparks flying up from the socket.
        for (int spark = 0; spark < 5; spark++) {
            long phase = (time / 35 + spark * 31L) % 36;
            int sx = left + 8 + (int) ((spark * 13 + (time / 500 + spark) % 5 * 3) % 18);
            int sy = top + 26 - (int) phase;
            int alpha = (int) (0xE0 * (1.0 - phase / 36.0));
            graphics.fill(sx, sy, sx + 1, sy + 1, alpha << 24 | (phase < 12 ? 0xFFE08A : 0xFF8A3A));
        }
    }

    @Override public int titleColor() { return 0xFFF3EDE6; }

    @Override public int textColor() { return 0xFFEDE6DE; }

    @Override public int mutedColor() { return 0xFFA89B8E; }

    @Override public int accentColor() { return 0xFFFFB067; }

    @Override public int categoryColor() { return 0xFFFFB067; }
}
