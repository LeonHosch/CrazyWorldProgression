package ekuzo.crazyworldprogression.portals;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The older group API, now answered by the party system so there is only one kind of group. */
public final class PortalGroups {
    private PortalGroups() { }

    public static void clear(MinecraftServer server) { }

    /** The party leader, or the player themself when not in a party. */
    public static UUID leader(MinecraftServer server, UUID member) {
        PartyBook.Party party = Parties.book(server).partyOf(member);
        return party == null ? member : party.leader();
    }

    /** All party members (online or not), or just the player when not in a party. */
    public static List<UUID> members(MinecraftServer server, UUID member) {
        PartyBook.Party party = Parties.book(server).partyOf(member);
        return party == null ? List.of(member) : party.members();
    }

    public static void invite(ServerPlayer inviter, ServerPlayer target) {
        MinecraftServer server = inviter.level().getServer();
        if (Parties.book(server).partyOf(inviter.getUUID()) == null) Parties.act(inviter, "create", "", "PRIVATE");
        Parties.act(inviter, "invite", target.getGameProfile().name(), "");
    }

    public static void accept(ServerPlayer player, ServerPlayer inviter) {
        Parties.act(player, "accept_invite", inviter.getGameProfile().name(), "");
    }

    public static void leave(ServerPlayer player) {
        if (Parties.book(player.level().getServer()).partyOf(player.getUUID()) != null) Parties.act(player, "leave", "", "");
    }

    public static void finishTravel(MinecraftServer server, UUID leader) { }

    public static List<String> names(MinecraftServer server, UUID member) {
        List<String> names = new ArrayList<>();
        for (UUID id : members(server, member)) names.add(Parties.name(server, id));
        return names;
    }

    public static String pendingInvite(MinecraftServer server, UUID playerId) {
        List<PartyBook.Party> invites = Parties.book(server).invitesFor(playerId);
        return invites.isEmpty() ? "" : Parties.name(server, invites.getFirst().leader());
    }
}
