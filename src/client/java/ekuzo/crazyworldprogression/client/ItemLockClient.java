package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.storage.ItemLocks;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** The item lock key (L, used in inventory windows), the padlock mark on slots, and the lock line in tooltips. */
public final class ItemLockClient {
    private static KeyMapping key;

    private ItemLockClient() { }

    static void initialize() {
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.crazy-world-progression.item_lock",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("crazy-world-progression", "storage_locks"))));
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            int lock = ItemLocks.lock(stack);
            if (lock == 0 || lines.isEmpty()) return;
            lines.add(1, Component.literal(lock == ItemLocks.FULL ? "Locked: stays in its slot" : "Half locked: can't be dropped or stored")
                    .withStyle(lock == ItemLocks.FULL ? ChatFormatting.RED : ChatFormatting.GOLD));
        });
    }

    public static KeyMapping key() { return key; }

    /** A small padlock in the slot's top right corner: yellow for half, red for full. */
    public static void drawMark(GuiGraphicsExtractor graphics, int x, int y, int lock) {
        int color = lock == ItemLocks.FULL ? 0xFFE04040 : 0xFFF2C230;
        int left = x + 11, top = y;
        graphics.fill(left + 1, top, left + 4, top + 1, color);
        graphics.fill(left, top + 1, left + 1, top + 3, color);
        graphics.fill(left + 4, top + 1, left + 5, top + 3, color);
        graphics.fill(left, top + 3, left + 5, top + 6, color);
        graphics.fill(left + 2, top + 4, left + 3, top + 5, 0xFF202020);
    }
}
