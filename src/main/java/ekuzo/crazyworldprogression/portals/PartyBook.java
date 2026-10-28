package ekuzo.crazyworldprogression.portals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/**
 * Party rules without any server objects, so they can be tested directly. Each party has a hidden ID that binds
 * dungeon instances to it; players outside a party use a personal solo key instead.
 */
public final class PartyBook {
    public static final int DEFAULT_MAX_SIZE = 5;
    public static final int LIMIT = 10;

    public enum Visibility {
        PUBLIC, SEMI_PRIVATE, PRIVATE, FRIENDS, CLAN;
        public String label() {
            return switch (this) {
                case PUBLIC -> "Public"; case SEMI_PRIVATE -> "Semi-private"; case PRIVATE -> "Private";
                case FRIENDS -> "Friends"; case CLAN -> "Clan";
            };
        }
        public Visibility next() { return values()[(ordinal() + 1) % values().length]; }
        public static Visibility parse(String value) {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
        }
    }

    public static final class Party {
        final UUID id;
        String name;
        Visibility visibility;
        UUID leader;
        int maxSize;
        /** Whether members can hurt each other (allies who heal allies always reach them). */
        boolean pvp;
        /** Open voice preserves two-way proximity audio; private voice isolates the party. */
        boolean privateVoice;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        final LinkedHashSet<UUID> requests = new LinkedHashSet<>();

        Party(UUID id, String name, Visibility visibility, UUID leader, int maxSize) {
            this.id = id; this.name = name; this.visibility = visibility; this.leader = leader; this.maxSize = maxSize;
            members.add(leader);
        }
        public UUID id() { return id; }
        public String name() { return name; }
        public Visibility visibility() { return visibility; }
        public UUID leader() { return leader; }
        public int maxSize() { return maxSize; }
        public boolean pvp() { return pvp; }
        public boolean privateVoice() { return privateVoice; }
        public List<UUID> members() { return List.copyOf(members); }
        public List<UUID> requests() { return List.copyOf(requests); }
        public boolean full() { return members.size() >= maxSize; }
    }

    private final Map<UUID, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberOf = new HashMap<>();
    /** invitee -> party ids that invited them */
    private final Map<UUID, LinkedHashSet<UUID>> invites = new HashMap<>();

    public Party partyOf(UUID player) {
        UUID id = memberOf.get(player);
        return id == null ? null : parties.get(id);
    }

    public Party party(UUID id) { return parties.get(id); }

    public List<Party> all() { return List.copyOf(parties.values()); }

    /** Disbands parties that have no online members. Offline members of active parties are retained. */
    public boolean disbandInactive(Set<UUID> online) {
        List<UUID> inactive = parties.values().stream()
                .filter(party -> party.members.stream().noneMatch(online::contains))
                .map(Party::id).toList();
        for (UUID id : inactive) {
            Party party = parties.remove(id);
            party.members.forEach(memberOf::remove);
            invites.values().forEach(set -> set.remove(id));
        }
        return !inactive.isEmpty();
    }

    /** The key dungeon instances are leased to: the hidden party ID, or a personal key when not in a party. */
    public String leaseKey(UUID player) {
        Party party = partyOf(player);
        return party == null ? "solo:" + player : "party:" + party.id;
    }

    /**
     * Friends and Clan parties: whether a player belongs to the leader's circle (their friends, or their clan). Kept
     * outside so the rules stay free of server objects.
     */
    public interface Circle { boolean contains(Visibility visibility, UUID leader, UUID player); }

    /** Public and semi-private parties, as listed in the party browser. Private parties never appear. */
    public List<Party> browser() { return browser(null, (visibility, leader, player) -> false); }

    /** The browser for one player: also the Friends and Clan parties whose leader's circle they are in. */
    public List<Party> browser(UUID viewer, Circle circle) {
        return parties.values().stream().filter(party -> switch (party.visibility) {
            case PUBLIC, SEMI_PRIVATE -> true;
            case PRIVATE -> false;
            case FRIENDS, CLAN -> viewer != null && circle.contains(party.visibility, party.leader, viewer);
        }).toList();
    }

    public List<Party> invitesFor(UUID player) {
        List<Party> result = new ArrayList<>();
        for (UUID id : invites.getOrDefault(player, new LinkedHashSet<>())) if (parties.containsKey(id)) result.add(parties.get(id));
        return result;
    }

    public Party create(UUID leader, String name, Visibility visibility) {
        if (memberOf.containsKey(leader)) throw new IllegalArgumentException("Leave your current party first");
        Party party = new Party(UUID.randomUUID(), cleanName(name), visibility, leader, DEFAULT_MAX_SIZE);
        parties.put(party.id, party);
        memberOf.put(leader, party.id);
        invites.remove(leader);
        dropRequestsBy(leader);
        return party;
    }

    /** Public: join at once when not full. Semi-private: becomes a join request for the leader. */
    public boolean join(UUID player, UUID partyId) { return join(player, partyId, (visibility, leader, joiner) -> false); }

    /** Friends and Clan parties: join at once when in the leader's circle (or invited). */
    public boolean join(UUID player, UUID partyId, Circle circle) {
        Party party = require(partyId);
        if (memberOf.containsKey(player)) throw new IllegalArgumentException("Leave your current party first");
        if (party.visibility == Visibility.PRIVATE && !invited(player, partyId))
            throw new IllegalArgumentException("That party is private; its leader has to invite you");
        if ((party.visibility == Visibility.FRIENDS || party.visibility == Visibility.CLAN) && !invited(player, partyId)
                && !circle.contains(party.visibility, party.leader, player))
            throw new IllegalArgumentException(party.visibility == Visibility.FRIENDS
                    ? "Only the leader's friends can join that party" : "Only the leader's clan can join that party");
        if (party.full()) throw new IllegalArgumentException("That party is full");
        if (party.visibility == Visibility.SEMI_PRIVATE && !invited(player, partyId)) {
            party.requests.add(player);
            return false;
        }
        add(party, player);
        return true;
    }

    public void invite(UUID leader, UUID target) {
        Party party = leading(leader);
        if (party.members.contains(target)) throw new IllegalArgumentException("Already in your party");
        if (memberOf.containsKey(target)) throw new IllegalArgumentException("That player is already in a party");
        if (party.full()) throw new IllegalArgumentException("Your party is full");
        invites.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(party.id);
    }

    public Party acceptInvite(UUID player, UUID partyId) {
        if (!invited(player, partyId)) throw new IllegalArgumentException("No invite from that party");
        Party party = require(partyId);
        if (memberOf.containsKey(player)) throw new IllegalArgumentException("Leave your current party first");
        if (party.full()) throw new IllegalArgumentException("That party is full");
        add(party, player);
        return party;
    }

    public void declineInvite(UUID player, UUID partyId) {
        LinkedHashSet<UUID> pending = invites.get(player);
        if (pending != null) pending.remove(partyId);
    }

    public void acceptRequest(UUID leader, UUID player) {
        Party party = leading(leader);
        if (!party.requests.remove(player)) throw new IllegalArgumentException("No request from that player");
        if (memberOf.containsKey(player)) throw new IllegalArgumentException("That player joined another party");
        if (party.full()) throw new IllegalArgumentException("Your party is full");
        add(party, player);
    }

    public void denyRequest(UUID leader, UUID player) {
        leading(leader).requests.remove(player);
    }

    /** Leaving hands leadership to the next member; the last member leaving disbands the party. */
    public Party leave(UUID player) {
        Party party = partyOf(player);
        if (party == null) throw new IllegalArgumentException("You are not in a party");
        party.members.remove(player);
        memberOf.remove(player);
        if (party.members.isEmpty()) {
            parties.remove(party.id);
            invites.values().forEach(set -> set.remove(party.id));
        } else if (party.leader.equals(player)) {
            party.leader = party.members.iterator().next();
        }
        return party;
    }

    public void kick(UUID leader, UUID member) {
        Party party = leading(leader);
        if (leader.equals(member)) throw new IllegalArgumentException("Use leave instead");
        if (!party.members.remove(member)) throw new IllegalArgumentException("Not in your party");
        memberOf.remove(member);
    }

    public void promote(UUID leader, UUID member) {
        Party party = leading(leader);
        if (!party.members.contains(member)) throw new IllegalArgumentException("Not in your party");
        party.leader = member;
    }

    public List<UUID> disband(UUID leader) {
        Party party = leading(leader);
        List<UUID> members = List.copyOf(party.members);
        members.forEach(memberOf::remove);
        parties.remove(party.id);
        invites.values().forEach(set -> set.remove(party.id));
        return members;
    }

    public void rename(UUID leader, String name) { leading(leader).name = cleanName(name); }

    public void setVisibility(UUID leader, Visibility visibility) { leading(leader).visibility = visibility; }

    public void setMaxSize(UUID leader, int maxSize) {
        Party party = leading(leader);
        if (maxSize < Math.max(1, party.members.size()) || maxSize > LIMIT)
            throw new IllegalArgumentException("Party size must be between " + Math.max(1, party.members.size()) + " and " + LIMIT);
        party.maxSize = maxSize;
    }

    public void setPvp(UUID leader, boolean pvp) { leading(leader).pvp = pvp; }

    public void setPrivateVoice(UUID leader, boolean privateVoice) { leading(leader).privateVoice = privateVoice; }

    /** Restores a saved party (server start). */
    void restore(UUID id, String name, Visibility visibility, UUID leader, int maxSize, boolean pvp,
                 boolean privateVoice, List<UUID> members,
                 List<UUID> requests) {
        Party party = new Party(id, name, visibility, leader, maxSize);
        party.pvp = pvp;
        party.privateVoice = privateVoice;
        for (UUID member : members) if (!memberOf.containsKey(member)) { party.members.add(member); memberOf.put(member, id); }
        memberOf.put(leader, id);
        party.requests.addAll(requests);
        parties.put(id, party);
    }

    private void add(Party party, UUID player) {
        party.members.add(player);
        memberOf.put(player, party.id);
        invites.remove(player);
        dropRequestsBy(player);
    }

    private void dropRequestsBy(UUID player) {
        parties.values().forEach(party -> party.requests.remove(player));
    }

    private boolean invited(UUID player, UUID partyId) {
        return invites.getOrDefault(player, new LinkedHashSet<>()).contains(partyId);
    }

    private Party require(UUID partyId) {
        Party party = parties.get(partyId);
        if (party == null) throw new IllegalArgumentException("That party no longer exists");
        return party;
    }

    private Party leading(UUID leader) {
        Party party = partyOf(leader);
        if (party == null) throw new IllegalArgumentException("You are not in a party");
        if (!party.leader.equals(leader)) throw new IllegalArgumentException("Only the party leader can do that");
        return party;
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) trimmed = "Party";
        return trimmed.length() > 24 ? trimmed.substring(0, 24) : trimmed;
    }
}
