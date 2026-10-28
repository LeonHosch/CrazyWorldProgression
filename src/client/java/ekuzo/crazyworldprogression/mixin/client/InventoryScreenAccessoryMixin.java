package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.accessory.Accessories;
import ekuzo.crazyworldprogression.accessory.BuffFood;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the two accessory slot frames (with a faint ring and necklace hint) next to the player model, and the buff food
 * slot below the crafting result (with a faint apple hint and a tooltip while empty), and the three ammo slots below
 * the panel.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenAccessoryMixin extends AbstractContainerScreen<InventoryMenu> {
    protected InventoryScreenAccessoryMixin(InventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void cwp$accessorySlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo info) {
        var accessories = menu.slots.stream().filter(Accessories::isSlot).toList();
        for (int index = 0; index < Accessories.SLOTS && index < accessories.size(); index++) {
            int x = leftPos + Accessories.SLOT_X - 1, y = topPos + Accessories.FIRST_SLOT_Y + index * 18 - 1;
            cwp$slotFrame(graphics, x, y);
            if (accessories.get(index).hasItem()) continue;
            // An empty slot shows a faint ring outline.
            int cx = x + 9, cy = y + 9;
            graphics.outline(cx - 4, cy - 4, 8, 8, 0x60373737);
            graphics.fill(cx - 1, cy - 6, cx + 1, cy - 4, 0x60373737);
        }
        // Ammo slots: a strip below the panel, the selected slot framed in gold.
        var ammo = menu.slots.stream().filter(ekuzo.crazyworldprogression.ammo.AmmoPouch::isSlot).toList();
        if (!ammo.isEmpty() && ammo.getFirst().isActive()) {
            int px = leftPos + ekuzo.crazyworldprogression.ammo.AmmoPouch.SLOT_X - 7;
            int py = topPos + ekuzo.crazyworldprogression.ammo.AmmoPouch.SLOT_Y - 7;
            int width = ekuzo.crazyworldprogression.ammo.AmmoPouch.SLOTS * 18 + 12;
            graphics.fill(px, py, px + width, py + 30, 0xFFC6C6C6);
            graphics.outline(px, py, width, 30, 0xFF555555);
            for (int index = 0; index < ammo.size(); index++) {
                int x = leftPos + ammo.get(index).x - 1, y = topPos + ammo.get(index).y - 1;
                cwp$slotFrame(graphics, x, y);
                if (index == ekuzo.crazyworldprogression.client.AmmoClient.selected())
                    graphics.outline(x - 1, y - 1, 20, 20, 0xFFFFC21A);
            }
            Slot hovered = ammo.stream().filter(slot -> mouseX >= leftPos + slot.x - 1 && mouseX < leftPos + slot.x + 17
                    && mouseY >= topPos + slot.y - 1 && mouseY < topPos + slot.y + 17 && !slot.hasItem()).findFirst().orElse(null);
            if (hovered != null) graphics.setComponentTooltipForNextFrame(font, java.util.List.of(
                    Component.translatable("tooltip.crazy-world-progression.ammo"),
                    Component.translatable("tooltip.crazy-world-progression.ammo.hint").withStyle(net.minecraft.ChatFormatting.GRAY)),
                    mouseX, mouseY);
        }
        var food = menu.slots.stream().filter(BuffFood::isSlot).findFirst().orElse(null);
        if (food == null || !food.isActive()) return;
        int x = leftPos + BuffFood.SLOT_X - 1, y = topPos + BuffFood.SLOT_Y - 1;
        cwp$slotFrame(graphics, x, y);
        if (food.hasItem()) return;
        // An empty slot shows a faint apple: a round body with a stem and a leaf.
        int cx = x + 9, cy = y + 10;
        graphics.fill(cx - 4, cy - 3, cx + 4, cy + 4, 0x60373737);
        graphics.fill(cx - 3, cy + 4, cx + 3, cy + 5, 0x60373737);
        graphics.fill(cx, cy - 6, cx + 1, cy - 3, 0x60373737);
        graphics.fill(cx + 1, cy - 6, cx + 3, cy - 5, 0x60373737);
        if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18)
            graphics.setComponentTooltipForNextFrame(font, java.util.List.of(
                    Component.translatable("tooltip.crazy-world-progression.buff_food"),
                    Component.translatable("tooltip.crazy-world-progression.buff_food.hint").withStyle(net.minecraft.ChatFormatting.GRAY),
                    Component.translatable("tooltip.crazy-world-progression.buff_food.hint2").withStyle(net.minecraft.ChatFormatting.GRAY)),
                    mouseX, mouseY);
    }

    @org.spongepowered.asm.mixin.Unique
    private static void cwp$slotFrame(GuiGraphicsExtractor graphics, int x, int y) {
        // Vanilla slot look: dark top-left edge, light bottom-right edge, grey inside.
        graphics.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        graphics.fill(x, y, x + 17, y + 1, 0xFF373737);
        graphics.fill(x, y, x + 1, y + 17, 0xFF373737);
        graphics.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF);
        graphics.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF);
    }
}
