package ekuzo.crazyworldprogression.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World-wide friends and clans: who is friends with whom, open friend requests, every clan with its members, ranks,
 * tier and XP, and the last name seen for each player (for offline names).
 */
public final class SocialData extends SavedData {
    /** Members a clan may have at tier 1 to 5. */
    public static final int[] MEMBER_CAP = {5, 7, 10, 12, 15};
    /** Total clan XP needed for tier 1 to 5. */
    public static final int[] TIER_XP = {0, 1000, 3000, 7000, 15000};

    private static final Codec<Map<UUID, Set<UUID>>> LINKS = Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC.listOf()).xmap(
            map -> {
                Map<UUID, Set<UUID>> result = new HashMap<>();
                map.forEach((key, list) -> result.put(UUID.fromString(key), new LinkedHashSet<>(list)));
                return result;
            },
            map -> {
                Map<String, List<UUID>> result = new HashMap<>();
                map.forEach((key, set) -> { if (!set.isEmpty()) result.put(key.toString(), List.copyOf(set)); });
                return result;
            });
    static final Codec<SocialData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LINKS.optionalFieldOf("friends", Map.of()).forGetter(data -> data.friends),
            LINKS.optionalFieldOf("friend_requests", Map.of()).forGetter(data -> data.requests),
            Clan.CODEC.listOf().optionalFieldOf("clans", List.of()).forGetter(data -> List.copyOf(data.clans.values())),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("names", Map.of()).forGetter(data -> {
                Map<String, String> names = new HashMap<>();
                data.names.forEach((id, name) -> names.put(id.toString(), name));
                return names;
            })
    ).apply(instance, SocialData::new));
    private static final SavedDataType<SocialData> TYPE = new SavedDataType<>(CrazyWorldProgression.id("social"),
            SocialData::new, CODEC, null);

    /** Each player's friends (kept both ways). */
    final Map<UUID, Set<UUID>> friends = new HashMap<>();
    /** Requests a player has received: target to the players who asked. */
    final Map<UUID, Set<UUID>> requests = new HashMap<>();
    final Map<UUID, Clan> clans = new LinkedHashMap<>();
    final Map<UUID, String> names = new HashMap<>();

    private SocialData() { }

    private SocialData(Map<UUID, Set<UUID>> friends, Map<UUID, Set<UUID>> requests, List<Clan> clans, Map<String, String> names) {
        friends.forEach((key, set) -> this.friends.put(key, new LinkedHashSet<>(set)));
        requests.forEach((key, set) -> this.requests.put(key, new LinkedHashSet<>(set)));
        for (Clan clan : clans) this.clans.put(clan.id, clan);
        names.forEach((id, name) -> this.names.put(UUID.fromString(id), name));
    }

    public static SocialData get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }

    // ---------------------------------------------------------------- friends

    public boolean friends(UUID a, UUID b) { return friends.getOrDefault(a, Set.of()).contains(b); }

    public Set<UUID> friendsOf(UUID player) { return Set.copyOf(friends.getOrDefault(player, Set.of())); }

    public Set<UUID> requestsFor(UUID player) { return Set.copyOf(requests.getOrDefault(player, Set.of())); }

    /** Sends a request, or makes them friends right away when the other player already asked. Returns true for that. */
    boolean requestFriend(UUID from, UUID to) {
        if (from.equals(to)) throw new IllegalArgumentException("You can't befriend yourself");
        if (friends(from, to)) throw new IllegalArgumentException("You are already friends");
        if (requests.getOrDefault(from, Set.of()).contains(to)) {
            befriend(from, to);
            return true;
        }
        if (!requests.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(from))
            throw new IllegalArgumentException("You already sent a request");
        setDirty();
        return false;
    }

    void acceptFriend(UUID player, UUID from) {
        if (!requests.getOrDefault(player, Set.of()).contains(from)) throw new IllegalArgumentException("No request from this player");
        befriend(player, from);
    }

    void denyFriend(UUID player, UUID from) {
        if (!requests.getOrDefault(player, new LinkedHashSet<>()).remove(from)) throw new IllegalArgumentException("No request from this player");
        setDirty();
    }

    void removeFriend(UUID player, UUID other) {
        boolean removed = friends.getOrDefault(player, new LinkedHashSet<>()).remove(other);
        friends.getOrDefault(other, new LinkedHashSet<>()).remove(player);
        if (!removed) throw new IllegalArgumentException("You are not friends");
        setDirty();
    }

    private void befriend(UUID a, UUID b) {
        friends.computeIfAbsent(a, ignored -> new LinkedHashSet<>()).add(b);
        friends.computeIfAbsent(b, ignored -> new LinkedHashSet<>()).add(a);
        requests.getOrDefault(a, new LinkedHashSet<>()).remove(b);
        requests.getOrDefault(b, new LinkedHashSet<>()).remove(a);
        setDirty();
    }

    // ---------------------------------------------------------------- clans

    public Clan clan(UUID id) { return id == null ? null : clans.get(id); }

    public Clan clanOf(UUID player) {
        for (Clan clan : clans.values()) if (clan.members.contains(player)) return clan;
        return null;
    }

    public Clan clanByTag(String tag) {
        for (Clan clan : clans.values()) if (clan.tag.equalsIgnoreCase(tag.trim())) return clan;
        return null;
    }

    public List<Clan> clans() { return List.copyOf(clans.values()); }

    Clan createClan(UUID leader, String tag, String name) {
        if (clanOf(leader) != null) throw new IllegalArgumentException("Leave your clan first");
        tag = tag.trim().toUpperCase(Locale.ROOT);
        name = name.trim();
        if (!tag.matches("[A-Z0-9]{2,5}")) throw new IllegalArgumentException("The tag needs 2 to 5 letters or digits");
        if (name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("The name needs 1 to 32 characters");
        if (clanByTag(tag) != null) throw new IllegalArgumentException("The tag " + tag + " is taken");
        for (Clan clan : clans.values())
            if (clan.name.equalsIgnoreCase(name)) throw new IllegalArgumentException("The name " + name + " is taken");
        Clan clan = new Clan(UUID.randomUUID(), tag, name, leader, List.of(), List.of(leader), 0, ClanMissions.State.EMPTY);
        clans.put(clan.id, clan);
        setDirty();
        return clan;
    }

    void disband(Clan clan) {
        clans.remove(clan.id);
        setDirty();
    }

    void name(UUID player, String name) {
        if (!name.equals(names.put(player, name))) setDirty();
    }

    /** The last name seen for this player, or a short form of the ID. */
    public String name(UUID player) { return names.getOrDefault(player, player.toString().substring(0, 8)); }

    public UUID idByName(String name) {
        for (var entry : names.entrySet()) if (entry.getValue().equalsIgnoreCase(name.trim())) return entry.getKey();
        return null;
    }

    /**
     * Clan ranks: members open clan chests; officers also lock chests to the clan and break clan chests; co-leaders
     * and the leader also invite, kick and set members' and officers' ranks; only the leader names co-leaders, hands
     * over the clan and disbands it.
     */
    public enum Rank {
        MEMBER("Member"), OFFICER("Officer"), CO_LEADER("Co-leader"), LEADER("Leader");

        private final String label;

        Rank(String label) { this.label = label; }

        public String label() { return label; }
    }

    /** A clan: tag, name, leader, co-leaders, officers, members (everyone, ranks included), XP and missions. */
    public static final class Clan {
        static final Codec<Clan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(clan -> clan.id),
                Codec.STRING.fieldOf("tag").forGetter(clan -> clan.tag),
                Codec.STRING.fieldOf("name").forGetter(clan -> clan.name),
                UUIDUtil.CODEC.fieldOf("leader").forGetter(clan -> clan.leader),
                UUIDUtil.CODEC.listOf().optionalFieldOf("officers", List.of()).forGetter(clan -> List.copyOf(clan.officers)),
                UUIDUtil.CODEC.listOf().optionalFieldOf("co_leaders", List.of()).forGetter(clan -> List.copyOf(clan.coLeaders)),
                UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(clan -> List.copyOf(clan.members)),
                Codec.INT.optionalFieldOf("xp", 0).forGetter(clan -> clan.xp),
                ClanMissions.State.CODEC.optionalFieldOf("missions", ClanMissions.State.EMPTY).forGetter(clan -> clan.missions)
        ).apply(instance, (id, tag, name, leader, officers, coLeaders, members, xp, missions) ->
                new Clan(id, tag, name, leader, officers, coLeaders, members, xp, missions)));

        final UUID id;
        final String tag;
        String name;
        UUID leader;
        final LinkedHashSet<UUID> officers = new LinkedHashSet<>();
        final LinkedHashSet<UUID> coLeaders = new LinkedHashSet<>();
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        int xp;
        ClanMissions.State missions;

        Clan(UUID id, String tag, String name, UUID leader, List<UUID> officers, List<UUID> members, int xp,
             ClanMissions.State missions) {
            this(id, tag, name, leader, officers, List.of(), members, xp, missions);
        }

        Clan(UUID id, String tag, String name, UUID leader, List<UUID> officers, List<UUID> coLeaders, List<UUID> members,
             int xp, ClanMissions.State missions) {
            this.id = id; this.tag = tag; this.name = name; this.leader = leader; this.xp = xp; this.missions = missions;
            this.officers.addAll(officers);
            this.coLeaders.addAll(coLeaders);
            this.members.addAll(members);
            this.members.add(leader);
        }

        public UUID id() { return id; }
        public String tag() { return tag; }
        public String name() { return name; }
        public UUID leader() { return leader; }
        public List<UUID> members() { return new ArrayList<>(members); }
        public Rank rank(UUID player) {
            if (player.equals(leader)) return Rank.LEADER;
            if (coLeaders.contains(player)) return Rank.CO_LEADER;
            if (officers.contains(player)) return Rank.OFFICER;
            return Rank.MEMBER;
        }

        /** Sets a member's rank below leader. */
        void setRank(UUID player, Rank rank) {
            officers.remove(player);
            coLeaders.remove(player);
            if (rank == Rank.OFFICER) officers.add(player);
            if (rank == Rank.CO_LEADER) coLeaders.add(player);
        }

        void remove(UUID player) {
            members.remove(player);
            officers.remove(player);
            coLeaders.remove(player);
        }

        /** Co-leaders and the leader invite, kick and set ranks. */
        public boolean manager(UUID player) { return rank(player).ordinal() >= Rank.CO_LEADER.ordinal(); }

        /** Officers and up lock chests to the clan and break clan chests. */
        public boolean chestKeeper(UUID player) { return members.contains(player) && rank(player).ordinal() >= Rank.OFFICER.ordinal(); }
        public int xp() { return xp; }

        /** Tier 1 to 5 from the clan's XP. */
        public int tier() {
            int tier = 1;
            while (tier < TIER_XP.length && xp >= TIER_XP[tier]) tier++;
            return tier;
        }

        public int memberCap() { return MEMBER_CAP[tier() - 1]; }
    }
}
