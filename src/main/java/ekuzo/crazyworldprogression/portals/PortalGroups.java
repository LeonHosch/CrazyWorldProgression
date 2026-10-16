package ekuzo.crazyworldprogression.portals;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-thread-only, pre-entry groups. A travel takes a snapshot of accepted members. */
public final class PortalGroups {
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final long INVITE_TICKS = 1_200L;

    private PortalGroups() { }

    private static State state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, ignored -> new State());
    }

    public static void clear(MinecraftServer server) { STATES.remove(server); }

    public static String pendingInvite(MinecraftServer server, UUID playerId) {
        Invite invite = state(server).invites.get(playerId);
        if (invite == null || invite.deadline() < server.getTickCount()) return "";
        ServerPlayer leader = server.getPlayerList().getPlayer(invite.leader());
        return leader == null ? "" : leader.getGameProfile().name();
    }

    public static UUID leader(MinecraftServer server, UUID member) {
        return state(server).leaders.getOrDefault(member, member);
    }

    public static List<UUID> members(MinecraftServer server, UUID member) {
        UUID leader = leader(server, member);
        return List.copyOf(state(server).groups.getOrDefault(leader, new LinkedHashSet<>(Set.of(leader))));
    }

    public static void invite(ServerPlayer inviter, ServerPlayer target) {
        MinecraftServer server = inviter.level().getServer();
        State state = state(server);
        UUID leader = leader(server, inviter.getUUID());
        if (!leader.equals(inviter.getUUID())) throw new IllegalArgumentException("Only the group leader can invite");
        if (target.getUUID().equals(leader)) throw new IllegalArgumentException("You cannot invite yourself");
        if (state.leaders.containsKey(target.getUUID()) || state.groups.containsKey(target.getUUID())) {
            throw new IllegalArgumentException("Player is already in a group");
        }
        state.invites.put(target.getUUID(), new Invite(leader, server.getTickCount() + INVITE_TICKS));
        target.sendSystemMessage(Component.literal(inviter.getGameProfile().name()
                + " invited you to a portal group. Use /portal group accept " + inviter.getGameProfile().name()));
        inviter.sendSystemMessage(Component.literal("Invited " + target.getGameProfile().name()));
        PortalNetworking.openGroupScreen(target);
    }

    public static void accept(ServerPlayer player, ServerPlayer inviter) {
        MinecraftServer server = player.level().getServer();
        State state = state(server);
        Invite invite = state.invites.remove(player.getUUID());
        if (invite == null || !invite.leader().equals(inviter.getUUID())
                || invite.deadline() < server.getTickCount()) throw new IllegalArgumentException("No active invite from this player");
        if (state.leaders.containsKey(player.getUUID()) || state.groups.containsKey(player.getUUID())) {
            throw new IllegalArgumentException("Leave your current group first");
        }
        state.groups.computeIfAbsent(inviter.getUUID(), ignored -> new LinkedHashSet<>(Set.of(inviter.getUUID())))
                .add(player.getUUID());
        state.leaders.put(player.getUUID(), inviter.getUUID());
        notifyGroup(server, inviter.getUUID(), player.getGameProfile().name() + " joined the portal group");
    }

    public static void leave(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        State state = state(server);
        UUID id = player.getUUID();
        UUID leader = leader(server, id);
        state.invites.remove(id);
        if (leader.equals(id)) {
            LinkedHashSet<UUID> group = state.groups.remove(id);
            if (group == null) return;
            group.forEach(state.leaders::remove);
            for (UUID member : group) {
                ServerPlayer online = server.getPlayerList().getPlayer(member);
                if (online != null) online.sendSystemMessage(Component.literal("Portal group disbanded"));
            }
        } else {
            state.leaders.remove(id);
            LinkedHashSet<UUID> group = state.groups.get(leader);
            if (group != null) group.remove(id);
            notifyGroup(server, leader, player.getGameProfile().name() + " left the portal group");
        }
    }

    public static void finishTravel(MinecraftServer server, UUID leader) {
        LinkedHashSet<UUID> group = state(server).groups.remove(leader);
        if (group != null) group.forEach(state(server).leaders::remove);
    }

    private static void notifyGroup(MinecraftServer server, UUID leader, String message) {
        for (UUID id : members(server, leader)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(Component.literal(message));
        }
    }

    public static List<String> names(MinecraftServer server, UUID member) {
        List<String> names = new ArrayList<>();
        for (UUID id : members(server, member)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            names.add(player == null ? id.toString() : player.getGameProfile().name());
        }
        return names;
    }

    private static final class State {
        private final Map<UUID, UUID> leaders = new HashMap<>();
        private final Map<UUID, LinkedHashSet<UUID>> groups = new HashMap<>();
        private final Map<UUID, Invite> invites = new HashMap<>();
    }

    private record Invite(UUID leader, long deadline) { }
}
