package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CWP's default crossbreeding window: a greenhouse potting bench. Weathered green wood with vine corners, a soil bed
 * panel, terracotta pot slots, and a sprouting seedling under a little glass dome as the result's socket.
 */
public class GreenhouseStyle implements ReforgeScreenStyle {
    private static final int WOOD = 0xF2233A22, WOOD_LIGHT = 0xFF3F6A3A, WOOD_DARK = 0xFF142514;
    private static final int LEAF = 0xFF5FA84A, LEAF_DARK = 0xFF3A7A2E, SOIL = 0xFF3E2A1A, TERRACOTTA = 0xFFA4582E;

    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, WOOD);
        // Vertical planks.
        for (int line = x + 12; line < x + width - 4; line += 16) graphics.fill(line, y + 3, line + 1, y + height - 3, 0x18000000);
        graphics.outline(x, y, width, height, WOOD_LIGHT);
        graphics.outline(x + 1, y + 1, width - 2, height - 2, WOOD_DARK);
        // Vines curling in from the corners.
        int[][] corners = {{x + 2, y + 2, 1, 1}, {x + width - 3, y + 2, -1, 1}, {x + 2, y + height - 3, 1, -1},
                {x + width - 3, y + height - 3, -1, -1}};
        for (int[] c : corners) {
            for (int step = 0; step < 7; step++) {
                int px = c[0] + c[2] * step, py = c[1] + c[3] * (step < 4 ? 0 : step - 3);
                graphics.fill(px, py, px + 1, py + 1, LEAF_DARK);
            }
            graphics.fill(c[0] + c[2] * 3, c[1] + c[3], c[0] + c[2] * 3 + 1, c[1] + c[3] + 1, LEAF);
            graphics.fill(c[0] + c[2] * 6, c[1] + c[3] * 4, c[0] + c[2] * 6 + 1, c[1] + c[3] * 4 + 1, LEAF);
        }
    }

    @Override
    public void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        // A soil bed with a wooden rim and a few lighter grains.
        graphics.fill(x, y, x + width, y + height, WOOD_LIGHT);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, SOIL);
        for (int gx = x + 5; gx < x + width - 4; gx += 9)
            for (int gy = y + 5 + (gx / 9 % 3); gy < y + height - 4; gy += 11)
                graphics.fill(gx, gy, gx + 1, gy + 1, 0x406A4A30);
    }

    @Override
    public void slot(GuiGraphicsExtractor graphics, int x, int y) {
        // A terracotta pot rim around dark soil.
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF5A2E16);
        graphics.fill(x, y, x + 17, y + 17, TERRACOTTA);
        graphics.fill(x, y, x + 16, y + 16, 0xFF2A1C10);
    }

    @Override
    public void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long time) {
        // A glass dome over the result slot, with a seedling that sways a little.
        int left = centreX - 14, top = centreY - 15;
        graphics.fill(left + 2, top + 26, left + 26, top + 29, 0xFF6B4A2C);
        graphics.outline(left + 3, top + 2, 22, 25, 0x66CFEFE8);
        graphics.fill(left + 6, top + 1, left + 22, top + 2, 0x66CFEFE8);
        graphics.fill(left + 4, top + 4, left + 6, top + 14, 0x33FFFFFF);
        int sway = (int) Math.round(Math.sin(time / 600.0));
        graphics.fill(left + 13 + sway, top + 4, left + 15 + sway, top + 7, LEAF);
        graphics.fill(left + 11 + sway, top + 5, left + 13 + sway, top + 6, LEAF);
    }

    @Override public int titleColor() { return 0xFFE6F2D2; }

    @Override public int textColor() { return 0xFFE2EED0; }

    @Override public int mutedColor() { return 0xFFA9BE94; }

    @Override public int accentColor() { return 0xFFB8E06A; }

    @Override public int categoryColor() { return 0xFFB8E06A; }

    @Override public int goodColor() { return 0xFF9CE07A; }

    @Override public int badColor() { return 0xFFFF8A6A; }
}
