package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CWP's default cooking windows, one look per station. Stew pot: a warm brick hearth with a copper pot bubbling over
 * flames. Smoker: dark soot-stained stone with a smoking grate. Slots are stoneware bowls on both.
 */
public class KitchenStyle implements ReforgeScreenStyle {
    private final boolean smoker;

    public KitchenStyle(boolean smoker) { this.smoker = smoker; }

    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        int base = smoker ? 0xF2262422 : 0xF24A2418, mortar = smoker ? 0x22000000 : 0x33E8C8A0;
        graphics.fill(x, y, x + width, y + height, base);
        // Brick or stone courses.
        for (int row = 0; y + 4 + row * 10 < y + height - 3; row++) {
            int ry = y + 4 + row * 10;
            graphics.fill(x + 3, ry, x + width - 3, ry + 1, mortar);
            for (int bx = x + 3 + (row % 2) * 9; bx < x + width - 3; bx += 18) graphics.fill(bx, ry, bx + 1, ry + 10, mortar);
        }
        graphics.outline(x, y, width, height, smoker ? 0xFF4A4642 : 0xFF8A4A2E);
        graphics.outline(x + 1, y + 1, width - 2, height - 2, smoker ? 0xFF0E0D0C : 0xFF240E08);
    }

    @Override
    public void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, smoker ? 0xFF3A3632 : 0xFF6B3A24);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, smoker ? 0xFF1C1A18 : 0xFF2E1810);
    }

    @Override
    public void slot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, smoker ? 0xFF0E0D0C : 0xFF2A140C);
        graphics.fill(x, y, x + 17, y + 17, 0xFFB8A88C);
        graphics.fill(x, y, x + 16, y + 16, 0xFF3A322A);
    }

    @Override
    public void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long time) {
        int left = centreX - 16, top = centreY - 16;
        if (smoker) {
            // A grate with smoke curling up from it.
            graphics.fill(left + 2, top + 26, left + 30, top + 28, 0xFF55504A);
            for (int bar = left + 4; bar < left + 30; bar += 4) graphics.fill(bar, top + 25, bar + 1, top + 29, 0xFF6E6862);
            for (int puff = 0; puff < 3; puff++) {
                double phase = (time / 900.0 + puff / 3.0) % 1.0;
                int py = top + 22 - (int) (phase * 20), px = left + 10 + puff * 6 + (int) Math.round(Math.sin(phase * 6) * 2);
                int alpha = (int) (0x70 * (1 - phase));
                graphics.fill(px, py, px + 3, py + 3, alpha << 24 | 0x9A948E);
            }
        } else {
            // A copper pot over flickering flames.
            double flicker = 0.5 + 0.5 * Math.sin(time / 120.0);
            graphics.fill(left + 6, top + 27, left + 26, top + 30, 0xFF3A1A0C);
            for (int flame = left + 8; flame < left + 25; flame += 5) {
                int h = 2 + (int) (flicker * 2) + ((flame / 5) % 2);
                graphics.fill(flame, top + 27 - h, flame + 2, top + 27, 0xFFFF9A2A);
                graphics.fill(flame, top + 27 - h + 1, flame + 1, top + 27, 0xFFFFE07A);
            }
            graphics.outline(left + 3, top + 2, 26, 22, 0xFFC27A3A);
            graphics.fill(left + 1, top + 4, left + 3, top + 6, 0xFFC27A3A);
            graphics.fill(left + 29, top + 4, left + 31, top + 6, 0xFFC27A3A);
            int bubble = (int) ((time / 250) % 4);
            graphics.fill(left + 9 + bubble * 3, top + 3, left + 11 + bubble * 3, top + 4, 0x88FFFFFF);
        }
    }

    @Override public int titleColor() { return smoker ? 0xFFE8E2DA : 0xFFF6E4CC; }

    @Override public int textColor() { return smoker ? 0xFFDCD6CE : 0xFFF2DEC6; }

    @Override public int mutedColor() { return smoker ? 0xFFA09A92 : 0xFFC9A486; }

    @Override public int accentColor() { return smoker ? 0xFFE0B45A : 0xFFFFB45A; }

    @Override public int categoryColor() { return accentColor(); }

    @Override public int goodColor() { return 0xFF9CE07A; }

    @Override public int badColor() { return 0xFFFF7A5A; }
}
