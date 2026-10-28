package ekuzo.crazyworldprogression.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The Friends and Clan window (SocialScreen on the client): the server sends everything the window shows as JSON, and
 * the window's buttons come back as actions that do what the /friend and /clan commands do.
 */
public final class SocialNetworking {
    private static final Gson GSON = new Gson();

    public record ActionPayload(String action, String argument) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(CrazyWorldProgression.id("social_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ActionPayload::action, ByteBufCodecs.STRING_UTF8, ActionPayload::argument, ActionPayload::new);
        @Override public Type<ActionPayload> type() { return TYPE; }
    }

    public record StatePayload(String json, boolean open) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("social_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, ByteBufCodecs.BOOL, StatePayload::open, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    private SocialNetworking() { }

    static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> act(context.player(), payload.action(), payload.argument()));
    }

    public static void open(ServerPlayer player) { send(player, true); }

    private static void act(ServerPlayer player, String action, String argument) {
        SocialData data = SocialData.get(player.level().getServer());
        String error = null;
        try {
            switch (action) {
                case "open" -> { send(player, true); return; }
                case "friend_add" -> Social.addFriend(player, argument);
                case "friend_accept" -> {
                    UUID from = Social.id(player, argument);
                    data.acceptFriend(player.getUUID(), from);
                    Social.befriended(player, from);
                }
                case "friend_deny" -> data.denyFriend(player.getUUID(), Social.id(player, argument));
                case "friend_remove" -> data.removeFriend(player.getUUID(), Social.id(player, argument));
                case "clan_create" -> {
                    int split = argument.indexOf('|');
                    if (split < 0) throw new IllegalArgumentException("Enter a tag and a name");
                    data.createClan(player.getUUID(), argument.substring(0, split), argument.substring(split + 1));
                }
                case "clan_invite" -> Social.invite(player, argument);
                case "clan_accept" -> Social.acceptInvite(player);
                case "clan_decline" -> Social.INVITES.remove(player.getUUID());
                case "clan_leave" -> Social.leave(player);
                case "clan_kick" -> Social.kick(player, argument);
                case "clan_promote" -> Social.setOfficer(player, argument, true);
                case "clan_demote" -> Social.setOfficer(player, argument, false);
                case "clan_transfer" -> Social.transfer(player, argument);
                case "clan_disband" -> Social.disband(player);
                case "clan_deliver" -> player.sendSystemMessage(Component.literal(ClanMissions.deliver(player, Social.requireClan(player)))
                        .withStyle(ChatFormatting.AQUA));
                case "clan_chat" -> {
                    Social.requireClan(player);
                    if (!Social.CLAN_CHAT.remove(player.getUUID())) Social.CLAN_CHAT.add(player.getUUID());
                }
                default -> { return; }
            }
        } catch (IllegalArgumentException exception) {
            error = exception.getMessage();
        }
        JsonObject state = state(player);
        state.addProperty("error", error == null ? "" : error);
        if (ServerPlayNetworking.canSend(player, StatePayload.TYPE))
            ServerPlayNetworking.send(player, new StatePayload(GSON.toJson(state), false));
    }

    private static void send(ServerPlayer player, boolean open) {
        if (!ServerPlayNetworking.canSend(player, StatePayload.TYPE)) return;
        JsonObject state = state(player);
        state.addProperty("error", "");
        ServerPlayNetworking.send(player, new StatePayload(GSON.toJson(state), open));
    }

    private static JsonObject state(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        SocialData data = SocialData.get(server);
        UUID me = player.getUUID();
        JsonObject state = new JsonObject();
        JsonArray friends = new JsonArray();
        for (UUID friend : data.friendsOf(me)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", data.name(friend));
            entry.addProperty("online", server.getPlayerList().getPlayer(friend) != null);
            friends.add(entry);
        }
        state.add("friends", friends);
        JsonArray requests = new JsonArray();
        for (UUID from : data.requestsFor(me)) requests.add(data.name(from));
        state.add("requests", requests);
        LockRegistry registry = LockRegistry.get(server);
        state.addProperty("chests", registry.personal(server, me) + "/" + LockRegistry.PLAYER_LIMIT);
        SocialData.Clan invite = data.clan(Social.INVITES.get(me));
        if (invite != null) state.addProperty("invite", "[" + invite.tag() + "] " + invite.name());
        SocialData.Clan clan = data.clanOf(me);
        if (clan == null) return state;
        JsonObject clanState = new JsonObject();
        clanState.addProperty("tag", clan.tag());
        clanState.addProperty("name", clan.name());
        int tier = clan.tier();
        clanState.addProperty("tier", tier);
        clanState.addProperty("xp", clan.xp());
        clanState.addProperty("next", tier >= SocialData.TIER_XP.length ? -1 : SocialData.TIER_XP[tier]);
        clanState.addProperty("cap", clan.memberCap());
        clanState.addProperty("rank", clan.rank(me).name());
        clanState.addProperty("chat", Social.CLAN_CHAT.contains(me));
        clanState.addProperty("chests", registry.clan(server, clan.id()) + "/" + LockRegistry.clanLimit(clan));
        JsonArray members = new JsonArray();
        for (UUID member : clan.members) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", data.name(member));
            entry.addProperty("rank", clan.rank(member).name());
            entry.addProperty("online", server.getPlayerList().getPlayer(member) != null);
            members.add(entry);
        }
        clanState.add("members", members);
        ClanMissions.State missions = ClanMissions.current(server, clan);
        for (boolean weekly : new boolean[]{false, true}) {
            JsonArray list = new JsonArray();
            for (String id : weekly ? missions.weekly() : missions.daily()) {
                ClanMissions.Mission mission = ClanMissions.mission(id);
                if (mission == null) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("text", mission.describe());
                entry.addProperty("progress", Math.min(mission.amount(), missions.progress(id)));
                entry.addProperty("amount", mission.amount());
                entry.addProperty("xp", mission.xp());
                entry.addProperty("item", mission.item() == null ? "" : mission.item().toString());
                list.add(entry);
            }
            clanState.add(weekly ? "weekly" : "daily", list);
        }
        state.add("clan", clanState);
        return state;
    }
}
