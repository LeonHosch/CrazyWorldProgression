package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.portals.PortalNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Small pre-entry party screen; the server validates every action. */
public final class PortalGroupClient {
    private PortalGroupClient() { }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(PortalNetworking.PortalGroupPayload.TYPE,
                (payload, context) -> context.client().gui.setScreen(new GroupScreen(payload)));
    }

    private static final class GroupScreen extends Screen {
        private final PortalNetworking.PortalGroupPayload state;
        private EditBox playerName;
        private int left;
        private int top;

        private GroupScreen(PortalNetworking.PortalGroupPayload state) {
            super(Component.literal("Portal group"));
            this.state = state;
        }

        @Override protected void init() {
            left = (width - 320) / 2;
            top = (height - 230) / 2;
            playerName = new EditBox(font, left + 16, top + 132, 196, 20,
                    Component.literal("Player name"));
            playerName.setMaxLength(16);
            addRenderableWidget(playerName);
            addRenderableWidget(Button.builder(Component.literal("Invite"), button -> send("invite", playerName.getValue()))
                    .bounds(left + 220, top + 132, 84, 20).build());
            if (!state.invitedBy().isBlank()) {
                addRenderableWidget(Button.builder(Component.literal("Accept " + state.invitedBy()),
                        button -> send("accept", state.invitedBy()))
                        .bounds(left + 16, top + 160, 145, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Leave group"), button -> send("leave", ""))
                    .bounds(left + 16, top + 194, 130, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(left + 174, top + 194, 130, 20).build());
        }

        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xA0000000);
            graphics.fill(left, top, left + 320, top + 230, 0xFF232832);
            graphics.fill(left + 2, top + 2, left + 318, top + 228, 0xFF11151D);
            graphics.text(font, Component.literal("Portal group"), left + 16, top + 16, 0xFFE6DFFF, false);
            drawWrapped(graphics, "Members: " + state.members(), top + 42, 0xFFD8D8D8);
            drawWrapped(graphics, "Online: " + state.online(), top + 70, 0xFFAAAAAA);
            graphics.text(font, Component.literal("Enter together at a private portal. Leader confirms travel."),
                    left + 16, top + 112, 0xFFBBBBBB, false);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        private void drawWrapped(GuiGraphicsExtractor graphics, String value, int y, int color) {
            int line = 0;
            for (FormattedCharSequence text : font.split(Component.literal(value), 288)) {
                if (line >= 2) break;
                graphics.text(font, text, left + 16, y + line * 10, color, false);
                line++;
            }
        }

        private void send(String action, String player) {
            if (ClientPlayNetworking.canSend(PortalNetworking.PortalGroupActionPayload.TYPE)) {
                ClientPlayNetworking.send(new PortalNetworking.PortalGroupActionPayload(action, player));
            }
        }

        @Override public boolean isPauseScreen() { return false; }
    }
}
