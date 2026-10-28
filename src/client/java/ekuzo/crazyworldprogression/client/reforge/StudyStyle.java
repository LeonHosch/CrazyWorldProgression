package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CWP's default study table window: a scholar's desk. Dark oak frames with brass corners, parchment-edged panels,
 * inset wooden slots, and the book slot as an open book on a lectern lit by a flickering candle.
 */
public class StudyStyle implements ReforgeScreenStyle {
    private static final int OAK = 0xF22A1C11, OAK_LIGHT = 0xFF4A3220, BRASS = 0xFFC9A04A, PARCHMENT = 0xFFE9D9B4;

    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, OAK);
        // Wood grain: a few faint horizontal streaks.
        for (int line = y + 7; line < y + height - 4; line += 11)
            graphics.fill(x + 3, line, x + width - 3, line + 1, 0x14FFE0B0);
        graphics.outline(x, y, width, height, OAK_LIGHT);
        graphics.outline(x + 1, y + 1, width - 2, height - 2, 0xFF1A110A);
        int[][] corners = {{x, y}, {x + width - 5, y}, {x, y + height - 5}, {x + width - 5, y + height - 5}};
        for (int[] corner : corners) {
            graphics.fill(corner[0], corner[1], corner[0] + 5, corner[1] + 5, BRASS);
            graphics.fill(corner[0] + 1, corner[1] + 1, corner[0] + 4, corner[1] + 4, 0xFF8A6A2A);
        }
    }

    @Override
    public void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF3A2716);
        graphics.outline(x, y, width, height, 0xFF6B4A2C);
        // A parchment edge along the top and bottom, like the page edges of a ledger.
        graphics.fill(x + 2, y + 1, x + width - 2, y + 2, 0x66E9D9B4);
        graphics.fill(x + 2, y + height - 2, x + width - 2, y + height - 1, 0x66E9D9B4);
    }

    @Override
    public void slot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF140D07);
        graphics.fill(x, y, x + 17, y + 17, 0xFF5A3E26);
        graphics.fill(x, y, x + 16, y + 16, 0xFF2E1F12);
    }

    @Override
    public void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long time) {
        int left = centreX - 17, top = centreY - 17;
        // Candle glow that flickers a little.
        double flicker = 0.6 + 0.25 * Math.sin(time / 170.0) + 0.15 * Math.sin(time / 53.0);
        int glow = (int) (0x18 + 0x30 * flicker);
        graphics.fill(left - 4, top - 4, left + 38, top + 38, glow << 24 | 0xFFC060);
        // The open book: two parchment pages with a spine, resting on the lectern's slanted top.
        graphics.fill(left, top + 2, left + 34, top + 30, 0xFF5A3E26);
        graphics.fill(left + 1, top + 3, left + 16, top + 29, PARCHMENT);
        graphics.fill(left + 18, top + 3, left + 33, top + 29, PARCHMENT);
        graphics.fill(left + 16, top + 2, left + 18, top + 30, 0xFF7A1F1F);
        for (int line = top + 7; line < top + 26; line += 3) {
            graphics.fill(left + 3, line, left + 14, line + 1, 0x33553B20);
            graphics.fill(left + 20, line, left + 31, line + 1, 0x33553B20);
        }
        // The item sits in the middle of the pages; keep that spot calm.
        graphics.fill(left + 8, top + 8, left + 26, top + 26, 0xCCE9D9B4);
        // Lectern stand below the book.
        graphics.fill(left + 13, top + 30, left + 21, top + 35, OAK_LIGHT);
        graphics.fill(left + 8, top + 35, left + 26, top + 37, OAK_LIGHT);
        // Candle to the right.
        graphics.fill(left + 37, top + 22, left + 40, top + 32, 0xFFEFE6D2);
        int flame = (int) (2 + flicker * 2);
        graphics.fill(left + 38, top + 22 - flame, left + 39, top + 22, 0xFFFFC34A);
    }

    @Override public int titleColor() { return 0xFFF4E6C6; }

    @Override public int textColor() { return 0xFFEFE1C2; }

    @Override public int mutedColor() { return 0xFFB59A74; }

    @Override public int accentColor() { return 0xFFE8B34A; }

    @Override public int categoryColor() { return 0xFFF0C46A; }

    @Override public int goodColor() { return 0xFF9CD67A; }

    @Override public int badColor() { return 0xFFE8735A; }
}
