package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** CWP's default reforge window: dark violet panels and an item socket with a faint enchantment shimmer. */
public class EnchantingStyle implements ReforgeScreenStyle {
    @Override
    public void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xF014131F);
        graphics.outline(x, y, width, height, 0xFF7C5CDA);
    }

    @Override
    public void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF1C1A2B);
        graphics.outline(x, y, width, height, 0xFF35305A);
    }

    @Override
    public void slot(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF0F0E18);
        graphics.fill(x, y, x + 17, y + 17, 0xFF2C2942);
        graphics.fill(x, y, x + 16, y + 16, 0xFF1B192A);
    }

    @Override
    public void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long timeMillis) {
        int left = centreX - 15, top = centreY - 15;
        double pulse = 0.5 + 0.5 * Math.sin(timeMillis / 600.0);
        graphics.fill(left - 2, top - 2, left + 32, top + 32, (int) (0x20 + 0x30 * pulse) << 24 | 0x9B6BFF);
        graphics.fill(left, top, left + 30, top + 30, 0xFF16142A);
        graphics.outline(left, top, 30, 30, 0xFF7C5CDA);
        graphics.fill(left + 6, top + 6, left + 24, top + 24, 0xFF221F3A);
        // A few glint sparkles drifting around the frame.
        for (int sparkle = 0; sparkle < 4; sparkle++) {
            long step = (timeMillis / 90 + sparkle * 29L) % 120;
            int edge = (int) (step * 4 % 120);
            int sx, sy;
            if (edge < 30) { sx = left + edge; sy = top; }
            else if (edge < 60) { sx = left + 29; sy = top + edge - 30; }
            else if (edge < 90) { sx = left + 89 - edge; sy = top + 29; }
            else { sx = left; sy = top + 119 - edge; }
            graphics.fill(sx, sy, sx + 1, sy + 1, 0xFFE6D8FF);
        }
    }

    @Override public int titleColor() { return 0xFFF2F0FA; }

    @Override public int textColor() { return 0xFFECEAF5; }

    @Override public int mutedColor() { return 0xFFA49FBD; }

    @Override public int accentColor() { return 0xFFC9B6FF; }

    @Override public int categoryColor() { return 0xFFB9A6FF; }
}
