package ekuzo.crazyworldprogression.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.portals.Parties;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Party screen: your party on the left, the party browser on the right. The server checks every action. */
public final class PartyScreen extends Screen {
    private static final int PANEL_WIDTH = 460;
    private static final int PANEL_HEIGHT = 262;
    private static final int ROW = 20;
    private static KeyMapping openKey;

    private final Screen parent;
    private JsonObject state;
    private int left;
    private int top;
    private int browserScroll;
    private String draftName = "";
    private String draftInvite = "";
    private String draftVisibility = "PRIVATE";
    private EditBox nameBox;
    private EditBox inviteBox;
    private final List<Label> labels = new ArrayList<>();

    private record Label(String text, int x, int y, int color) { }

    private PartyScreen(Screen parent, JsonObject state) {
        super(Component.literal("Party"));
        this.parent = parent;
        this.state = state;
    }

    public static void initialize() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.crazy-world-progression.open_party",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("crazy-world-progression", "party"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) if (client.player != null && client.gui.screen() == null) requestOpen();
        });
        ClientPlayNetworking.registerGlobalReceiver(Parties.StatePayload.TYPE, (payload, context) -> {
            JsonObject received = JsonParser.parseString(payload.json()).getAsJsonObject();
            Minecraft client = context.client();
            if (client.gui.screen() instanceof PartyScreen screen) screen.update(received);
            else if (payload.open()) client.gui.setScreen(new PartyScreen(client.gui.screen(), received));
        });
    }

    public static void requestOpen() {
        send("open", "", "");
    }

    private static void send(String action, String a, String b) {
        if (ClientPlayNetworking.canSend(Parties.ActionPayload.TYPE))
            ClientPlayNetworking.send(new Parties.ActionPayload(action, a, b));
    }

    private void update(JsonObject received) {
        if (nameBox != null) draftName = nameBox.getValue();
        if (inviteBox != null) draftInvite = inviteBox.getValue();
        state = received;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        labels.clear();
        int panelWidth = Math.min(PANEL_WIDTH, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(8, (height - PANEL_HEIGHT) / 2);
        int column = (panelWidth - 30) / 2;
        int myX = left + 10;
        int browserX = left + 20 + column;
        buildMyParty(myX, top + 26, column);
        buildBrowser(browserX, top + 26, column);
        addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
                .bounds(left + panelWidth - 90, top + PANEL_HEIGHT - 28, 80, 20).build());
    }

    private void buildMyParty(int x, int y, int width) {
        JsonObject party = state.has("party") ? state.getAsJsonObject("party") : null;
        if (party == null) {
            labels.add(new Label("You are not in a party", x, y, 0xFFE6DFFF));
            y += 14;
            nameBox = new EditBox(font, x, y, width - 4, 18, Component.literal("Party name"));
            nameBox.setMaxLength(24);
            nameBox.setHint(Component.literal("Party name"));
            nameBox.setValue(draftName);
            addRenderableWidget(nameBox);
            y += ROW + 2;
            addRenderableWidget(Button.builder(Component.literal("Visibility: " + label(draftVisibility)), button -> {
                draftVisibility = next(draftVisibility);
                draftName = nameBox.getValue();
                rebuildWidgets();
            }).bounds(x, y, width - 4, 18).tooltip(Tooltip.create(Component.literal(
                    "Public: anyone can join from the browser. Semi-private: players ask to join. Private: invite only.")))
                    .build());
            y += ROW + 2;
            addRenderableWidget(Button.builder(Component.literal("Create party"),
                    ignored -> send("create", nameBox.getValue(), draftVisibility)).bounds(x, y, width - 4, 20).build());
            y += ROW + 10;
            JsonArray invites = state.getAsJsonArray("invites");
            if (!invites.isEmpty()) labels.add(new Label("Invites", x, y, 0xFFE0B040));
            y += 12;
            for (JsonElement element : invites) {
                JsonObject invite = element.getAsJsonObject();
                String id = invite.get("id").getAsString();
                labels.add(new Label(trim(invite.get("name").getAsString() + " (" + invite.get("leader").getAsString() + ")",
                        width - 92), x, y + 5, 0xFFD8D8D8));
                addRenderableWidget(Button.builder(Component.literal("Accept"), ignored -> send("accept_invite", id, ""))
                        .bounds(x + width - 90, y, 44, 18).build());
                addRenderableWidget(Button.builder(Component.literal("No"), ignored -> send("decline_invite", id, ""))
                        .bounds(x + width - 44, y, 40, 18).build());
                y += ROW;
                if (y > top + PANEL_HEIGHT - 50) break;
            }
            return;
        }
        boolean leader = party.get("leader").getAsBoolean();
        String visibility = party.get("visibility").getAsString();
        labels.add(new Label(trim(party.get("name").getAsString(), width - 4), x, y, 0xFFE6DFFF));
        String activity = party.get("activity").getAsString();
        labels.add(new Label(label(visibility) + (activity.isEmpty() ? "" : " · in " + activity), x, y + 11, 0xFF9CA3AF));
        y += 24;
        if (leader) {
            nameBox = new EditBox(font, x, y, width - 58, 18, Component.literal("Party name"));
            nameBox.setMaxLength(24);
            nameBox.setValue(draftName.isEmpty() ? party.get("name").getAsString() : draftName);
            addRenderableWidget(nameBox);
            addRenderableWidget(Button.builder(Component.literal("Rename"), ignored -> {
                draftName = "";
                send("rename", nameBox.getValue(), "");
            }).bounds(x + width - 56, y, 52, 18).build());
            y += ROW;
            addRenderableWidget(Button.builder(Component.literal(label(visibility)),
                    ignored -> send("visibility", next(visibility), "")).bounds(x, y, width - 92, 18).build());
            int max = party.get("max").getAsInt();
            addRenderableWidget(Button.builder(Component.literal("-"), ignored -> send("max_size", Integer.toString(max - 1), ""))
                    .bounds(x + width - 88, y, 18, 18).build());
            labels.add(new Label("Max " + max, x + width - 66, y + 5, 0xFFD8D8D8));
            addRenderableWidget(Button.builder(Component.literal("+"), ignored -> send("max_size", Integer.toString(max + 1), ""))
                    .bounds(x + width - 22, y, 18, 18).build());
            y += ROW + 2;
        }
        JsonArray members = party.getAsJsonArray("members");
        labels.add(new Label("Members " + members.size() + "/" + party.get("max").getAsInt(), x, y, 0xFFE0B040));
        y += 12;
        for (JsonElement element : members) {
            JsonObject member = element.getAsJsonObject();
            String id = member.get("id").getAsString();
            boolean isLeader = member.get("leader").getAsBoolean();
            boolean online = member.get("online").getAsBoolean();
            labels.add(new Label((isLeader ? "★ " : "  ") + member.get("name").getAsString(), x, y + 4,
                    online ? 0xFFE8E8E8 : 0xFF7A7F88));
            if (leader && !isLeader) {
                addRenderableWidget(Button.builder(Component.literal("Lead"), ignored -> send("promote", id, ""))
                        .bounds(x + width - 82, y, 38, 16).tooltip(Tooltip.create(Component.literal("Make party leader"))).build());
                addRenderableWidget(Button.builder(Component.literal("Kick"), ignored -> send("kick", id, ""))
                        .bounds(x + width - 42, y, 38, 16).build());
            }
            y += 17;
        }
        JsonArray requests = party.getAsJsonArray("requests");
        if (leader && !requests.isEmpty()) {
            y += 2;
            labels.add(new Label("Join requests", x, y, 0xFFE0B040));
            y += 12;
            for (JsonElement element : requests) {
                JsonObject request = element.getAsJsonObject();
                String id = request.get("id").getAsString();
                labels.add(new Label(request.get("name").getAsString(), x, y + 4, 0xFFE8E8E8));
                addRenderableWidget(Button.builder(Component.literal("Accept"), ignored -> send("accept_request", id, ""))
                        .bounds(x + width - 90, y, 46, 16).build());
                addRenderableWidget(Button.builder(Component.literal("Deny"), ignored -> send("deny_request", id, ""))
                        .bounds(x + width - 42, y, 38, 16).build());
                y += 17;
            }
        }
        int bottom = top + PANEL_HEIGHT - 28;
        if (leader) {
            inviteBox = new EditBox(font, x, bottom - 24, width - 58, 18, Component.literal("Player name"));
            inviteBox.setMaxLength(16);
            inviteBox.setHint(Component.literal("Invite player"));
            inviteBox.setValue(draftInvite);
            inviteBox.setResponder(this::suggestName);
            suggestName(inviteBox.getValue());
            addRenderableWidget(inviteBox);
            addRenderableWidget(Button.builder(Component.literal("Invite"), ignored -> {
                draftInvite = "";
                send("invite", inviteBox.getValue().trim(), "");
            }).bounds(x + width - 56, bottom - 24, 52, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Leave party"), ignored -> send("leave", "", ""))
                .bounds(x, bottom, leader ? (width - 8) / 2 : width - 4, 20).build());
        if (leader) addRenderableWidget(Button.builder(Component.literal("Disband"), ignored -> send("disband", "", ""))
                .bounds(x + (width - 8) / 2 + 4, bottom, (width - 8) / 2, 20).build());
    }

    private void buildBrowser(int x, int y, int width) {
        labels.add(new Label("Party browser", x, y, 0xFFE6DFFF));
        y += 14;
        JsonArray browser = state.getAsJsonArray("browser");
        if (browser.isEmpty()) {
            labels.add(new Label("No public or semi-private parties", x, y + 4, 0xFF7A7F88));
            return;
        }
        int rows = (top + PANEL_HEIGHT - 40 - y) / 26;
        browserScroll = Math.max(0, Math.min(browserScroll, Math.max(0, browser.size() - rows)));
        boolean inParty = state.has("party");
        for (int index = browserScroll; index < browser.size() && index < browserScroll + rows; index++) {
            JsonObject party = browser.get(index).getAsJsonObject();
            String id = party.get("id").getAsString();
            int members = party.get("members").getAsInt(), max = party.get("max").getAsInt();
            boolean semi = party.get("visibility").getAsString().equals("SEMI_PRIVATE");
            String activity = party.get("activity").getAsString();
            labels.add(new Label(trim(party.get("name").getAsString(), width - 70), x, y + 2, 0xFFE8E8E8));
            labels.add(new Label(trim(party.get("leader").getAsString() + " · " + members + "/" + max
                    + (activity.isEmpty() ? "" : " · " + activity), width - 70), x, y + 13, 0xFF9CA3AF));
            String action = members >= max ? "Full" : party.get("requested").getAsBoolean() ? "Asked"
                    : semi ? "Request" : "Join";
            Button button = Button.builder(Component.literal(action), ignored -> send("join", id, ""))
                    .bounds(x + width - 64, y + 2, 60, 18)
                    .tooltip(Tooltip.create(Component.literal(semi ? "Semi-private: the leader has to accept you."
                            : "Public: join right away."))).build();
            button.active = !inParty && action.equals("Join") || !inParty && action.equals("Request");
            addRenderableWidget(button);
            y += 26;
        }
        if (browser.size() > rows) labels.add(new Label((browserScroll + 1) + "-" + Math.min(browser.size(), browserScroll + rows)
                + " / " + browser.size(), x, top + PANEL_HEIGHT - 40, 0xFF7A7F88));
    }

    private void suggestName(String typed) {
        if (inviteBox == null) return;
        String match = null;
        if (!typed.isEmpty()) for (JsonElement element : state.getAsJsonArray("online")) {
            String name = element.getAsString();
            if (name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) { match = name; break; }
        }
        inviteBox.setSuggestion(match == null || match.length() == typed.length() ? null : match.substring(typed.length()));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB && inviteBox != null && inviteBox.isFocused()) {
            String typed = inviteBox.getValue();
            for (JsonElement element : state.getAsJsonArray("online")) {
                String name = element.getAsString();
                if (!typed.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) {
                    inviteBox.setValue(name);
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX > width / 2.0) {
            int previous = browserScroll;
            browserScroll = Math.max(0, browserScroll + (vertical < 0 ? 1 : -1));
            if (browserScroll != previous) {
                if (nameBox != null) draftName = nameBox.getValue();
                if (inviteBox != null) draftInvite = inviteBox.getValue();
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelWidth = Math.min(PANEL_WIDTH, width - 16);
        graphics.fill(0, 0, width, height, 0xA0000000);
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + PANEL_HEIGHT + 1, 0xFF7C5CDA);
        graphics.fill(left, top, left + panelWidth, top + PANEL_HEIGHT, 0xF012151B);
        int divider = left + 15 + (panelWidth - 30) / 2;
        graphics.fill(divider, top + 24, divider + 1, top + PANEL_HEIGHT - 34, 0xFF2E3440);
        graphics.text(font, Component.literal("Party"), left + 10, top + 9, 0xFFF2F2F5, false);
        for (Label label : labels) graphics.text(font, Component.literal(label.text()), label.x(), label.y(), label.color(), false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        send("close", "", "");
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String trim(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
    }

    private static String label(String visibility) {
        return switch (visibility) {
            case "PUBLIC" -> "Public";
            case "SEMI_PRIVATE" -> "Semi-private";
            default -> "Private";
        };
    }

    private static String next(String visibility) {
        return switch (visibility) {
            case "PRIVATE" -> "SEMI_PRIVATE";
            case "SEMI_PRIVATE" -> "PUBLIC";
            default -> "PRIVATE";
        };
    }
}
