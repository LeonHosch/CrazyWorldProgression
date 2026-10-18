package ekuzo.crazyworldprogression.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "You have unsaved changes": Save and leave, Leave without saving, or Cancel. Drawn over the admin panel. */
final class UnsavedChangesScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int HEIGHT = 92;
    private final Screen parent;
    private final Runnable save;
    private final Runnable discard;

    /** save may be null when the page has no Save button; then only the other two choices are offered. */
    UnsavedChangesScreen(Screen parent, Runnable save, Runnable discard) {
        super(Component.literal("Unsaved changes"));
        this.parent = parent;
        this.save = save;
        this.discard = discard;
    }

    @Override
    protected void init() {
        int x = (width - WIDTH) / 2;
        int y = (height - HEIGHT) / 2 + HEIGHT - 28;
        int buttons = save == null ? 2 : 3;
        int buttonWidth = (WIDTH - 16 - (buttons - 1) * 4) / buttons;
        int bx = x + 8;
        if (save != null) {
            addRenderableWidget(Button.builder(Component.literal("Save and leave"), ignored -> {
                minecraft.gui.setScreen(parent);
                save.run();
            }).bounds(bx, y, buttonWidth, 20).build());
            bx += buttonWidth + 4;
        }
        addRenderableWidget(Button.builder(Component.literal("Leave without saving"), ignored -> {
            minecraft.gui.setScreen(parent);
            discard.run();
        }).bounds(bx, y, buttonWidth, 20).build());
        bx += buttonWidth + 4;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> onClose())
                .bounds(bx, y, buttonWidth, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // The admin panel stays visible behind the dialog.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        parent.extractRenderState(graphics, -1, -1, delta);
        graphics.fill(0, 0, width, height, 0x88000000);
        int x = (width - WIDTH) / 2;
        int y = (height - HEIGHT) / 2;
        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF7C5CDA);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF151920);
        graphics.text(font, Component.literal("You have unsaved changes"), x + 10, y + 10, 0xFFF2F2F5, false);
        graphics.text(font, Component.literal(save == null ? "Leave this page anyway?"
                : "Save them before leaving this page?"), x + 10, y + 26, 0xFF9CA3AF, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent.isPauseScreen();
    }
}
