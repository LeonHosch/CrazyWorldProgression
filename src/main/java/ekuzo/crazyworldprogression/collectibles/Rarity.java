package ekuzo.crazyworldprogression.collectibles;

import net.minecraft.ChatFormatting;

/** How rare a mount or pet is; shown in its colour. */
public enum Rarity {
    COMMON("Common", ChatFormatting.WHITE, 0xFFFFFF),
    UNCOMMON("Uncommon", ChatFormatting.GREEN, 0x55FF55),
    RARE("Rare", ChatFormatting.AQUA, 0x55A8FF),
    EPIC("Epic", ChatFormatting.LIGHT_PURPLE, 0xC060FF),
    LEGENDARY("Legendary", ChatFormatting.GOLD, 0xFFAA00);

    private final String label;
    private final ChatFormatting color;
    private final int rgb;

    Rarity(String label, ChatFormatting color, int rgb) {
        this.label = label;
        this.color = color;
        this.rgb = rgb;
    }

    public String label() { return label; }

    public ChatFormatting color() { return color; }

    /** The colour as 0xRRGGBB, for screens. */
    public int rgb() { return rgb; }
}
