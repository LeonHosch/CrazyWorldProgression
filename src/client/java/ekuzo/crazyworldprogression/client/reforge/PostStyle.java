package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CWP's default postmaster window: a post office counter. Postal red frames trimmed with a cream airmail stripe,
 * a stamp-edged panel behind the mail, cream pigeonhole slots, and a little envelope with a wax seal as the emblem.
 */
public class PostStyle implements ReforgeScreenStyle {
    private static final int RED = 0xF2611A1A, RED_LIGHT = 0xFF8E2A26, RED_DARK = 0xFF3A0E0E, CREAM = 0xFFF1E4C6;
    private static final int AIRMAIL_BLUE = 0xFF2A4C8A;

    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, RED);
        graphics.outline(x, y, width, height, RED_LIGHT);
        graphics.outline(x + 1, y + 1, width - 2, height - 2, RED_DARK);
        // Airmail stripe along the top edge: alternating red and blue dashes on cream.
        graphics.fill(x + 3, y + 3, x + width - 3, y + 5, CREAM);
        for (int dash = x + 3; dash < x + width - 3; dash += 8) {
            graphics.fill(dash, y + 3, Math.min(dash + 4, x + width - 3), y + 5, 0xFFC0392B);
            graphics.fill(Math.min(dash + 4, x + width - 3), y + 3, Math.min(dash + 8, x + width - 3), y + 5, AIRMAIL_BLUE);
        }
    }

    @Override
    public void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        // A stamp: cream paper with a perforated edge, and a darker inset where the mail sits.
        graphics.fill(x, y, x + width, y + height, CREAM);
        for (int px = x + 2; px < x + width - 1; px += 4) {
            graphics.fill(px, y, px + 2, y + 1, RED);
            graphics.fill(px, y + height - 1, px + 2, y + height, RED);
        }
        for (int py = y + 2; py < y + height - 1; py += 4) {
            graphics.fill(x, py, x + 1, py + 2, RED);
            graphics.fill(x + width - 1, py, x + width, py + 2, RED);
        }
        graphics.fill(x + 3, y + 3, x + width - 3, y + height - 3, 0xFFD9C79E);
    }

    @Override
    public void slot(GuiGraphicsExtractor graphics, int x, int y) {
        // A pigeonhole: cream box with a shaded back.
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF7A5A3A);
        graphics.fill(x, y, x + 16, y + 16, 0xFFEFE1C0);
        graphics.fill(x, y, x + 16, y + 2, 0xFFCBB68C);
    }

    /** A slot that cannot hold mail: a closed hatch with a red seal. */
    public void sealedSlot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF4A2A1A);
        graphics.fill(x, y, x + 16, y + 16, 0xFF6B4A2E);
        graphics.fill(x + 1, y + 7, x + 15, y + 9, 0xFF4A2A1A);
        graphics.fill(x + 6, y + 6, x + 10, y + 10, 0xFF9A1E1E);
    }

    @Override
    public void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long time) {
        // Envelope with its flap folded down and a wax seal; the seal glints now and then.
        int left = centreX - 9, top = centreY - 6;
        graphics.fill(left, top, left + 18, top + 12, CREAM);
        graphics.outline(left, top, 18, 12, 0xFF8A7650);
        for (int step = 0; step < 6; step++) {
            graphics.fill(left + 1 + step, top + 1 + step, left + 2 + step, top + 2 + step, 0xFF8A7650);
            graphics.fill(left + 16 - step, top + 1 + step, left + 17 - step, top + 2 + step, 0xFF8A7650);
        }
        boolean glint = (time / 120) % 25 == 0;
        graphics.fill(left + 7, top + 5, left + 11, top + 9, glint ? 0xFFE04848 : 0xFFA82020);
    }

    @Override public int titleColor() { return 0xFFF8EBCB; }

    @Override public int textColor() { return 0xFFF1E4C6; }

    @Override public int mutedColor() { return 0xFFE0B9A6; }

    @Override public int accentColor() { return 0xFFF2C14E; }

    @Override public int categoryColor() { return 0xFFF2C14E; }

    @Override public int goodColor() { return 0xFF9CD67A; }

    @Override public int badColor() { return 0xFFFF8A6A; }
}
