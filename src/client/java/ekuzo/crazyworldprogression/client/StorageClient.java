package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.client.style.PanelStyle;
import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.storage.StorageNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chest filters and quicksort on the client: the Filter button above a chest window and its category window, the
 * quicksort window (K), and items flying into chests when quicksort puts them away.
 */
final class StorageClient {
    private static StorageNetworking.FilterInfoPayload filterInfo;
    private static long filterAt;
    private static KeyMapping quicksortKey;

    /** Client-only item entities flying from the player into a chest. */
    private record Flight(ItemEntity item, Vec3 from, Vec3 to, int[] age) { }

    private static final List<Flight> FLIGHTS = new ArrayList<>();
    private static final int FLIGHT_TICKS = 8;

    private StorageClient() { }

    static void initialize() {
        quicksortKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.crazy-world-progression.quicksort",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("crazy-world-progression", "storage"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (quicksortKey.consumeClick())
                if (client.player != null && client.gui.screen() == null)
                    ekuzo.crazyworldprogression.client.CwpClientPayloads.send(new StorageNetworking.QuicksortPayload("", false, false, true));
            tickFlights(client);
        });
        ClientPlayNetworking.registerGlobalReceiver(StorageNetworking.FilterInfoPayload.TYPE, (payload, context) -> {
            filterInfo = payload;
            filterAt = System.currentTimeMillis();
        });
        ClientPlayNetworking.registerGlobalReceiver(StorageNetworking.QuicksortPayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() instanceof QuicksortScreen screen) screen.update(payload);
            else if (payload.open()) context.client().gui.setScreen(new QuicksortScreen(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(StorageNetworking.SortedPayload.TYPE, (payload, context) -> fly(context.client(), payload));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> sortButtons(client, screen));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container) || !(container.getMenu() instanceof ChestMenu menu)
                    || filterInfo == null || System.currentTimeMillis() - filterAt > 3000) return;
            int rows = Math.min(6, menu.getRowCount());
            int left = (width - 176) / 2, top = (height - (114 + rows * 18)) / 2;
            StorageNetworking.FilterInfoPayload info = filterInfo;
            Button button = Button.builder(Component.literal(info.categories().isEmpty() ? "Filter: all" : "Filter: "
                    + info.categories().split(",").length), ignored -> {
                client.player.closeContainer();
                client.gui.setScreen(new FilterScreen(info.pos(), categories(info.categories())));
            }).bounds(left, top - 15, 64, 14).build();
            button.setTooltip(Tooltip.create(Component.literal("Choose which item types this chest takes")));
            Screens.getWidgets(screen).add(PanelStyle.styled(button));
        });
    }

    /**
     * Sort and Quick stack beside chest windows (left side), and Sort above the player's inventory window. Buttons
     * follow the window when it moves (the recipe book).
     */
    private static void sortButtons(Minecraft client, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container) || client.player == null || client.player.isCreative()) return;
        var bounds = (CharacterSheetClient.ContainerBounds) container;
        List<Button> buttons = new ArrayList<>();
        List<int[]> offsets = new ArrayList<>();
        if (container.getMenu() instanceof ChestMenu) {
            Button sort = Button.builder(Component.literal("Sort"), ignored -> sort("sort_chest")).size(40, 16).build();
            sort.setTooltip(Tooltip.create(Component.literal("Sort this chest: stacks merged, grouped by item type")));
            Button stack = Button.builder(Component.literal("Stack"), ignored -> sort("stack_chest")).size(40, 16).build();
            stack.setTooltip(Tooltip.create(Component.literal("Quick stack: puts items from your inventory (not the hotbar or locked items) into this chest where it already holds them")));
            buttons.add(sort);
            offsets.add(new int[]{-42, 4});
            buttons.add(stack);
            offsets.add(new int[]{-42, 22});
        } else if (screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
            Button sort = Button.builder(Component.literal("Sort"), ignored -> sort("sort_inventory")).size(40, 14).build();
            sort.setTooltip(Tooltip.create(Component.literal("Sort your inventory (the hotbar and fully locked items stay put)")));
            buttons.add(sort);
            offsets.add(new int[]{-1, -15});
        }
        if (buttons.isEmpty()) return;
        Runnable place = () -> {
            for (int index = 0; index < buttons.size(); index++) {
                int[] offset = offsets.get(index);
                int x = offset[0] < 0 && offset[1] < 0 ? bounds.cwp$leftPos() + bounds.cwp$imageWidth() - buttons.get(index).getWidth()
                        : bounds.cwp$leftPos() + offset[0];
                buttons.get(index).setPosition(x, bounds.cwp$topPos() + offset[1]);
            }
        };
        place.run();
        for (Button button : buttons) Screens.getWidgets(screen).add(PanelStyle.styled(button));
        ScreenEvents.beforeExtract(screen).register((current, graphics, mouseX, mouseY, delta) -> place.run());
    }

    private static void sort(String action) {
        if (ClientPlayNetworking.canSend(ekuzo.crazyworldprogression.storage.SortActions.ActionPayload.TYPE))
            ekuzo.crazyworldprogression.client.CwpClientPayloads.send(new ekuzo.crazyworldprogression.storage.SortActions.ActionPayload(action));
    }

    static Set<String> categories(String joined) {
        return joined.isEmpty() ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(joined.split(",")));
    }

    /** The creative category tabs (the item types), as on the server (ItemCategories). */
    static List<CreativeModeTab> tabs() {
        List<CreativeModeTab> tabs = new ArrayList<>();
        for (CreativeModeTab tab : CreativeModeTabs.allTabs())
            if (tab.getType() == CreativeModeTab.Type.CATEGORY && !id(tab).equals("minecraft:op_blocks")) tabs.add(tab);
        return tabs;
    }

    static String id(CreativeModeTab tab) {
        var key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
        return key == null ? "" : key.toString();
    }

    private static int nextFlightId = -1_000_000;

    private static void fly(Minecraft client, StorageNetworking.SortedPayload payload) {
        if (client.level == null) return;
        Vec3 to = Vec3.atCenterOf(payload.chest());
        int index = 0;
        for (var stack : payload.items()) {
            Vec3 from = payload.from().add((index - 1) * 0.2, 0, 0);
            ItemEntity item = new ItemEntity(client.level, from.x, from.y, from.z, stack.copy());
            item.setNoGravity(true);
            item.noPhysics = true;
            item.setNeverPickUp();
            // Only the client knows these items, so they get ids the server never hands out.
            item.setId(nextFlightId--);
            client.level.addEntity(item);
            FLIGHTS.add(new Flight(item, from, to, new int[]{-index * 2}));
            index++;
        }
    }

    private static void tickFlights(Minecraft client) {
        for (Iterator<Flight> it = FLIGHTS.iterator(); it.hasNext(); ) {
            Flight flight = it.next();
            int age = ++flight.age()[0];
            if (age > FLIGHT_TICKS || client.level == null) {
                flight.item().discard();
                it.remove();
                continue;
            }
            double t = Math.max(0, age) / (double) FLIGHT_TICKS;
            Vec3 at = flight.from().lerp(flight.to(), t).add(0, Math.sin(t * Math.PI) * 0.4, 0);
            flight.item().setDeltaMovement(Vec3.ZERO);
            flight.item().setPos(at);
        }
    }

    /** A grid of item categories to tick on and off, shared by the filter and quicksort windows. */
    static final class CategoryGrid {
        private final List<CreativeModeTab> tabs = tabs();
        final Set<String> selected;
        private int left, top, columns;

        CategoryGrid(Set<String> selected) { this.selected = selected; }

        static final int CELL_W = 140, CELL_H = 20;

        void build(java.util.function.Consumer<Button> add, int left, int top, int width, Runnable changed) {
            this.left = left;
            this.top = top;
            this.columns = Math.max(1, width / CELL_W);
            for (int index = 0; index < tabs.size(); index++) {
                CreativeModeTab tab = tabs.get(index);
                String id = id(tab);
                int x = left + (index % columns) * CELL_W + 20, y = top + (index / columns) * CELL_H;
                Button button = Button.builder(label(tab), ignored -> {
                    if (!selected.remove(id)) selected.add(id);
                    changed.run();
                }).bounds(x, y, CELL_W - 24, 18).build();
                add.accept(button);
            }
        }

        private Component label(CreativeModeTab tab) {
            return Component.literal((selected.contains(id(tab)) ? "✔ " : "   ")).append(tab.getDisplayName());
        }

        void icons(GuiGraphicsExtractor graphics) {
            for (int index = 0; index < tabs.size(); index++) {
                int x = left + (index % columns) * CELL_W, y = top + (index / columns) * CELL_H;
                graphics.item(tabs.get(index).getIconItem(), x, y + 1);
            }
        }

        int height() { return ((tabs.size() + columns - 1) / columns) * CELL_H; }

        List<String> all() { return tabs.stream().map(StorageClient::id).toList(); }
    }

    /** The chest filter window: tick the item types the chest takes (all ticked: it takes everything). */
    static final class FilterScreen extends Screen {
        private final BlockPos pos;
        private final CategoryGrid grid;

        FilterScreen(BlockPos pos, Set<String> selected) {
            super(Component.literal("Chest filter"));
            this.pos = pos;
            this.grid = new CategoryGrid(selected);
            if (selected.isEmpty()) selected.addAll(grid.all());
        }

        @Override
        protected void init() {
            int width = Math.min(440, this.width - 20), left = (this.width - width) / 2, top = 40;
            grid.build(this::addRenderableWidget, left, top, width, this::rebuildWidgets);
            int y = top + grid.height() + 8;
            addRenderableWidget(Button.builder(Component.literal("All"), ignored -> {
                grid.selected.addAll(grid.all());
                rebuildWidgets();
            }).bounds(left, y, 60, 20).build());
            addRenderableWidget(Button.builder(Component.literal("None"), ignored -> {
                grid.selected.clear();
                rebuildWidgets();
            }).bounds(left + 64, y, 60, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Save"), ignored -> {
                boolean all = grid.selected.containsAll(grid.all());
                ekuzo.crazyworldprogression.client.CwpClientPayloads.send(new StorageNetworking.SetFilterPayload(pos, all ? "" : String.join(",", grid.selected)));
                onClose();
            }).bounds(left + width - 140, y, 66, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose()).bounds(left + width - 70, y, 66, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            PanelStyle style = PanelStyle.get();
            style.backdrop(graphics, width, height);
            int panelWidth = Math.min(440, this.width - 20);
            style.window(graphics, (width - panelWidth) / 2 - 8, 6, panelWidth + 16, 40 + grid.height() + 40);
            graphics.centeredText(font, title, width / 2, 14, style.title());
            graphics.centeredText(font, Component.literal("Only ticked item types can go into this chest (players, hoppers and quicksort)"),
                    width / 2, 26, style.muted());
            PanelStyle.styling(true);
            try {
                super.extractRenderState(graphics, mouseX, mouseY, delta);
            } finally {
                PanelStyle.styling(false);
            }
            grid.icons(graphics);
        }

        @Override
        public boolean isPauseScreen() { return false; }
    }

    /** The quicksort window: item types, the mode, and the on/off switch; every change is saved right away. */
    static final class QuicksortScreen extends Screen {
        private CategoryGrid grid;
        private boolean fill, on;

        QuicksortScreen(StorageNetworking.QuicksortPayload payload) {
            super(Component.literal("Quicksort"));
            update(payload);
        }

        void update(StorageNetworking.QuicksortPayload payload) {
            grid = new CategoryGrid(categories(payload.categories()));
            fill = payload.fill();
            on = payload.on();
            if (width > 0) rebuildWidgets();
        }

        private void save() {
            ekuzo.crazyworldprogression.client.CwpClientPayloads.send(new StorageNetworking.QuicksortPayload(String.join(",", grid.selected), fill, on, false));
        }

        @Override
        protected void init() {
            int width = Math.min(440, this.width - 20), left = (this.width - width) / 2, top = 40;
            grid.build(this::addRenderableWidget, left, top, width, () -> {
                save();
                rebuildWidgets();
            });
            int y = top + grid.height() + 8;
            Button mode = addRenderableWidget(Button.builder(Component.literal(fill ? "Mode: fill filtered chests" : "Mode: stack only"), ignored -> {
                fill = !fill;
                save();
            }).bounds(left, y, 170, 20).build());
            mode.setTooltip(Tooltip.create(Component.literal(fill
                    ? "Puts every item of the ticked types into chests whose filter takes it (never into unfiltered chests)"
                    : "Only adds items to chests that already hold the same item")));
            Button toggle = addRenderableWidget(Button.builder(Component.literal(on ? "Auto: ON" : "Auto: OFF"), ignored -> {
                on = !on;
                save();
            }).bounds(left + 174, y, 100, 20).build());
            toggle.setTooltip(Tooltip.create(Component.literal("While on, once a second your inventory (not the hotbar) is sorted into chests within 5 blocks. Turn it off when you are done.")));
            addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose()).bounds(left + width - 70, y, 66, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            PanelStyle style = PanelStyle.get();
            style.backdrop(graphics, width, height);
            int panelWidth = Math.min(440, this.width - 20);
            style.window(graphics, (width - panelWidth) / 2 - 8, 6, panelWidth + 16, 40 + grid.height() + 40);
            graphics.centeredText(font, title, width / 2, 14, style.title());
            graphics.centeredText(font, Component.literal("Tick the item types to put away into nearby chests"), width / 2, 26, style.muted());
            PanelStyle.styling(true);
            try {
                super.extractRenderState(graphics, mouseX, mouseY, delta);
            } finally {
                PanelStyle.styling(false);
            }
            grid.icons(graphics);
        }

        @Override
        public boolean isPauseScreen() { return false; }
    }
}
