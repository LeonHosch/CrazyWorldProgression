package ekuzo.crazyworldprogression.fletching;

import ekuzo.crazyworldprogression.mixin.AbstractArrowAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A fletched arrow. It shoots a normal arrow entity that keeps this item as its pickup item, which is how the hit
 * effects find its type ({@link #type(AbstractArrow)}); damage and piercing are set here.
 */
public final class FletchedArrowItem extends ArrowItem {
    private final ArrowType type;

    public FletchedArrowItem(ArrowType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    public ArrowType type() { return type; }

    @Override
    public AbstractArrow createArrow(Level level, ItemStack ammo, LivingEntity shooter, ItemStack weapon) {
        AbstractArrow arrow = super.createArrow(level, ammo, shooter, weapon);
        switch (type) {
            case HEAVY -> arrow.setBaseDamage(ArrowType.HEAVY_DAMAGE);
            case EXPLOSIVE -> arrow.setBaseDamage(ArrowType.EXPLOSIVE_DAMAGE);
            case PIERCING -> ((AbstractArrowAccessor) arrow).cwp$setPierceLevel(
                    (byte) Math.max(arrow.getPierceLevel(), ArrowType.PIERCE_LEVEL));
            default -> { }
        }
        return arrow;
    }

    /** The fletched type of an arrow entity, or null for any other arrow. */
    public static ArrowType type(AbstractArrow arrow) {
        return arrow.getPickupItemStackOrigin().getItem() instanceof FletchedArrowItem item ? item.type() : null;
    }
}
