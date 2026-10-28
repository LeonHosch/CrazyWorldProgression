package ekuzo.crazyworldprogression.social;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Friends and clans in chat. /friend add, accept, deny, remove and list; friends see each other come online.
 * /clan create, invite, accept, leave, kick, promote, demote, transfer, disband, info, missions, deliver and chat;
 * /c sends one message to the clan, and /clan chat makes normal chat go to the clan until toggled off. A clan's
 * member cap rises with its tier (SocialData.MEMBER_CAP). Ranks (SocialData.Rank): co-leaders and the leader invite,
 * kick and promote or demote members and officers; only the leader names co-leaders.
 */
public final class Social {
    /** Players whose normal chat goes to their clan. */
    static final Set<UUID> CLAN_CHAT = ConcurrentHashMap.newKeySet();
    /** Open clan invites: invited player to clan. */
    static final Map<UUID, UUID> INVITES = new ConcurrentHashMap<>();

    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (context, builder) ->
            SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

    private Social() { }

    public static void initialize() {
        ChestLocks.initialize();
        SocialNetworking.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> joined(server, handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CLAN_CHAT.remove(handler.player.getUUID()));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!CLAN_CHAT.contains(sender.getUUID())) return true;
            SocialData.Clan clan = SocialData.get(sender.level().getServer()).clanOf(sender.getUUID());
            if (clan == null) {
                CLAN_CHAT.remove(sender.getUUID());
                return true;
            }
            clanChat(sender, clan, message.signedContent());
            return false;
        });
    }

    private static void joined(MinecraftServer server, ServerPlayer player) {
        SocialData data = SocialData.get(server);
        data.name(player.getUUID(), player.getGameProfile().name());
        for (UUID friend : data.friendsOf(player.getUUID())) {
            ServerPlayer online = server.getPlayerList().getPlayer(friend);
            if (online != null) online.sendSystemMessage(Component.literal(player.getGameProfile().name() + " is online")
                    .withStyle(ChatFormatting.AQUA));
        }
        int requests = data.requestsFor(player.getUUID()).size();
        if (requests > 0) player.sendSystemMessage(Component.literal("You have " + requests + " friend request(s). ")
                .withStyle(ChatFormatting.AQUA).append(button("[Show]", "/friend list")));
    }

    // ---------------------------------------------------------------- commands

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("friend")
                .executes(context -> run(context, player -> listFriends(player)))
                .then(Commands.literal("list").executes(context -> run(context, Social::listFriends)))
                .then(withPlayer("add", Social::addFriend))
                .then(withPlayer("accept", (player, name) -> {
                    SocialData data = data(player);
                    UUID from = id(player, name);
                    data.acceptFriend(player.getUUID(), from);
                    befriended(player, from);
                }))
                .then(withPlayer("deny", (player, name) -> {
                    data(player).denyFriend(player.getUUID(), id(player, name));
                    say(player, "Request declined");
                }))
                .then(withPlayer("remove", (player, name) -> {
                    data(player).removeFriend(player.getUUID(), id(player, name));
                    say(player, "Removed " + name + " from your friends");
                })));
        dispatcher.register(Commands.literal("social").executes(context -> run(context, SocialNetworking::open)));
        dispatcher.register(Commands.literal("c").then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(context -> run(context, player -> clanChat(player, requireClan(player),
                        StringArgumentType.getString(context, "message"))))));
        dispatcher.register(Commands.literal("clan")
                .executes(context -> run(context, Social::info))
                .then(Commands.literal("info").executes(context -> run(context, Social::info)))
                .then(Commands.literal("create").then(Commands.argument("tag", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(context -> run(context, player -> {
                            SocialData.Clan clan = data(player).createClan(player.getUUID(),
                                    StringArgumentType.getString(context, "tag"), StringArgumentType.getString(context, "name"));
                            say(player, "Founded [" + clan.tag() + "] " + clan.name() + ". Invite members with /clan invite <player>.");
                        })))))
                .then(withPlayer("invite", Social::invite))
                .then(Commands.literal("accept").executes(context -> run(context, Social::acceptInvite)))
                .then(Commands.literal("decline").executes(context -> run(context, player -> {
                    if (INVITES.remove(player.getUUID()) == null) throw new IllegalArgumentException("You have no clan invite");
                    say(player, "Invite declined");
                })))
                .then(Commands.literal("leave").executes(context -> run(context, Social::leave)))
                .then(withPlayer("kick", Social::kick))
                .then(withPlayer("promote", (player, name) -> setOfficer(player, name, true)))
                .then(withPlayer("demote", (player, name) -> setOfficer(player, name, false)))
                .then(withPlayer("transfer", Social::transfer))
                .then(Commands.literal("disband").executes(context -> run(context, Social::disband)))
                .then(Commands.literal("missions").executes(context -> run(context, Social::missions)))
                .then(Commands.literal("deliver").executes(context -> run(context, player ->
                        say(player, ClanMissions.deliver(player, requireClan(player))))))
                .then(Commands.literal("chat").executes(context -> run(context, player -> {
                    requireClan(player);
                    if (CLAN_CHAT.remove(player.getUUID())) say(player, "Your chat goes to everyone again");
                    else {
                        CLAN_CHAT.add(player.getUUID());
                        say(player, "Your chat now goes to your clan. /clan chat again to switch back");
                    }
                }))));
    }

    private interface PlayerAction { void run(ServerPlayer player) throws Exception; }

    private interface NamedAction { void run(ServerPlayer player, String name) throws Exception; }

    private static LiteralArgumentBuilder<CommandSourceStack> withPlayer(String literal, NamedAction action) {
        return Commands.literal(literal).then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                .executes(context -> run(context, player -> action.run(player, StringArgumentType.getString(context, "player")))));
    }

    private static int run(CommandContext<CommandSourceStack> context, PlayerAction action) {
        try {
            action.run(context.getSource().getPlayerOrException());
            return 1;
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage()));
            return 0;
        }
    }

    private static SocialData data(ServerPlayer player) { return SocialData.get(player.level().getServer()); }

    /** An online player by name, or a known (offline) one. */
    static UUID id(ServerPlayer player, String name) {
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        UUID known = data(player).idByName(name);
        if (known == null) throw new IllegalArgumentException("Unknown player " + name);
        return known;
    }

    private static void say(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
    }

    private static MutableComponent button(String label, String command) {
        return Component.literal(label).withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    // ---------------------------------------------------------------- friends

    static void addFriend(ServerPlayer player, String name) {
        ServerPlayer target = player.level().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException(name + " is not online");
        if (data(player).requestFriend(player.getUUID(), target.getUUID())) {
            befriended(player, target.getUUID());
            return;
        }
        say(player, "Friend request sent to " + target.getGameProfile().name());
        String me = player.getGameProfile().name();
        target.sendSystemMessage(Component.literal(me + " wants to be your friend. ").withStyle(ChatFormatting.AQUA)
                .append(button("[Accept]", "/friend accept " + me)).append(" ").append(button("[Decline]", "/friend deny " + me)));
    }

    static void befriended(ServerPlayer player, UUID other) {
        SocialData data = data(player);
        say(player, "You and " + data.name(other) + " are now friends");
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(other);
        if (online != null) say(online, "You and " + player.getGameProfile().name() + " are now friends");
    }

    private static void listFriends(ServerPlayer player) {
        SocialData data = data(player);
        MinecraftServer server = player.level().getServer();
        var friends = data.friendsOf(player.getUUID());
        player.sendSystemMessage(Component.literal("Friends (" + friends.size() + ")").withStyle(ChatFormatting.GOLD));
        for (UUID friend : friends) {
            boolean online = server.getPlayerList().getPlayer(friend) != null;
            player.sendSystemMessage(Component.literal(" " + data.name(friend) + (online ? " · online" : " · offline"))
                    .withStyle(online ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                    .append(" ").append(button("[Remove]", "/friend remove " + data.name(friend))));
        }
        for (UUID from : data.requestsFor(player.getUUID()))
            player.sendSystemMessage(Component.literal(" Request from " + data.name(from) + " ").withStyle(ChatFormatting.AQUA)
                    .append(button("[Accept]", "/friend accept " + data.name(from))).append(" ")
                    .append(button("[Decline]", "/friend deny " + data.name(from))));
        if (friends.isEmpty()) say(player, "Add friends with /friend add <player>");
    }

    // ---------------------------------------------------------------- clans

    static SocialData.Clan requireClan(ServerPlayer player) {
        SocialData.Clan clan = data(player).clanOf(player.getUUID());
        if (clan == null) throw new IllegalArgumentException("You are not in a clan. Found one with /clan create <tag> <name>");
        return clan;
    }

    private static SocialData.Clan requireManager(ServerPlayer player) {
        SocialData.Clan clan = requireClan(player);
        if (!clan.manager(player.getUUID())) throw new IllegalArgumentException("Only the leader and co-leaders can do that");
        return clan;
    }

    private static SocialData.Clan requireLeader(ServerPlayer player) {
        SocialData.Clan clan = requireClan(player);
        if (!clan.leader().equals(player.getUUID())) throw new IllegalArgumentException("Only the clan leader can do that");
        return clan;
    }

    /** Sends a message to every online member. */
    public static void tellClan(MinecraftServer server, SocialData.Clan clan, Component message) {
        for (UUID member : clan.members) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) online.sendSystemMessage(message);
        }
    }

    private static void clanChat(ServerPlayer sender, SocialData.Clan clan, String text) {
        Component line = Component.literal("[" + clan.tag() + "] ").withStyle(ChatFormatting.DARK_GREEN)
                .append(Component.literal(sender.getGameProfile().name() + ": ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        tellClan(sender.level().getServer(), clan, line);
        ekuzo.crazyworldprogression.CrazyWorldProgression.LOGGER.info("[Clan {}] {}: {}", clan.tag(), sender.getGameProfile().name(), text);
    }

    static void invite(ServerPlayer player, String name) {
        SocialData.Clan clan = requireManager(player);
        ServerPlayer target = player.level().getServer().getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException(name + " is not online");
        if (data(player).clanOf(target.getUUID()) != null) throw new IllegalArgumentException(name + " is already in a clan");
        if (clan.members.size() >= clan.memberCap())
            throw new IllegalArgumentException("The clan is full (" + clan.memberCap() + " members at tier " + clan.tier() + ")");
        INVITES.put(target.getUUID(), clan.id());
        say(player, "Invited " + target.getGameProfile().name());
        target.sendSystemMessage(Component.literal(player.getGameProfile().name() + " invites you to [" + clan.tag() + "] "
                + clan.name() + ". ").withStyle(ChatFormatting.AQUA)
                .append(button("[Join]", "/clan accept")).append(" ").append(button("[Decline]", "/clan decline")));
    }

    static void acceptInvite(ServerPlayer player) {
        SocialData data = data(player);
        UUID id = INVITES.remove(player.getUUID());
        SocialData.Clan clan = data.clan(id);
        if (clan == null) throw new IllegalArgumentException("You have no clan invite");
        if (data.clanOf(player.getUUID()) != null) throw new IllegalArgumentException("Leave your clan first");
        if (clan.members.size() >= clan.memberCap()) throw new IllegalArgumentException("The clan is full");
        clan.members.add(player.getUUID());
        data.setDirty();
        tellClan(player.level().getServer(), clan, Component.literal(player.getGameProfile().name() + " joined the clan")
                .withStyle(ChatFormatting.GREEN));
    }

    static void leave(ServerPlayer player) {
        SocialData data = data(player);
        SocialData.Clan clan = requireClan(player);
        if (clan.leader().equals(player.getUUID()) && clan.members.size() > 1)
            throw new IllegalArgumentException("Hand the clan to someone first with /clan transfer <player>, or /clan disband");
        if (clan.members.size() == 1) {
            data.disband(clan);
            say(player, "You left, and the clan was disbanded. Its chests stay locked to it.");
            return;
        }
        clan.remove(player.getUUID());
        CLAN_CHAT.remove(player.getUUID());
        data.setDirty();
        say(player, "You left [" + clan.tag() + "]");
        tellClan(player.level().getServer(), clan, Component.literal(player.getGameProfile().name() + " left the clan")
                .withStyle(ChatFormatting.YELLOW));
    }

    static void kick(ServerPlayer player, String name) {
        SocialData data = data(player);
        SocialData.Clan clan = requireManager(player);
        UUID target = id(player, name);
        if (!clan.members.contains(target)) throw new IllegalArgumentException(name + " is not in your clan");
        if (clan.rank(target).ordinal() >= clan.rank(player.getUUID()).ordinal())
            throw new IllegalArgumentException("You can't kick " + name);
        clan.remove(target);
        CLAN_CHAT.remove(target);
        data.setDirty();
        tellClan(player.level().getServer(), clan, Component.literal(data.name(target) + " was kicked from the clan")
                .withStyle(ChatFormatting.YELLOW));
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(target);
        if (online != null) say(online, "You were kicked from [" + clan.tag() + "]");
    }

    /**
     * Promotes (member to officer, officer to co-leader) or demotes (co-leader to officer, officer to member). Co-leaders
     * move members and officers; only the leader makes or unmakes co-leaders.
     */
    static void setOfficer(ServerPlayer player, String name, boolean promote) {
        SocialData.Clan clan = requireManager(player);
        UUID target = id(player, name);
        if (!clan.members.contains(target) || target.equals(clan.leader()) || target.equals(player.getUUID()))
            throw new IllegalArgumentException(name + "'s rank can't be changed");
        SocialData.Rank now = clan.rank(target);
        SocialData.Rank next = promote
                ? (now == SocialData.Rank.MEMBER ? SocialData.Rank.OFFICER : SocialData.Rank.CO_LEADER)
                : (now == SocialData.Rank.CO_LEADER ? SocialData.Rank.OFFICER : SocialData.Rank.MEMBER);
        if (next == now) throw new IllegalArgumentException(name + " is already " + now.label());
        boolean leader = clan.rank(player.getUUID()) == SocialData.Rank.LEADER;
        if (!leader && (now == SocialData.Rank.CO_LEADER || next == SocialData.Rank.CO_LEADER))
            throw new IllegalArgumentException("Only the leader names co-leaders");
        clan.setRank(target, next);
        data(player).setDirty();
        tellClan(player.level().getServer(), clan, Component.literal(name + " is now " + next.label()).withStyle(ChatFormatting.AQUA));
    }

    static void transfer(ServerPlayer player, String name) {
        SocialData.Clan clan = requireLeader(player);
        UUID target = id(player, name);
        if (!clan.members.contains(target) || target.equals(player.getUUID())) throw new IllegalArgumentException(name + " is not in your clan");
        clan.leader = target;
        clan.setRank(target, SocialData.Rank.MEMBER);
        clan.setRank(player.getUUID(), SocialData.Rank.CO_LEADER);
        data(player).setDirty();
        tellClan(player.level().getServer(), clan, Component.literal(name + " now leads the clan").withStyle(ChatFormatting.GOLD));
    }

    static void disband(ServerPlayer player) {
        SocialData.Clan clan = requireLeader(player);
        tellClan(player.level().getServer(), clan, Component.literal("[" + clan.tag() + "] " + clan.name()
                + " was disbanded. Its chests stay locked to it.").withStyle(ChatFormatting.YELLOW));
        clan.members.forEach(CLAN_CHAT::remove);
        data(player).disband(clan);
    }

    private static void info(ServerPlayer player) {
        SocialData data = data(player);
        SocialData.Clan clan = requireClan(player);
        MinecraftServer server = player.level().getServer();
        int tier = clan.tier();
        String next = tier >= SocialData.TIER_XP.length ? "max tier" : clan.xp() + "/" + SocialData.TIER_XP[tier] + " XP to tier " + (tier + 1);
        player.sendSystemMessage(Component.literal("[" + clan.tag() + "] " + clan.name() + " · tier " + tier + " · " + next)
                .withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("Members " + clan.members.size() + "/" + clan.memberCap()).withStyle(ChatFormatting.GRAY));
        for (UUID member : clan.members) {
            String rank = clan.rank(member).label();
            boolean online = server.getPlayerList().getPlayer(member) != null;
            player.sendSystemMessage(Component.literal(" " + data.name(member) + " · " + rank + (online ? " · online" : ""))
                    .withStyle(online ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        player.sendSystemMessage(button("[Missions]", "/clan missions").append(" ").append(button("[Clan chat]", "/clan chat")));
    }

    private static void missions(ServerPlayer player) {
        SocialData.Clan clan = requireClan(player);
        ClanMissions.State state = ClanMissions.current(player.level().getServer(), clan);
        for (boolean weekly : new boolean[]{false, true}) {
            player.sendSystemMessage(Component.literal(weekly ? "Weekly missions" : "Daily missions").withStyle(ChatFormatting.GOLD));
            for (String id : weekly ? state.weekly() : state.daily()) {
                ClanMissions.Mission mission = ClanMissions.mission(id);
                if (mission == null) continue;
                int progress = Math.min(mission.amount(), state.progress(id));
                boolean done = progress >= mission.amount();
                MutableComponent line = Component.literal(" " + (done ? "✔ " : "") + mission.describe() + " · " + progress + "/"
                        + mission.amount() + " · " + mission.xp() + " XP").withStyle(done ? ChatFormatting.GREEN : ChatFormatting.WHITE);
                if (!done && mission.kind() == ClanMissions.Kind.DELIVER) line.append(" ").append(button("[Deliver held]", "/clan deliver"));
                player.sendSystemMessage(line);
            }
        }
    }
}
