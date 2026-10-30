package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.creative.CreativeSlots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The accessory, buff food and ammo slots on the creative inventory tab: placed by CreativeInventorySlotsMixin, and
 * their contents sent to the server while the creative screen is open (vanilla only sends its own 45 slots).
 */
public final class CreativeSlotsClient {
    /** Slot positions on the creative inventory tab (top-left of the item), right of the player. */
    public static final int X = 127, TOP_Y = 6, AMMO_Y = 33;
    private static final Set<Slot> WRAPPERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Integer, ItemStack> SENT = new HashMap<>();
    private static Constructor<?> wrapperConstructor;

    private CreativeSlotsClient() { }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(CreativeSlotsClient::sync);
    }

    /** Vanilla's own creative slot wrapper (its clicks expect one), at a new position. */
    public static Slot wrap(Slot target, int index, int x, int y) {
        try {
            if (wrapperConstructor == null) {
                Class<?> type = Class.forName(CreativeModeInventoryScreen.class.getName() + "$SlotWrapper");
                wrapperConstructor = type.getDeclaredConstructor(Slot.class, int.class, int.class, int.class);
                wrapperConstructor.setAccessible(true);
            }
            Slot wrapper = (Slot) wrapperConstructor.newInstance(target, index, x, y);
            WRAPPERS.add(wrapper);
            return wrapper;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CrazyWorldProgression.LOGGER.warn("Could not place the extra slots in the creative inventory", exception);
            return null;
        }
    }

    public static boolean isExtra(Slot slot) { return WRAPPERS.contains(slot); }

    private static void sync(Minecraft client) {
        if (client.player == null || !client.player.isCreative()
                || !(client.gui.screen() instanceof CreativeModeInventoryScreen)) {
            SENT.clear();
            return;
        }
        var slots = client.player.inventoryMenu.slots;
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            if (!CreativeSlots.isExtraSlot(slot)) continue;
            ItemStack current = slot.getItem();
            ItemStack sent = SENT.get(index);
            if (sent == null) {
                SENT.put(index, current.copy());
                continue;
            }
            if (ItemStack.matches(sent, current)) continue;
            SENT.put(index, current.copy());
            CwpClientPayloads.send(new CreativeSlots.SetPayload(index, current.copy()));
        }
    }
}
