package ekuzo.crazyworldprogression.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily and weekly clan missions, picked at random from a pool when the day or week turns (server time zone):
 * {@link #DAILY} daily and {@link #WEEKLY} weekly missions at once. A mission is a delivery (the clan hands in items
 * with /clan deliver; they are destroyed, so they leave the economy) or finishing dungeons or raids: each clan member
 * inside when an instance is finished counts once, so three members finishing together add three. A finished mission
 * gives the clan its XP, which raises the clan tier.
 */
public final class ClanMissions {
    public static final int DAILY = 3, WEEKLY = 2;

    public enum Kind { DELIVER, DUNGEON, RAID }

    /** One mission of the pool: what to do, how much, its clan XP, and whether it is daily or weekly. */
    public record Mission(String id, Kind kind, Identifier item, int amount, int xp, boolean weekly) {
        public String describe() {
            return switch (kind) {
                case DELIVER -> "Deliver " + amount + " " + BuiltInRegistries.ITEM.getOptional(item)
                        .map(value -> new ItemStack(value).getHoverName().getString()).orElse(String.valueOf(item));
                case DUNGEON -> "Clear dungeons together (" + amount + " member clears)";
                case RAID -> "Clear raids together (" + amount + " member clears)";
            };
        }
    }

    /** The pool missions are picked from. */
    public static final List<Mission> POOL = List.of(
            deliver("wheat", "minecraft:wheat", 256, 150, false),
            deliver("cobblestone", "minecraft:cobblestone", 1024, 100, false),
            deliver("iron", "minecraft:iron_ingot", 64, 200, false),
            deliver("logs", "minecraft:oak_log", 256, 150, false),
            deliver("bones", "minecraft:bone", 128, 150, false),
            deliver("cooked_beef", "minecraft:cooked_beef", 64, 150, false),
            new Mission("dungeon_daily", Kind.DUNGEON, null, 3, 250, false),
            new Mission("raid_daily", Kind.RAID, null, 2, 300, false),
            deliver("diamonds", "minecraft:diamond", 32, 800, true),
            deliver("gold", "minecraft:gold_ingot", 256, 700, true),
            deliver("emeralds", "minecraft:emerald", 64, 700, true),
            new Mission("dungeon_weekly", Kind.DUNGEON, null, 15, 1000, true),
            new Mission("raid_weekly", Kind.RAID, null, 10, 1200, true));

    private static Mission deliver(String id, String item, int amount, int xp, boolean weekly) {
        return new Mission(id, Kind.DELIVER, Identifier.parse(item), amount, xp, weekly);
    }

    public static Mission mission(String id) {
        for (Mission mission : POOL) if (mission.id().equals(id)) return mission;
        return null;
    }

    /** A clan's current missions: the day and week they were picked for, and each one's progress. */
    public record State(long day, long week, List<String> daily, List<String> weekly, Map<String, Integer> progress) {
        static final Codec<State> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("day", -1L).forGetter(State::day),
                Codec.LONG.optionalFieldOf("week", -1L).forGetter(State::week),
                Codec.STRING.listOf().optionalFieldOf("daily", List.of()).forGetter(State::daily),
                Codec.STRING.listOf().optionalFieldOf("weekly", List.of()).forGetter(State::weekly),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("progress", Map.of()).forGetter(State::progress)
        ).apply(instance, State::new));
        static final State EMPTY = new State(-1, -1, List.of(), List.of(), Map.of());

        public State {
            daily = List.copyOf(daily);
            weekly = List.copyOf(weekly);
            progress = Map.copyOf(progress);
        }

        public int progress(String id) { return progress.getOrDefault(id, 0); }

        public List<String> active() {
            List<String> all = new ArrayList<>(daily);
            all.addAll(weekly);
            return all;
        }
    }

    private ClanMissions() { }

    private static long today() { return LocalDate.now(ZoneId.systemDefault()).toEpochDay(); }

    private static long thisWeek() {
        LocalDate date = LocalDate.now(ZoneId.systemDefault());
        return date.get(IsoFields.WEEK_BASED_YEAR) * 100L + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** The clan's missions, picking new ones when the day or week has turned. */
    public static State current(MinecraftServer server, SocialData.Clan clan) {
        State state = clan.missions;
        long day = today(), week = thisWeek();
        if (state.day() == day && state.week() == week) return state;
        RandomSource random = RandomSource.create();
        List<String> daily = state.day() == day ? state.daily() : pick(random, false, DAILY);
        List<String> weekly = state.week() == week ? state.weekly() : pick(random, true, WEEKLY);
        Map<String, Integer> progress = new java.util.HashMap<>();
        for (String id : daily) if (state.day() == day) progress.put(id, state.progress(id));
        for (String id : weekly) if (state.week() == week) progress.put(id, state.progress(id));
        clan.missions = new State(day, week, daily, weekly, progress);
        SocialData.get(server).setDirty();
        return clan.missions;
    }

    private static List<String> pick(RandomSource random, boolean weekly, int count) {
        List<Mission> options = new ArrayList<>(POOL.stream().filter(mission -> mission.weekly() == weekly).toList());
        List<String> picked = new ArrayList<>();
        while (picked.size() < count && !options.isEmpty()) picked.add(options.remove(random.nextInt(options.size())).id());
        return picked;
    }

    /** Adds progress to every active mission of this kind (and item); finishing one gives the clan its XP. */
    static int advance(MinecraftServer server, SocialData.Clan clan, Kind kind, Identifier item, int amount) {
        State state = current(server, clan);
        Map<String, Integer> progress = new java.util.HashMap<>(state.progress());
        int used = 0;
        for (String id : state.active()) {
            Mission mission = mission(id);
            if (mission == null || mission.kind() != kind || (item != null && !item.equals(mission.item()))) continue;
            int before = progress.getOrDefault(id, 0);
            if (before >= mission.amount()) continue;
            int after = Math.min(mission.amount(), before + amount);
            used = Math.max(used, after - before);
            progress.put(id, after);
            if (after >= mission.amount()) complete(server, clan, mission);
        }
        clan.missions = new State(state.day(), state.week(), state.daily(), state.weekly(), progress);
        SocialData.get(server).setDirty();
        return used;
    }

    private static void complete(MinecraftServer server, SocialData.Clan clan, Mission mission) {
        int tierBefore = clan.tier();
        clan.xp += mission.xp();
        Social.tellClan(server, clan, Component.literal("Mission complete: " + mission.describe() + " (+" + mission.xp()
                + " clan XP)").withStyle(ChatFormatting.GOLD));
        if (clan.tier() > tierBefore) Social.tellClan(server, clan, Component.literal("The clan reached tier " + clan.tier()
                + "! It can now have " + clan.memberCap() + " members.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
    }

    /**
     * Hands in the held items for a delivery mission. Only what the missions still need is taken, and it is
     * destroyed.
     */
    static String deliver(ServerPlayer player, SocialData.Clan clan) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) throw new IllegalArgumentException("Hold the items you want to deliver");
        Identifier item = BuiltInRegistries.ITEM.getKey(held.getItem());
        int used = advance(player.level().getServer(), clan, Kind.DELIVER, item, held.getCount());
        if (used <= 0) throw new IllegalArgumentException("No active mission needs " + held.getHoverName().getString());
        String name = held.getHoverName().getString();
        held.shrink(used);
        return "Delivered " + used + " " + name + " for the clan";
    }

    /** An instance was finished with these players inside: each clan's members there count once each. */
    public static void instanceFinished(MinecraftServer server, List<ServerPlayer> players, boolean raid) {
        SocialData data = SocialData.get(server);
        Map<UUID, Integer> perClan = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            SocialData.Clan clan = data.clanOf(player.getUUID());
            if (clan != null) perClan.merge(clan.id(), 1, Integer::sum);
        }
        perClan.forEach((id, count) -> advance(server, data.clan(id), raid ? Kind.RAID : Kind.DUNGEON, null, count));
    }
}
