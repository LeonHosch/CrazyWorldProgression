package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.portals.PortalNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Opens a framed Minecraft-style destination screen before a server-authorized portal journey. */
public final class PortalEntryPromptClient {
    // Prevent construction of the static client prompt bridge.
    private PortalEntryPromptClient() {
    }

    // Register the clientbound travel prompt receiver.
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(PortalNetworking.PortalEntryPromptPayload.TYPE,
                (payload, context) -> open(context.client(), payload));
    }

    // Open the dedicated Minecraft-style portal confirmation screen.
    private static void open(Minecraft client, PortalNetworking.PortalEntryPromptPayload payload) {
        client.gui.setScreen(new PortalConfirmationScreen(client.gui.screen(), payload));
    }

    // Format a positive second count as the requested total-hours and minutes instance label.
    private static String formatDuration(long totalSeconds) {
        long seconds = Math.max(0L, totalSeconds);
        long hours = seconds / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        if (hours == 0L && minutes == 0L && seconds > 0L) minutes = 1L;
        return hours + "h " + minutes + "m";
    }

    /** Compact framed confirmation screen styled with vanilla widgets and difficulty colors. */
    private static final class PortalConfirmationScreen extends Screen {
        private static final int MAX_PANEL_WIDTH = 390;
        private static final int PANEL_PADDING = 22;
        private static final int BUTTON_WIDTH = 104;
        private final Screen parent;
        private final PortalNetworking.PortalEntryPromptPayload payload;
        private List<FormattedCharSequence> descriptionLines = List.of();
        private int panelLeft;
        private int panelTop;
        private int panelWidth;
        private int panelHeight;
        private boolean answered;

        // Store the server-issued destination data until the player makes one decision.
        private PortalConfirmationScreen(Screen parent, PortalNetworking.PortalEntryPromptPayload payload) {
            super(Component.literal("Portal travel confirmation"));
            this.parent = parent;
            this.payload = payload;
        }

        // Measure wrapped content and place two ordinary Minecraft buttons inside the framed panel.
        @Override
        protected void init() {
            panelWidth = Math.min(MAX_PANEL_WIDTH, width - 32);
            descriptionLines = font.split(Component.literal(description()), panelWidth - PANEL_PADDING * 2)
                    .stream().limit(7).toList();
            panelHeight = Math.max(payload.accessMode().equals("private") ? 216 : 180,
                    142 + descriptionLines.size() * 10 + (payload.accessMode().equals("private") ? 36 : 0));
            panelLeft = (width - panelWidth) / 2;
            panelTop = (height - panelHeight) / 2;
            int buttonY = panelTop + panelHeight - 34;
            int gap = 10;
            int firstX = panelLeft + (panelWidth - BUTTON_WIDTH * 2 - gap) / 2;
            addRenderableWidget(Button.builder(Component.literal("Travel"), ignored -> answer(true))
                    .bounds(firstX, buttonY, BUTTON_WIDTH, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), ignored -> answer(false))
                    .bounds(firstX + BUTTON_WIDTH + gap, buttonY, BUTTON_WIDTH, 20).build());
            if (payload.accessMode().equals("private")) {
                addRenderableWidget(Button.builder(Component.literal("Manage group"), ignored -> {
                    answer(false);
                    if (ClientPlayNetworking.canSend(PortalNetworking.PortalGroupActionPayload.TYPE)) {
                        ClientPlayNetworking.send(new PortalNetworking.PortalGroupActionPayload("open", ""));
                    }
                }).bounds(panelLeft + (panelWidth - 130) / 2, buttonY - 27, 130, 20).build());
            }
        }

        // Draw a dark world overlay, shadowed panel, colored border, and requested information hierarchy.
        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int difficultyColor = difficultyColor(payload.difficulty());
            graphics.fill(0, 0, width, height, 0xA0000000);
            graphics.fill(panelLeft + 5, panelTop + 5, panelLeft + panelWidth + 5,
                    panelTop + panelHeight + 5, 0x90000000);
            graphics.fill(panelLeft - 2, panelTop - 2, panelLeft + panelWidth + 2,
                    panelTop + panelHeight + 2, 0xFF08090C);
            graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, difficultyColor);
            graphics.fill(panelLeft + 3, panelTop + 3, panelLeft + panelWidth - 3,
                    panelTop + panelHeight - 3, 0xF0181B22);
            graphics.fill(panelLeft + 7, panelTop + 7, panelLeft + panelWidth - 7,
                    panelTop + panelHeight - 7, 0xE00C0F14);

            int textX = panelLeft + PANEL_PADDING;
            int y = panelTop + 20;
            graphics.text(font, Component.literal("Travel to \"" + payload.displayName() + "\""),
                    textX, y, difficultyColor, false);
            y += 20;
            for (FormattedCharSequence line : descriptionLines) {
                graphics.text(font, line, textX, y, 0xFFD6D9DF, false);
                y += 10;
            }
            y += 10;
            String skulls = "☠ ".repeat(Math.max(1, Math.min(5, payload.difficulty()))).trim();
            graphics.text(font, Component.literal("Difficulty: " + skulls), textX, y, difficultyColor, false);
            y += 14;
            String time = "Instance time: " + formatDuration(payload.secondsRemaining())
                    + (payload.existingInstance() ? " (already running)" : "");
            graphics.text(font, Component.literal(time), textX, y, 0xFFE7E7E7, false);
            if (payload.accessMode().equals("private")) {
                graphics.text(font, Component.literal("Private group · max " + payload.maxPlayers()
                        + " players · entry closes when travel starts"), textX, y + 15, 0xFFD6D9DF, false);
            } else {
                graphics.text(font, Component.literal("Public · shared instance"), textX, y + 15,
                        0xFFD6D9DF, false);
            }
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        // Treat Escape as a deliberate cancellation and send exactly one response.
        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                answer(false);
                return true;
            }
            return super.keyPressed(event);
        }

        // Cancel pending travel when another client action closes this screen.
        @Override
        public void onClose() {
            answer(false);
        }

        // Keep the integrated server and remaining-instance clock running while this decision is visible.
        @Override
        public boolean isPauseScreen() {
            return false;
        }

        // Send one accepted or rejected decision and restore the previously open screen.
        private void answer(boolean accepted) {
            if (answered) return;
            answered = true;
            minecraft.gui.setScreen(parent);
            if (ClientPlayNetworking.canSend(PortalNetworking.PortalEntryResponsePayload.TYPE)) {
                ClientPlayNetworking.send(new PortalNetworking.PortalEntryResponsePayload(
                        payload.portalId(), accepted));
            }
        }

        // Supply neutral fallback copy without shifting the requested content layout.
        private String description() {
            return payload.description().isBlank()
                    ? "No description has been provided for this destination."
                    : payload.description();
        }

        // Map one-to-five skulls from safe green through yellow and orange to dangerous red-purple.
        private static int difficultyColor(int difficulty) {
            return switch (Math.max(1, Math.min(5, difficulty))) {
                case 1 -> 0xFF55FF55;
                case 2 -> 0xFFFFFF55;
                case 3 -> 0xFFFFAA00;
                case 4 -> 0xFFFF5555;
                default -> 0xFFCC55FF;
            };
        }
    }
}
