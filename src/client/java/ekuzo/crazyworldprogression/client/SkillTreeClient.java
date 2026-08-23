package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeNetworking;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SkillTreeClient {
    private static final int INVENTORY_BUTTON_WIDTH = 20;
    private static final int INVENTORY_BUTTON_HEIGHT = 18;
    private static final int INVENTORY_BUTTON_GAP = 2;
    private static final WidgetSprites INVENTORY_BUTTON_SPRITES = new WidgetSprites(
            CrazyWorldProgression.id("skill_tree/button"),
            CrazyWorldProgression.id("skill_tree/button"),
            CrazyWorldProgression.id("skill_tree/button_highlighted")
    );

    // Prevent this static client integration from being instantiated.
    private SkillTreeClient() {
    }

    // Register the snapshot receiver and add a skill-tree button to the right of the recipe-book button.
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(SkillTreeNetworking.SkillTreeSnapshotPayload.TYPE,
                (payload, context) -> showSnapshot(
                        context.client(),
                        SkillTreeNetworking.decodeSnapshot(payload.json())
                ));

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof InventoryScreen)) {
                return;
            }
            ImageButton recipeBookButton = findRecipeBookButton(screen);
            if (recipeBookButton == null) {
                return;
            }
            SkillTreeInventoryButton skillTreeButton = new SkillTreeInventoryButton(
                    recipeBookButton.getRight() + INVENTORY_BUTTON_GAP,
                    recipeBookButton.getY()
            );
            Screens.getWidgets(screen).add(skillTreeButton);
            ScreenEvents.beforeExtract(screen).register((ignored, graphics, mouseX, mouseY, delta) ->
                    positionBesideRecipeBook(skillTreeButton, recipeBookButton));
        });
    }

    // Find the vanilla 20-by-18 recipe-book image button created before this callback runs.
    private static ImageButton findRecipeBookButton(Screen screen) {
        return Screens.getWidgets(screen).stream()
                .filter(ImageButton.class::isInstance)
                .map(ImageButton.class::cast)
                .filter(button -> button.getWidth() == INVENTORY_BUTTON_WIDTH)
                .filter(button -> button.getHeight() == INVENTORY_BUTTON_HEIGHT)
                .findFirst()
                .orElse(null);
    }

    // Follow the live recipe-button position when opening or closing the recipe panel.
    private static void positionBesideRecipeBook(Button skillTreeButton, ImageButton recipeBookButton) {
        skillTreeButton.setPosition(
                recipeBookButton.getRight() + INVENTORY_BUTTON_GAP,
                recipeBookButton.getY()
        );
    }

    // Ask the server for authoritative definitions, balances, and unlock state.
    public static void requestOpen() {
        if (ClientPlayNetworking.canSend(SkillTreeNetworking.RequestSkillTreesPayload.TYPE)) {
            ClientPlayNetworking.send(new SkillTreeNetworking.RequestSkillTreesPayload());
        }
    }

    // Open a new screen or refresh the already-open screen after a purchase.
    private static void showSnapshot(Minecraft client, SkillTreeService.SkillTreeSnapshot snapshot) {
        Screen current = client.gui.screen();
        if (current instanceof SkillTreeScreen skillTreeScreen) {
            skillTreeScreen.updateSnapshot(snapshot);
            return;
        }
        // A network callback already runs inside the current frame; forcing another render here would blur twice.
        client.gui.setScreen(new SkillTreeScreen(current, snapshot));
    }

    private static final class SkillTreeInventoryButton extends Button {
        private final ItemStack icon;

        // Create a recipe-sized vanilla button carrying an experience-themed item icon.
        private SkillTreeInventoryButton(int x, int y) {
            super(
                    x,
                    y,
                    INVENTORY_BUTTON_WIDTH,
                    INVENTORY_BUTTON_HEIGHT,
                    Component.translatable("tooltip.crazy-world-progression.open_skill_trees"),
                    ignored -> requestOpen(),
                    DEFAULT_NARRATION
            );
            icon = new ItemStack(Items.EXPERIENCE_BOTTLE);
            setTooltip(Tooltip.create(Component.translatable("tooltip.crazy-world-progression.open_skill_trees")));
        }

        // Draw the experience bottle centrally instead of rendering a text label.
        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    INVENTORY_BUTTON_SPRITES.get(isActive(), isHoveredOrFocused()),
                    getX(),
                    getY(),
                    getWidth(),
                    getHeight()
            );
            graphics.item(icon, getX() + 2, getY() + 1);
        }

    }
}
