package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.client.style.PanelStyle;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.social.SocialNetworking;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Friends and Clan window (U, or /social), styled like the party window: a Friends tab (add, requests, the list
 * with who is online), a Clan tab (found or join one, members with their ranks and the leader's and co-leaders'
 * buttons, invites, clan chat, chest limits) and a Missions tab (today's and this week's clan missions with a deliver
 * button). All changes go to the server (SocialNetworking), which sends back the new state.
 */
public final class SocialScreen extends Screen {
    private static final int PANEL_WIDTH = 440, PANEL_HEIGHT = 280, ROW = 22;
    private static KeyMapping openKey;

    enum Tab { FRIENDS, CLAN, MISSIONS }

    /** The tab the window opens on: the one used last. */
    static Tab lastTab = Tab.FRIENDS;

    private final Screen parent;
    private JsonObject state;
    private Tab tab = lastTab;
    private int left, top, panelHeight;
    private final List<Label> labels = new ArrayList<>();
    private final List<Bar> bars = new ArrayList<>();
    private EditBox nameBox, tagBox, clanNameBox, inviteBox;
    private String draftName = "", draftTag = "", draftClanName = "", draftInvite = "";

    private record Label(String text, int x, int y, int color) { }

    private record Bar(int x, int y, int width, double share, int color) { }

    private SocialScreen(Screen parent, JsonObject state) {
        super(Component.literal("Friends & Clan"));
        this.parent = parent;
        this.state = state;
    }

    static void initialize() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.crazy-world-progression.open_social",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("crazy-world-progression", "social"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) if (client.player != null && client.gui.screen() == null) send("open", "");
        });
        ClientPlayNetworking.registerGlobalReceiver(SocialNetworking.StatePayload.TYPE, (payload, context) -> {
            JsonObject received = JsonParser.parseString(payload.json()).getAsJsonObject();
            Minecraft client = context.client();
            if (client.gui.screen() instanceof SocialScreen screen) screen.update(received);
            else if (payload.open()) client.gui.setScreen(new SocialScreen(client.gui.screen(), received));
        });
    }

    private static void send(String action, String argument) {
        if (ClientPlayNetworking.canSend(SocialNetworking.ActionPayload.TYPE))
            ClientPlayNetworking.send(new SocialNetworking.ActionPayload(action, argument));
    }

    private void update(JsonObject received) {
        keepDrafts();
        state = received;
        rebuildWidgets();
    }

    private void keepDrafts() {
        if (nameBox != null) draftName = nameBox.getValue();
        if (tagBox != null) draftTag = tagBox.getValue();
        if (clanNameBox != null) draftClanName = clanNameBox.getValue();
        if (inviteBox != null) draftInvite = inviteBox.getValue();
    }

    @Override
    protected void init() {
        labels.clear();
        bars.clear();
        nameBox = tagBox = clanNameBox = inviteBox = null;
        int panelWidth = Math.min(PANEL_WIDTH, width - 16);
        panelHeight = Math.min(PANEL_HEIGHT, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int tabX = left + 10;
        for (Tab each : Tab.values()) {
            String label = switch (each) { case FRIENDS -> "Friends"; case CLAN -> "Clan"; case MISSIONS -> "Missions"; };
            Button button = addRenderableWidget(Button.builder(Component.literal(label), ignored -> {
                keepDrafts();
                tab = each;
                lastTab = each;
                rebuildWidgets();
            }).bounds(tabX, top + 22, 80, 18).build());
            button.active = tab != each && (each != Tab.MISSIONS || state.has("clan"));
            tabX += 84;
        }
        int x = left + 10, y = top + 48, contentWidth = panelWidth - 20;
        switch (tab) {
            case FRIENDS -> buildFriends(x, y, contentWidth);
            case CLAN -> buildClan(x, y, contentWidth);
            case MISSIONS -> buildMissions(x, y, contentWidth);
        }
        String error = state.has("error") ? state.get("error").getAsString() : "";
        if (!error.isEmpty()) labels.add(new Label(error, left + 10, top + panelHeight - 22, PanelStyle.get().bad()));
        addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
                .bounds(left + panelWidth - 90, top + panelHeight - 28, 80, 20).build());
    }

    private void buildFriends(int x, int y, int width) {
        nameBox = new EditBox(font, x, y, 160, 18, Component.literal("Player name"));
        nameBox.setHint(Component.literal("Player name"));
        nameBox.setMaxLength(16);
        nameBox.setValue(draftName);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.literal("Add friend"), ignored -> {
            draftName = "";
            send("friend_add", nameBox.getValue().trim());
        }).bounds(x + 164, y, 80, 18).build());
        labels.add(new Label("Locked chests: " + state.get("chests").getAsString() + " Private/Friends", x + 252, y + 5, PanelStyle.get().muted()));
        y += ROW + 6;
        for (JsonElement element : state.getAsJsonArray("requests")) {
            String name = element.getAsString();
            labels.add(new Label("Request from " + name, x, y + 5, PanelStyle.get().accent()));
            addRenderableWidget(Button.builder(Component.literal("Accept"), ignored -> send("friend_accept", name))
                    .bounds(x + width - 128, y, 60, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Decline"), ignored -> send("friend_deny", name))
                    .bounds(x + width - 64, y, 60, 18).build());
            y += ROW;
        }
        JsonArray friends = state.getAsJsonArray("friends");
        labels.add(new Label("Friends (" + friends.size() + ")", x, y + 4, PanelStyle.get().heading()));
        y += 16;
        if (friends.isEmpty()) labels.add(new Label("No friends yet. Add someone above.", x, y + 4, PanelStyle.get().muted()));
        for (JsonElement element : friends) {
            if (y > top + panelHeight - 56) break;
            JsonObject friend = element.getAsJsonObject();
            String name = friend.get("name").getAsString();
            boolean online = friend.get("online").getAsBoolean();
            labels.add(new Label((online ? "● " : "○ ") + name, x, y + 5, online ? PanelStyle.get().good() : PanelStyle.get().muted()));
            addRenderableWidget(Button.builder(Component.literal("Remove"), ignored -> send("friend_remove", name))
                    .bounds(x + width - 64, y, 60, 18).build());
            y += ROW;
        }
    }

    private void buildClan(int x, int y, int width) {
        JsonObject clan = state.has("clan") ? state.getAsJsonObject("clan") : null;
        if (clan == null) {
            if (state.has("invite")) {
                labels.add(new Label("Invite to " + state.get("invite").getAsString(), x, y + 5, PanelStyle.get().accent()));
                addRenderableWidget(Button.builder(Component.literal("Join"), ignored -> send("clan_accept", ""))
                        .bounds(x + width - 128, y, 60, 18).build());
                addRenderableWidget(Button.builder(Component.literal("Decline"), ignored -> send("clan_decline", ""))
                        .bounds(x + width - 64, y, 60, 18).build());
                y += ROW + 6;
            }
            labels.add(new Label("Found a clan", x, y + 4, PanelStyle.get().heading()));
            y += 16;
            tagBox = new EditBox(font, x, y, 60, 18, Component.literal("Tag"));
            tagBox.setHint(Component.literal("TAG"));
            tagBox.setMaxLength(5);
            tagBox.setValue(draftTag);
            addRenderableWidget(tagBox);
            clanNameBox = new EditBox(font, x + 64, y, 180, 18, Component.literal("Clan name"));
            clanNameBox.setHint(Component.literal("Clan name"));
            clanNameBox.setMaxLength(32);
            clanNameBox.setValue(draftClanName);
            addRenderableWidget(clanNameBox);
            addRenderableWidget(Button.builder(Component.literal("Found"), ignored ->
                    send("clan_create", tagBox.getValue().trim() + "|" + clanNameBox.getValue().trim())).bounds(x + 248, y, 70, 18).build());
            y += ROW + 4;
            labels.add(new Label("A clan starts at tier 1 with room for 5 members; missions raise its tier (up to 15).", x, y, PanelStyle.get().muted()));
            return;
        }
        String rank = clan.get("rank").getAsString();
        boolean leader = rank.equals("LEADER"), manager = leader || rank.equals("CO_LEADER");
        int tier = clan.get("tier").getAsInt(), xp = clan.get("xp").getAsInt(), next = clan.get("next").getAsInt();
        labels.add(new Label("[" + clan.get("tag").getAsString() + "] " + clan.get("name").getAsString() + " · Tier " + tier,
                x, y, PanelStyle.get().accent()));
        labels.add(new Label(next < 0 ? "Max tier" : xp + " / " + next + " clan XP", x + width - 150, y, PanelStyle.get().muted()));
        bars.add(new Bar(x, y + 11, width, next < 0 ? 1.0 : Math.min(1.0, xp / (double) next), PanelStyle.get().accent()));
        y += 20;
        JsonArray members = clan.getAsJsonArray("members");
        labels.add(new Label("Members " + members.size() + "/" + clan.get("cap").getAsInt() + " · Clan chests "
                + clan.get("chests").getAsString() + " · You: " + rankLabel(rank), x, y + 4, PanelStyle.get().heading()));
        y += 16;
        for (JsonElement element : members) {
            if (y > top + panelHeight - 82) break;
            JsonObject member = element.getAsJsonObject();
            String name = member.get("name").getAsString(), memberRank = member.get("rank").getAsString();
            boolean online = member.get("online").getAsBoolean();
            labels.add(new Label((online ? "● " : "○ ") + name + " · " + rankLabel(memberRank), x, y + 5, online ? PanelStyle.get().good() : PanelStyle.get().muted()));
            int buttonX = x + width - 64;
            boolean self = name.equals(minecraft.player.getGameProfile().name());
            int mine = order(rank), theirs = order(memberRank);
            if (!self && manager && theirs < mine) {
                addRenderableWidget(Button.builder(Component.literal("Kick"), ignored -> send("clan_kick", name))
                        .bounds(buttonX, y, 60, 18).build());
                buttonX -= 64;
                // Promote: member to officer (co-leaders too), officer to co-leader (leader only).
                if (memberRank.equals("MEMBER") || (memberRank.equals("OFFICER") && leader)) {
                    addRenderableWidget(Button.builder(Component.literal("Promote"), ignored -> send("clan_promote", name))
                            .bounds(buttonX, y, 60, 18).build());
                    buttonX -= 64;
                }
                if (memberRank.equals("OFFICER") || (memberRank.equals("CO_LEADER") && leader)) {
                    addRenderableWidget(Button.builder(Component.literal("Demote"), ignored -> send("clan_demote", name))
                            .bounds(buttonX, y, 60, 18).build());
                    buttonX -= 64;
                }
            }
            if (!self && leader) {
                Button lead = Button.builder(Component.literal("Make leader"), ignored -> send("clan_transfer", name))
                        .bounds(buttonX - 20, y, 80, 18).build();
                lead.setTooltip(Tooltip.create(Component.literal("Hands the clan to " + name + "; you become a co-leader")));
                addRenderableWidget(lead);
            }
            y += ROW;
        }
        int bottom = top + panelHeight - 56;
        if (manager) {
            inviteBox = new EditBox(font, x, bottom, 140, 18, Component.literal("Player"));
            inviteBox.setHint(Component.literal("Player to invite"));
            inviteBox.setMaxLength(16);
            inviteBox.setValue(draftInvite);
            addRenderableWidget(inviteBox);
            addRenderableWidget(Button.builder(Component.literal("Invite"), ignored -> {
                draftInvite = "";
                send("clan_invite", inviteBox.getValue().trim());
            }).bounds(x + 144, bottom, 60, 18).build());
        }
        boolean chat = clan.get("chat").getAsBoolean();
        Button chatButton = addRenderableWidget(Button.builder(Component.literal(chat ? "Clan chat: on" : "Clan chat: off"),
                ignored -> send("clan_chat", "")).bounds(x + 210, bottom, 90, 18).build());
        chatButton.setTooltip(Tooltip.create(Component.literal("While on, your normal chat goes to the clan. /c <message> sends one clan message.")));
        addRenderableWidget(Button.builder(Component.literal(leader ? "Disband" : "Leave"), ignored ->
                send(leader && members.size() > 1 ? "clan_disband" : leader ? "clan_disband" : "clan_leave", ""))
                .bounds(x + width - 64, bottom, 60, 18).build());
    }

    private void buildMissions(int x, int y, int width) {
        JsonObject clan = state.has("clan") ? state.getAsJsonObject("clan") : null;
        if (clan == null) {
            labels.add(new Label("Join a clan to see its missions.", x, y + 4, PanelStyle.get().muted()));
            return;
        }
        for (String group : new String[]{"daily", "weekly"}) {
            labels.add(new Label(group.equals("daily") ? "Daily missions (new each day)" : "Weekly missions (new each week)",
                    x, y + 4, PanelStyle.get().accent()));
            y += 16;
            for (JsonElement element : clan.getAsJsonArray(group)) {
                JsonObject mission = element.getAsJsonObject();
                int progress = mission.get("progress").getAsInt(), amount = mission.get("amount").getAsInt();
                boolean done = progress >= amount;
                labels.add(new Label(trim((done ? "✔ " : "") + mission.get("text").getAsString(), width - 230), x, y + 2,
                        done ? PanelStyle.get().good() : PanelStyle.get().text()));
                labels.add(new Label(progress + "/" + amount + " · " + mission.get("xp").getAsInt() + " XP", x + width - 222, y + 2, PanelStyle.get().muted()));
                bars.add(new Bar(x, y + 12, width - 100, progress / (double) amount, done ? PanelStyle.get().good() : PanelStyle.get().accent()));
                if (!done && !mission.get("item").getAsString().isEmpty()) {
                    Button deliver = addRenderableWidget(Button.builder(Component.literal("Deliver held"),
                            ignored -> send("clan_deliver", "")).bounds(x + width - 90, y, 86, 16).build());
                    deliver.setTooltip(Tooltip.create(Component.literal("Hands in the items in your main hand. They are used up.")));
                }
                y += ROW;
            }
            y += 6;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelWidth = Math.min(PANEL_WIDTH, width - 16);
        PanelStyle style = PanelStyle.get();
        style.backdrop(graphics, width, height);
        style.window(graphics, left, top, panelWidth, panelHeight);
        graphics.text(font, title, left + 10, top + 9, style.title(), false);
        for (Bar bar : bars) style.bar(graphics, bar.x(), bar.y(), bar.width(), bar.share(), bar.color());
        for (Label label : labels) graphics.text(font, Component.literal(label.text()), label.x(), label.y(), label.color(), false);
        PanelStyle.styling(true);
        try {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        } finally {
            PanelStyle.styling(false);
        }
    }

    private static String rankLabel(String rank) {
        return switch (rank) { case "LEADER" -> "Leader"; case "CO_LEADER" -> "Co-leader"; case "OFFICER" -> "Officer"; default -> "Member"; };
    }

    private static int order(String rank) {
        return switch (rank) { case "LEADER" -> 3; case "CO_LEADER" -> 2; case "OFFICER" -> 1; default -> 0; };
    }

    private String trim(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(1, width - font.width("…"))) + "…";
    }

    @Override
    public void onClose() { minecraft.gui.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
