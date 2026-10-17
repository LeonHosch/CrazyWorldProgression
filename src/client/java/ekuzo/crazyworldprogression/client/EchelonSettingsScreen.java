package ekuzo.crazyworldprogression.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Immediate, locally saved client options. */
public final class EchelonSettingsScreen extends Screen {
    private final Screen parent;

    public EchelonSettingsScreen(Screen parent) {
        super(Component.literal("ECHELON Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = (width - 310) / 2;
        int top = Math.max(20, (height - 224) / 2);
        option(left, top, "Protect instance hotbar", EchelonClientSettings.protectHotbar, () -> {
            EchelonClientSettings.protectHotbar = !EchelonClientSettings.protectHotbar;
        });
        option(left, top + 24, "Coordinate portal menu", EchelonClientSettings.coordinatePortalMenu, () -> {
            EchelonClientSettings.coordinatePortalMenu = !EchelonClientSettings.coordinatePortalMenu;
        });
        option(left, top + 48, "Exit portal menu", EchelonClientSettings.exitPortalMenu, () -> {
            EchelonClientSettings.exitPortalMenu = !EchelonClientSettings.exitPortalMenu;
        });
        option(left, top + 72, "Portal particles", EchelonClientSettings.portalParticles, () -> {
            EchelonClientSettings.portalParticles = !EchelonClientSettings.portalParticles;
        });
        option(left, top + 96, "Editor placement previews", EchelonClientSettings.editorPlacementPreviews, () -> {
            EchelonClientSettings.editorPlacementPreviews = !EchelonClientSettings.editorPlacementPreviews;
        });
        option(left, top + 120, "Show titles", EchelonClientSettings.showTitles, () -> {
            EchelonClientSettings.showTitles = !EchelonClientSettings.showTitles;
        });
        option(left, top + 144, "Show overhead player names", EchelonClientSettings.showPlayerNames, () -> {
            EchelonClientSettings.showPlayerNames = !EchelonClientSettings.showPlayerNames;
        });
        option(left, top + 168, "Show players in semi private instances",
                EchelonClientSettings.showPlayersInSemiPrivateInstances, () -> {
                    EchelonClientSettings.showPlayersInSemiPrivateInstances =
                            !EchelonClientSettings.showPlayersInSemiPrivateInstances;
                });
        addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
                .bounds((width - 150) / 2, top + 198, 150, 20).build());
    }

    private void option(int left, int top, String label, boolean value, Runnable toggle) {
        addRenderableWidget(Button.builder(Component.literal(label + ": " + (value ? "ON" : "OFF")), button -> {
            toggle.run();
            boolean current = switch (label) {
                case "Protect instance hotbar" -> EchelonClientSettings.protectHotbar;
                case "Coordinate portal menu" -> EchelonClientSettings.coordinatePortalMenu;
                case "Exit portal menu" -> EchelonClientSettings.exitPortalMenu;
                case "Portal particles" -> EchelonClientSettings.portalParticles;
                case "Editor placement previews" -> EchelonClientSettings.editorPlacementPreviews;
                case "Show titles" -> EchelonClientSettings.showTitles;
                case "Show overhead player names" -> EchelonClientSettings.showPlayerNames;
                case "Show players in semi private instances" -> EchelonClientSettings.showPlayersInSemiPrivateInstances;
                default -> EchelonClientSettings.editorPlacementPreviews;
            };
            button.setMessage(Component.literal(label + ": " + (current ? "ON" : "OFF")));
            EchelonClientSettings.save();
        }).bounds(left, top, 310, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.text(font, title, (width - font.width(title)) / 2, Math.max(8, (height - 224) / 2 - 17),
                0xFFFFFFFF, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
