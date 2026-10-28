package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.client.style.PanelStyle;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.portals.Parties;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
import java.util.UUID;

/** Party screen: your party on the left, the party browser on the right. The server checks every action. */
public final class PartyScreen extends Screen {
    private static final int PANEL_WIDTH = 560;
    private static final int PANEL_HEIGHT = 330;
    private static final int ROW = 20;
    private static KeyMapping openKey;

    private final Screen parent;
    private JsonObject state;
    private int left;
    private int top;
    private int panelHeight;
    private int dividerX;
    private int browserScroll;
    private int memberScroll;
    private static final boolean VOICE_AVAILABLE = FabricLoader.getInstance().isModLoaded("voicechat");
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
        panelHeight = Math.min(PANEL_HEIGHT, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int partyWidth = (panelWidth - 30) * 2 / 3;
        int browserWidth = panelWidth - 30 - partyWidth;
        dividerX = left + 15 + partyWidth;
        int myX = left + 10;
        int browserX = left + 20 + partyWidth;
        buildMyParty(myX, top + 26, partyWidth);
        buildBrowser(browserX, top + 26, browserWidth);
        addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
                .bounds(left + panelWidth - 90, top + panelHeight - 28, 80, 20).build());
    }

    private void buildMyParty(int x, int y, int width) {
        JsonObject party = state.has("party") ? state.getAsJsonObject("party") : null;
        if (party == null) {
            labels.add(new Label("You are not in a party", x, y, PanelStyle.get().heading()));
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
            if (!invites.isEmpty()) labels.add(new Label("Invites", x, y, PanelStyle.get().heading()));
            y += 12;
            for (JsonElement element : invites) {
                JsonObject invite = element.getAsJsonObject();
                String id = invite.get("id").getAsString();
                labels.add(new Label(trim(invite.get("name").getAsString() + " (" + invite.get("leader").getAsString() + ")",
                        width - 92), x, y + 5, PanelStyle.get().text()));
                addRenderableWidget(Button.builder(Component.literal("Accept"), ignored -> send("accept_invite", id, ""))
                        .bounds(x + width - 90, y, 44, 18).build());
                addRenderableWidget(Button.builder(Component.literal("No"), ignored -> send("decline_invite", id, ""))
                        .bounds(x + width - 44, y, 40, 18).build());
                y += ROW;
                if (y > top + panelHeight - 50) break;
            }
            return;
        }
        boolean leader = party.get("leader").getAsBoolean();
        String visibility = party.get("visibility").getAsString();
        labels.add(new Label(trim(party.get("name").getAsString(), width - 4), x, y, PanelStyle.get().heading()));
        String activity = party.get("activity").getAsString();
        boolean pvp = party.has("pvp") && party.get("pvp").getAsBoolean();
        boolean privateVoice = party.has("private_voice") && party.get("private_voice").getAsBoolean();
        labels.add(new Label(label(visibility) + (pvp ? " · PvP on" : " · PvP off")
                + (privateVoice ? " · Private voice" : " · Open voice")
                + (activity.isEmpty() ? "" : " · in " + activity), x, y + 11, PanelStyle.get().muted()));
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
            int half = (width - 92) / 2;
            addRenderableWidget(Button.builder(Component.literal(label(visibility)),
                    ignored -> send("visibility", next(visibility), "")).bounds(x, y, half - 1, 18).build());
            addRenderableWidget(Button.builder(Component.literal(pvp ? "PvP: On" : "PvP: Off"),
                    ignored -> send("pvp", Boolean.toString(!pvp), "")).bounds(x + half + 1, y, width - 92 - half - 1, 18)
                    .build());
            int max = party.get("max").getAsInt();
            addRenderableWidget(Button.builder(Component.literal("-"), ignored -> send("max_size", Integer.toString(max - 1), ""))
                    .bounds(x + width - 88, y, 18, 18).build());
            labels.add(new Label("Max " + max, x + width - 66, y + 5, PanelStyle.get().text()));
            addRenderableWidget(Button.builder(Component.literal("+"), ignored -> send("max_size", Integer.toString(max + 1), ""))
                    .bounds(x + width - 22, y, 18, 18).build());
            y += ROW + 2;
            addRenderableWidget(Button.builder(Component.literal(privateVoice ? "Voice: Private" : "Voice: Open"),
                    ignored -> send("voice_mode", privateVoice ? "open" : "private", ""))
                    .bounds(x, y, width - 4, 18)
                    .tooltip(Tooltip.create(Component.literal("Open: party voice and nearby players hear each other. Private: only party members hear party voice.")))
                    .build());
            y += ROW;
        }
        JsonArray members = party.getAsJsonArray("members");
        int memberSpace = top + panelHeight - 52 - (y + 12);
        int visibleMembers = Math.min(members.size(), Math.min(6, Math.max(1, memberSpace / 19)));
        memberScroll = Math.max(0, Math.min(memberScroll, members.size() - visibleMembers));
        String range = members.size() > visibleMembers ? " (" + (memberScroll + 1) + "-"
                + (memberScroll + visibleMembers) + ")" : "";
        labels.add(new Label("Members " + members.size() + "/" + party.get("max").getAsInt() + range,
                x, y, PanelStyle.get().heading()));
        y += 12;
        for (int index = memberScroll; index < memberScroll + visibleMembers; index++) {
            JsonObject member = members.get(index).getAsJsonObject();
            String id = member.get("id").getAsString();
            boolean isLeader = member.get("leader").getAsBoolean();
            boolean online = member.get("online").getAsBoolean();
            boolean self = minecraft.player != null && minecraft.player.getUUID().toString().equals(id);
            boolean compactControls = width < 230;
            int sliderWidth = leader && !isLeader && compactControls ? 64 : 80;
            int sliderOffset = leader && !isLeader ? (compactControls ? 112 : 150) : 86;
            String displayName = (isLeader ? "★ " : "  ") + member.get("name").getAsString();
            labels.add(new Label(trim(displayName, leader && !isLeader ? width - sliderOffset - 5 : width - 90), x, y + 4,
                    online ? PanelStyle.get().text() : PanelStyle.get().muted()));
            MemberVolumeSlider volume = new MemberVolumeSlider(UUID.fromString(id),
                    x + width - sliderOffset, y, sliderWidth, 16);
            volume.active = VOICE_AVAILABLE && !self;
            volume.setTooltip(Tooltip.create(Component.literal(self ? "You do not hear your own voice."
                    : VOICE_AVAILABLE ? "Volume for " + member.get("name").getAsString() + " (saved on this client)"
                    : "Install Simple Voice Chat to use party voice.")));
            addRenderableWidget(volume);
            if (leader && !isLeader) {
                addRenderableWidget(Button.builder(Component.literal(compactControls ? "L" : "Lead"),
                                ignored -> send("promote", id, ""))
                        .bounds(x + width - (compactControls ? 46 : 68), y, compactControls ? 20 : 32, 16)
                        .tooltip(Tooltip.create(Component.literal("Make party leader"))).build());
                addRenderableWidget(Button.builder(Component.literal(compactControls ? "X" : "Kick"),
                                ignored -> send("kick", id, ""))
                        .bounds(x + width - (compactControls ? 24 : 34), y, compactControls ? 20 : 30, 16)
                        .tooltip(Tooltip.create(Component.literal("Remove from party"))).build());
            }
            y += 19;
        }
        JsonArray requests = party.getAsJsonArray("requests");
        if (leader && !requests.isEmpty()) {
            y += 2;
            labels.add(new Label("Join requests", x, y, PanelStyle.get().heading()));
            y += 12;
            for (JsonElement element : requests) {
                if (y + 17 > top + panelHeight - 52) break;
                JsonObject request = element.getAsJsonObject();
                String id = request.get("id").getAsString();
                labels.add(new Label(request.get("name").getAsString(), x, y + 4, PanelStyle.get().text()));
                addRenderableWidget(Button.builder(Component.literal("Accept"), ignored -> send("accept_request", id, ""))
                        .bounds(x + width - 90, y, 46, 16).build());
                addRenderableWidget(Button.builder(Component.literal("Deny"), ignored -> send("deny_request", id, ""))
                        .bounds(x + width - 42, y, 38, 16).build());
                y += 17;
            }
        }
        int bottom = top + panelHeight - 28;
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
        labels.add(new Label("Party browser", x, y, PanelStyle.get().heading()));
        y += 14;
        JsonArray browser = state.getAsJsonArray("browser");
        if (browser.isEmpty()) {
            labels.add(new Label("No public or", x, y + 4, PanelStyle.get().muted()));
            labels.add(new Label("semi-private parties", x, y + 16, PanelStyle.get().muted()));
            return;
        }
        int rows = (top + panelHeight - 40 - y) / 26;
        browserScroll = Math.max(0, Math.min(browserScroll, Math.max(0, browser.size() - rows)));
        boolean inParty = state.has("party");
        for (int index = browserScroll; index < browser.size() && index < browserScroll + rows; index++) {
            JsonObject party = browser.get(index).getAsJsonObject();
            String id = party.get("id").getAsString();
            int members = party.get("members").getAsInt(), max = party.get("max").getAsInt();
            boolean semi = party.get("visibility").getAsString().equals("SEMI_PRIVATE");
            String activity = party.get("activity").getAsString();
            labels.add(new Label(trim(party.get("name").getAsString(), width - 70), x, y + 2, PanelStyle.get().text()));
            labels.add(new Label(trim(party.get("leader").getAsString() + " · " + members + "/" + max
                    + (activity.isEmpty() ? "" : " · " + activity), width - 70), x, y + 13, PanelStyle.get().muted()));
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
                + " / " + browser.size(), x, top + panelHeight - 40, PanelStyle.get().muted()));
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
        if (mouseX < dividerX && state.has("party")) {
            int previous = memberScroll;
            memberScroll = Math.max(0, memberScroll + (vertical < 0 ? 1 : -1));
            if (memberScroll != previous) {
                if (nameBox != null) draftName = nameBox.getValue();
                if (inviteBox != null) draftInvite = inviteBox.getValue();
                rebuildWidgets();
            }
            return true;
        }
        if (mouseX >= dividerX) {
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
        PanelStyle style = PanelStyle.get();
        style.backdrop(graphics, width, height);
        style.window(graphics, left, top, panelWidth, panelHeight);
        style.divider(graphics, dividerX, top + 24, top + panelHeight - 34);
        graphics.text(font, Component.literal("Party"), left + 10, top + 9, style.title(), false);
        for (Label label : labels) graphics.text(font, Component.literal(label.text()), label.x(), label.y(), label.color(), false);
        PanelStyle.styling(true);
        try {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        } finally {
            PanelStyle.styling(false);
        }
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
            case "FRIENDS" -> "Friends only";
            case "CLAN" -> "Clan only";
            default -> "Private";
        };
    }

    private static String next(String visibility) {
        return switch (visibility) {
            case "PRIVATE" -> "FRIENDS";
            case "FRIENDS" -> "CLAN";
            case "CLAN" -> "SEMI_PRIVATE";
            case "SEMI_PRIVATE" -> "PUBLIC";
            default -> "PRIVATE";
        };
    }

    private static final class MemberVolumeSlider extends AbstractSliderButton {
        private final UUID member;

        private MemberVolumeSlider(UUID member, int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), PartyVoiceVolumes.percent(member) / 200.0);
            this.member = member;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (member != null) setMessage(Component.literal(PartyVoiceVolumes.percent(member) + "%"));
        }

        @Override
        protected void applyValue() {
            PartyVoiceVolumes.setPercent(member, (int) Math.round(value * 200));
            updateMessage();
        }
    }
}
