package ekuzo.crazyworldprogression.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact, entirely local coordinate entry reached from the pause menu. */
public final class WaypointScreen extends Screen {
    private final Screen parent;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private String error = "";

    public WaypointScreen(Screen parent) {
        super(Component.translatable("screen.crazy-world-progression.waypoint.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - 102;
        int top = height / 2 - 58;
        WaypointClient.Waypoint current = WaypointClient.active();
        xBox = box(left, top + 28, "X", current == null ? "" : Double.toString(current.x()));
        yBox = box(left + 70, top + 28, "Y", current == null ? "" : Double.toString(current.y()));
        zBox = box(left + 140, top + 28, "Z", current == null ? "" : Double.toString(current.z()));
        addRenderableWidget(Button.builder(Component.translatable("screen.crazy-world-progression.waypoint.start"),
                ignored -> start()).bounds(left, top + 56, 204, 20).build());
        Button stop = Button.builder(Component.translatable("screen.crazy-world-progression.waypoint.stop"),
                ignored -> { WaypointClient.clear(); onClose(); }).bounds(left, top + 80, 99, 20).build();
        stop.active = current != null;
        addRenderableWidget(stop);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), ignored -> onClose())
                .bounds(left + 105, top + 80, 99, 20).build());
        setInitialFocus(xBox);
    }

    private EditBox box(int x, int y, String axis, String value) {
        EditBox box = new EditBox(font, x, y, 64, 20, Component.literal(axis));
        box.setMaxLength(24);
        box.setHint(Component.literal(axis));
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private void start() {
        if (minecraft.level == null) return;
        try {
            double x = Double.parseDouble(xBox.getValue().trim());
            double y = Double.parseDouble(yBox.getValue().trim());
            double z = Double.parseDouble(zBox.getValue().trim());
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new NumberFormatException();
            WaypointClient.set(minecraft.level.dimension().identifier(), x, y, z);
            onClose();
        } catch (NumberFormatException exception) {
            error = Component.translatable("screen.crazy-world-progression.waypoint.invalid").getString();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int top = height / 2 - 58;
        graphics.centeredText(font, title, width / 2, top, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("screen.crazy-world-progression.waypoint.hint"),
                width / 2, top + 13, 0xFFA8B0C0);
        WaypointClient.Waypoint current = WaypointClient.active();
        if (current != null) graphics.centeredText(font, Component.literal(WaypointClient.coordinates(current)),
                width / 2, top - 17, 0xFFFFD34D);
        if (!error.isEmpty()) graphics.centeredText(font, Component.literal(error), width / 2, top + 107, 0xFFFF7777);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
