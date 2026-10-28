package ekuzo.crazyworldprogression.storage;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Item categories for chest filters and quicksort: the creative inventory's category tabs (Building Blocks, Natural,
 * Combat, Food, ... and other mods' tabs), by tab ID. An item can be in several. Built on server start.
 */
public final class ItemCategories {
    private static volatile Map<Item, Set<String>> byItem = Map.of();

    private ItemCategories() { }

    static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(ItemCategories::build);
    }

    /** The category tabs, in creative inventory order. */
    public static List<CreativeModeTab> tabs() {
        List<CreativeModeTab> tabs = new ArrayList<>();
        for (CreativeModeTab tab : CreativeModeTabs.allTabs())
            if (tab.getType() == CreativeModeTab.Type.CATEGORY && !id(tab).equals("minecraft:op_blocks")) tabs.add(tab);
        return tabs;
    }

    public static String id(CreativeModeTab tab) {
        var key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
        return key == null ? "" : key.toString();
    }

    private static void build(MinecraftServer server) {
        CreativeModeTabs.tryRebuildTabContents(server.getWorldData().enabledFeatures(), true, server.registryAccess());
        Map<Item, Set<String>> map = new HashMap<>();
        for (CreativeModeTab tab : tabs()) {
            String id = id(tab);
            for (ItemStack stack : tab.getDisplayItems()) map.computeIfAbsent(stack.getItem(), ignored -> new LinkedHashSet<>()).add(id);
        }
        byItem = Map.copyOf(map);
    }

    /** The categories an item is in (empty for items in no tab). */
    public static Set<String> of(ItemStack stack) { return byItem.getOrDefault(stack.getItem(), Set.of()); }

    /** Whether an item is in one of these categories. */
    public static boolean in(ItemStack stack, Set<String> categories) {
        for (String category : of(stack)) if (categories.contains(category)) return true;
        return false;
    }
}
