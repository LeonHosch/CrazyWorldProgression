package ekuzo.crazyworldprogression.client.reforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Objects;

/**
 * The look of the reforge window at the enchanting table. CWP ships {@link EnchantingStyle}; a content mod sets
 * its own in its client initializer with {@code ReforgeScreenStyle.set(new MyStyle())} and only overrides what
 * it wants to change. Names (title, button) come from {@code ReforgeBranding} instead.
 *
 * <p>Coordinates are screen coordinates. Colors are ARGB.
 */
public interface ReforgeScreenStyle {
    /** Window, tier panel and enchantment list background and frame. */
    void window(GuiGraphicsExtractor graphics, int x, int y, int width, int height);

    /** The box behind the tier choice and its costs. */
    void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height);

    /** One inventory slot; (x, y) is the slot's top-left item position, the slot is 18×18 around it. */
    void slot(GuiGraphicsExtractor graphics, int x, int y);

    /**
     * The decoration around the item slot, centred on the slot. Drawn every frame, so it may animate.
     * It should stay within 40×40 pixels.
     */
    void itemSocket(GuiGraphicsExtractor graphics, int centreX, int centreY, long timeMillis);

    int titleColor();

    int textColor();

    int mutedColor();

    /** Headings such as "Possible enchantments" and the proficiency in the corner. */
    int accentColor();

    /** Nested pools listed as categories. */
    int categoryColor();

    default int goodColor() { return 0xFF72D69C; }

    default int badColor() { return 0xFFFF6B6B; }

    default String categoryMarker() { return "▸ "; }

    final class Holder {
        private static volatile ReforgeScreenStyle current = new EnchantingStyle();
        private static volatile ReforgeScreenStyle smithing = new ForgeStyle();
        private static volatile ReforgeScreenStyle study = new StudyStyle();
        private static volatile ReforgeScreenStyle postmaster = new PostStyle();
        private static volatile ReforgeScreenStyle crossbreeding = new GreenhouseStyle();
        private static volatile ReforgeScreenStyle stewPot = new KitchenStyle(false);
        private static volatile ReforgeScreenStyle smoker = new KitchenStyle(true);
        private static volatile ReforgeScreenStyle divine = new DivineStyle();

        private Holder() { }
    }

    /** The enchanting table's reforge window. */
    static ReforgeScreenStyle get() { return Holder.current; }

    static void set(ReforgeScreenStyle style) { Holder.current = Objects.requireNonNull(style); }

    /** The anvil's resmith window (default {@link ForgeStyle}). */
    static ReforgeScreenStyle smithing() { return Holder.smithing; }

    static void setSmithing(ReforgeScreenStyle style) { Holder.smithing = Objects.requireNonNull(style); }

    /** The lectern's study table window (default {@link StudyStyle}). */
    static ReforgeScreenStyle study() { return Holder.study; }

    static void setStudy(ReforgeScreenStyle style) { Holder.study = Objects.requireNonNull(style); }

    /** The postmaster window (default {@link PostStyle}); itemSocket is drawn once, as the emblem by the title. */
    static ReforgeScreenStyle postmaster() { return Holder.postmaster; }

    static void setPostmaster(ReforgeScreenStyle style) { Holder.postmaster = Objects.requireNonNull(style); }

    /** The composter's crossbreeding window (default {@link GreenhouseStyle}). */
    static ReforgeScreenStyle crossbreeding() { return Holder.crossbreeding; }

    static void setCrossbreeding(ReforgeScreenStyle style) { Holder.crossbreeding = Objects.requireNonNull(style); }

    /** The cauldron's stew cooking window (default {@link KitchenStyle} hearth). */
    static ReforgeScreenStyle stewPot() { return Holder.stewPot; }

    static void setStewPot(ReforgeScreenStyle style) { Holder.stewPot = Objects.requireNonNull(style); }

    /** The smoker's cooking window (default {@link KitchenStyle} smokehouse). */
    static ReforgeScreenStyle smoker() { return Holder.smoker; }

    static void setSmoker(ReforgeScreenStyle style) { Holder.smoker = Objects.requireNonNull(style); }

    /** The Divine Protection window (default {@link DivineStyle}). */
    static ReforgeScreenStyle divine() { return Holder.divine; }

    static void setDivine(ReforgeScreenStyle style) { Holder.divine = Objects.requireNonNull(style); }
}
