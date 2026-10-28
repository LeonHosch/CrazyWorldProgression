package ekuzo.crazyworldprogression.smithing;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Tools, weapons and armor from iron upwards (repaired with iron, diamonds or netherite) no longer break. At the
 * last point of durability they become broken instead, like an elytra: they stay in the inventory, but give no
 * armor, attack damage, tool speed or enchantment effects until they are resmithed at an anvil (or repaired).
 */
public final class ItemDurability {
    private static final String BROKEN = "cwp_broken";

    private ItemDurability() { }

    /** True for damageable items repaired with iron ingots, diamonds or netherite ingots. */
    public static boolean isSturdy(ItemStack stack) {
        if (!stack.isDamageableItem()) return false;
        var repairable = stack.get(DataComponents.REPAIRABLE);
        if (repairable == null) return false;
        return repairable.isValidRepairItem(new ItemStack(Items.IRON_INGOT))
                || repairable.isValidRepairItem(new ItemStack(Items.DIAMOND))
                || repairable.isValidRepairItem(new ItemStack(Items.NETHERITE_INGOT));
    }

    public static boolean isBrokenDown(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(BROKEN, false);
    }

    /** Leaves the item at its last durability point and switches off what it does. */
    public static void breakDown(ItemStack stack) {
        if (isBrokenDown(stack)) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(BROKEN, true));
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        stack.remove(DataComponents.TOOL);
        stack.remove(DataComponents.WEAPON);
    }

    /** Durability by quality, Miserable to Perfect, as a share of the item's normal durability. */
    public static final double[] QUALITY_DURABILITY = {0.75, 1.0, 1.25, 1.5, 2.0};

    /** A damageable item's quality: what a repair rolled, or Normal (crafted and looted items). */
    public static ekuzo.crazyworldprogression.farming.ProduceQuality.Quality quality(ItemStack stack) {
        var quality = ekuzo.crazyworldprogression.farming.ProduceQuality.get(stack);
        return quality == null ? ekuzo.crazyworldprogression.farming.ProduceQuality.Quality.NORMAL : quality;
    }

    /**
     * Gives the item a quality and the durability that goes with it ({@link #QUALITY_DURABILITY} of its normal
     * durability), fully repaired.
     */
    public static void setQuality(ItemStack stack, ekuzo.crazyworldprogression.farming.ProduceQuality.Quality quality) {
        int base = stack.getItem().components().getOrDefault(DataComponents.MAX_DAMAGE, stack.getMaxDamage());
        stack.set(ekuzo.crazyworldprogression.farming.ProduceQuality.QUALITY, quality.ordinal());
        int max = (int) Math.max(1, Math.round(base * QUALITY_DURABILITY[quality.ordinal()]));
        stack.set(DataComponents.MAX_DAMAGE, max);
        stack.setDamageValue(0);
    }

    /** Gives a broken item back what it does; durability is set by the caller. */
    public static void restore(ItemStack stack) {
        if (!isBrokenDown(stack)) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(BROKEN));
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        var defaults = stack.getItem().components();
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, defaults.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY));
        if (defaults.has(DataComponents.TOOL)) stack.set(DataComponents.TOOL, defaults.get(DataComponents.TOOL));
        if (defaults.has(DataComponents.WEAPON)) stack.set(DataComponents.WEAPON, defaults.get(DataComponents.WEAPON));
    }
}
