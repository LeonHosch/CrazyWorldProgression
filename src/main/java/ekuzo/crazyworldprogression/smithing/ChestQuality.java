package ekuzo.crazyworldprogression.smithing;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.farming.ProduceQuality;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * Chest qualities: a chest holds two rows at Miserable (a crafted chest) and one more row per quality, up to six at
 * Perfect (ChestQualityMixin). The quality is on the chest item (the quality component) and on the placed chest; chests
 * from before qualities keep their 27 slots. Only chests of the same quality join into a double chest; one with more
 * than six rows gets a scrolling window (ScrollChestMenu). Craftsmanship upgrades a chest item one quality at a time
 * at the anvil ({@link #STEPS}, SmithingService). The quality is synced to clients for the chest's look.
 */
public final class ChestQuality {
    /** Slots every chest keeps room for, whatever its quality (so upgrading never loses items). */
    public static final int CAPACITY = 54;
    /** Slots of a chest from before qualities. */
    public static final int LEGACY = 27;
    public static final AttachmentType<Integer> QUALITY = AttachmentRegistry.create(CrazyWorldProgression.id("chest_quality"),
            builder -> builder.persistent(Codec.intRange(0, 4)).syncWith(net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
                    net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate.all()));

    /** Upgrading to quality index+1 (Normal to Perfect): the Craftsmanship rank it needs and what it costs. */
    public record Step(int rank, List<ReforgeData.Cost> costs) { }

    public static final List<Step> STEPS = List.of(
            new Step(1, List.of(cost("iron_ingot", 4), cost("oak_planks", 8))),
            new Step(3, List.of(cost("iron_ingot", 8), cost("gold_ingot", 4))),
            new Step(4, List.of(cost("diamond", 4), cost("gold_ingot", 8))),
            new Step(5, List.of(cost("netherite_ingot", 1), cost("diamond", 4))));

    private static ReforgeData.Cost cost(String item, int amount) {
        return new ReforgeData.Cost("item", Identifier.withDefaultNamespace(item), amount);
    }

    private ChestQuality() { }

    public static void initialize() { ScrollChestMenu.initialize(); }

    /** The window of a double chest: its rows (up to six), or a scrolling one above that. */
    public static net.minecraft.world.inventory.ChestMenu doubleMenu(int id, net.minecraft.world.entity.player.Inventory inventory,
                                                                    net.minecraft.world.Container container) {
        int rows = container.getContainerSize() / 9;
        if (rows <= ScrollChestMenu.VISIBLE_ROWS)
            return new net.minecraft.world.inventory.ChestMenu(menuType(rows), id, inventory, container, rows);
        return ScrollChestMenu.open(id, inventory, container);
    }

    /** A chest's quality as stored on the placed chest, or -1 for one from before qualities. */
    public static int placed(BlockEntity chest) {
        Integer quality = chest == null ? null : chest.getAttached(QUALITY);
        return quality == null ? -1 : quality;
    }

    public static boolean isChest(ItemStack stack) { return stack.is(Items.CHEST) || stack.is(Items.TRAPPED_CHEST); }

    /** A chest item's quality index: its component, or Miserable for a crafted chest. */
    public static int of(ItemStack stack) {
        ProduceQuality.Quality quality = ProduceQuality.get(stack);
        return quality == null ? 0 : quality.ordinal();
    }

    /** Slots of a placed chest: two rows plus one per quality, or 27 for one from before qualities. */
    public static int slots(BlockEntity chest) {
        Integer quality = chest.getAttached(QUALITY);
        return quality == null ? LEGACY : (quality + 2) * 9;
    }

    public static int slotsFor(int quality) { return (quality + 2) * 9; }

    public static MenuType<?> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }
}
