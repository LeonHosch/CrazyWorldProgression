package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.ammo.AmmoPouch;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The ammo slots on the client: R (rebindable) selects the next ammo slot, and next to the hotbar the three slots
 * show while the player has ammo or holds a bow or crossbow, the selected one framed.
 */
public final class AmmoClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(CrazyWorldProgression.id("combat"));
    private static KeyMapping cycleKey;
    private static int selected;

    private AmmoClient() { }

    static void initialize() {
        cycleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.crazy-world-progression.cycle_ammo",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (cycleKey.consumeClick())
                if (client.player != null && client.gui.screen() == null) {
                    selected = (selected + 1) % AmmoPouch.SLOTS;
                    ClientPlayNetworking.send(new AmmoPouch.CyclePayload(1));
                }
        });
        ClientPlayNetworking.registerGlobalReceiver(AmmoPouch.SelectedPayload.TYPE, (payload, context) -> selected = payload.slot());
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, CrazyWorldProgression.id("ammo"), AmmoClient::render);
    }

    /** The selected ammo slot (for the inventory screen). */
    public static int selected() { return selected; }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.isSpectator() || client.player.isCreative()) return;
        List<ItemStack> ammo = client.player.inventoryMenu.slots.stream().filter(AmmoPouch::isSlot)
                .map(slot -> slot.getItem()).toList();
        boolean armed = client.player.getMainHandItem().getItem() instanceof ProjectileWeaponItem
                || client.player.getOffhandItem().getItem() instanceof ProjectileWeaponItem;
        if (!armed && ammo.stream().allMatch(ItemStack::isEmpty)) return;
        int x = graphics.guiWidth() / 2 + 98, y = graphics.guiHeight() - 20;
        for (int index = 0; index < ammo.size(); index++) {
            int cellX = x + index * 20;
            graphics.fill(cellX, y, cellX + 18, y + 18, 0x90000000);
            if (index == selected) graphics.outline(cellX - 1, y - 1, 20, 20, 0xFFFFD866);
            ItemStack stack = ammo.get(index);
            if (stack.isEmpty()) continue;
            graphics.item(stack, cellX + 1, y + 1);
            graphics.itemDecorations(client.font, stack, cellX + 1, y + 1);
        }
    }
}
