package ekuzo.crazyworldprogression.client.style;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The look of CWP's full-screen windows that have no themed design of their own: party, friends and clan, chest
 * filter and quicksort, and the extra buttons on chest and inventory windows. CWP ships {@link CwpPanelStyle} (its
 * original dark look); a content mod sets its own in its client initializer with {@code PanelStyle.set(new MyStyle())}
 * and overrides only what it wants. Only drawing goes through here, never behaviour.
 *
 * <p>Coordinates are screen coordinates. Colors are ARGB.
 */
public interface PanelStyle {
    /** Behind the window, over the whole screen. */
    void backdrop(GuiGraphicsExtractor graphics, int width, int height);

    /** The window itself. */
    void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height);

    /** A vertical line between two columns of a window. */
    void divider(GuiGraphicsExtractor graphics, int x, int top, int bottom);

    /** A thin progress bar (track and filled share). */
    default void bar(GuiGraphicsExtractor graphics, int x, int y, int width, double share, int color) {
        graphics.fill(x, y, x + width, y + 3, track());
        graphics.fill(x, y, x + (int) (width * Math.max(0, Math.min(1, share))), y + 3, color);
    }

    /**
     * Draws a button (frame, background and label) and returns true, or returns false to keep the vanilla button.
     */
    default boolean button(GuiGraphicsExtractor graphics, AbstractWidget button, boolean hovered) { return false; }

    int title();

    int text();

    int muted();

    /** Section headings ("Invites", "Members"). */
    int heading();

    /** Highlights: tiers, progress, selected things. */
    int accent();

    default int good() { return 0xFF86EFAC; }

    default int bad() { return 0xFFFF6B6B; }

    default int track() { return 0xFF2E3440; }

    final class Holder {
        private static volatile PanelStyle current = new CwpPanelStyle();
        private static int styling;
        private static final Set<AbstractWidget> STYLED = Collections.newSetFromMap(new WeakHashMap<>());

        private Holder() { }
    }

    static PanelStyle get() { return Holder.current; }

    static void set(PanelStyle style) { Holder.current = Objects.requireNonNull(style); }

    /** Windows call this around drawing their widgets, so their buttons take the style. */
    static void styling(boolean on) { Holder.styling += on ? 1 : -1; }

    /** Marks one button added to someone else's window (chest Sort and Filter buttons) as styled. */
    static <T extends AbstractWidget> T styled(T widget) {
        Holder.STYLED.add(widget);
        return widget;
    }

    /** Whether this button should be drawn by the style. */
    static boolean isStyled(AbstractWidget widget) { return Holder.styling > 0 || Holder.STYLED.contains(widget); }
}
