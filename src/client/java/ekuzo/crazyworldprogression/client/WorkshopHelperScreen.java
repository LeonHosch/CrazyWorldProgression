package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.portals.WorkshopHelpers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Designer menu inside a workshop: which helper categories show, labels, and jumping between helpers. */
public final class WorkshopHelperScreen extends Screen {
    private static final String KEY = "screen.crazy-world-progression.workshop.";
    private static final int COLUMN = 150, ROW = 22;
    private final Screen parent;

    public WorkshopHelperScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<String> categories = WorkshopHelperClient.categories();
        int rows = (categories.size() + 1) / 2;
        int left = width / 2 - COLUMN - 2;
        int top = Math.max(28, height / 2 - (rows + 5) * ROW / 2);
        for (int index = 0; index < categories.size(); index++) {
            String category = categories.get(index);
            int x = left + index % 2 * (COLUMN + 4), y = top + index / 2 * ROW;
            addRenderableWidget(Button.builder(categoryLabel(category), button -> {
                WorkshopHelperClient.setShown(category, !WorkshopHelperClient.shown(category));
                button.setMessage(categoryLabel(category));
            }).bounds(x, y, COLUMN, 20).build());
        }
        int y = top + rows * ROW + 6;
        int third = (COLUMN * 2 + 4 - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "all"), ignored -> {
            WorkshopHelperClient.setAll(true);
            rebuildWidgets();
        }).bounds(left, y, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "none"), ignored -> {
            WorkshopHelperClient.setAll(false);
            rebuildWidgets();
        }).bounds(left + third + 4, y, third, 20).build());
        addRenderableWidget(Button.builder(labelsLabel(), button -> {
            WorkshopHelperClient.setLabels(!WorkshopHelperClient.labels());
            button.setMessage(labelsLabel());
        }).bounds(left + 2 * (third + 4), y, third, 20).build());
        y += ROW;
        int half = (COLUMN * 2 + 4 - 4) / 2;
        Button move = Button.builder(moveLabel(), button -> {
            WorkshopHelperClient.setMoveMode(!WorkshopHelperClient.moveMode());
            rebuildWidgets();
        }).bounds(left, y, half, 20).build();
        move.setTooltip(Tooltip.create(Component.translatable(KEY + "move.tooltip")));
        addRenderableWidget(move);
        Button clear = Button.builder(Component.translatable(KEY + "clear_selection", WorkshopHelperClient.selectedCount()),
                ignored -> {
                    WorkshopHelperClient.clearSelection();
                    rebuildWidgets();
                }).bounds(left + half + 4, y, half, 20).build();
        clear.active = WorkshopHelperClient.selectedCount() > 0;
        addRenderableWidget(clear);
        y += ROW;
        int quarter = (COLUMN * 2 + 4 - 12) / 4;
        String[] actions = {"previous", "next", "nearest", "list"};
        for (int index = 0; index < actions.length; index++) {
            String action = actions[index];
            Button button = Button.builder(Component.translatable(KEY + action), ignored -> {
                WorkshopHelperClient.request(action);
                if (!action.equals("nearest")) minecraft.gui.setScreen(null);
            }).bounds(left + index * (quarter + 4), y, quarter, 20).build();
            button.setTooltip(Tooltip.create(Component.translatable(KEY + action + ".tooltip")));
            addRenderableWidget(button);
        }
        y += ROW + 6;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(width / 2 - 75, y, 150, 20).build());
    }

    private static Component categoryLabel(String category) {
        long count = WorkshopHelperClient.helpers().stream().filter(helper -> helper.category().equals(category)).count();
        boolean shown = WorkshopHelperClient.shown(category);
        return Component.translatable(KEY + "category." + category).append(" (" + count + "): ")
                .append(Component.translatable(shown ? "options.on" : "options.off"));
    }

    private static Component moveLabel() {
        return Component.translatable(KEY + "move").append(": ")
                .append(Component.translatable(WorkshopHelperClient.moveMode() ? "options.on" : "options.off"));
    }

    private static Component labelsLabel() {
        return Component.translatable(KEY + "labels").append(": ")
                .append(Component.translatable(WorkshopHelperClient.labels() ? "options.on" : "options.off"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int rows = (WorkshopHelperClient.categories().size() + 1) / 2;
        int top = Math.max(28, height / 2 - (rows + 5) * ROW / 2);
        graphics.centeredText(font, title, width / 2, top - 22, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable(KEY + "hint", WorkshopHelperClient.helpers().size()),
                width / 2, top - 11, 0xFFA8B0C0);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
