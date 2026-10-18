package ekuzo.crazyworldprogression.portals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server side of parties: the saved party book, which party each dungeon instance belongs to, chat notices with
 * clickable answers, and the state sent to the party screen.
 */
public final class Parties {
    private static final Map<MinecraftServer, PartyBook> BOOKS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Set<UUID>> VIEWERS = new WeakHashMap<>();

    private Parties() { }

    // ---------------------------------------------------------------- persistence

    record SavedParty(UUID id, String name, String visibility, UUID leader, int maxSize, List<UUID> members,
                      List<UUID> requests) {
        static final Codec<SavedParty> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(SavedParty::id),
                Codec.STRING.fieldOf("name").forGetter(SavedParty::name),
                Codec.STRING.fieldOf("visibility").forGetter(SavedParty::visibility),
                UUIDUtil.STRING_CODEC.fieldOf("leader").forGetter(SavedParty::leader),
                Codec.INT.fieldOf("max_size").forGetter(SavedParty::maxSize),
                UUIDUtil.STRING_CODEC.listOf().fieldOf("members").forGetter(SavedParty::members),
                UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("requests", List.of()).forGetter(SavedParty::requests)
        ).apply(instance, SavedParty::new));
    }

    static final class Data extends SavedData {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                SavedParty.CODEC.listOf().optionalFieldOf("parties", List.of()).forGetter(data -> data.parties),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("leases", Map.of())
                        .forGetter(data -> data.leases),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("names", Map.of())
                        .forGetter(data -> data.names)
        ).apply(instance, Data::new));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(CrazyWorldProgression.id("parties"), Data::new, CODEC, null);
        List<SavedParty> parties = new ArrayList<>();
        /** "slot|created" of a dungeon instance -> the lease key of the party (or solo player) it belongs to. */
        final Map<String, String> leases = new LinkedHashMap<>();
        /** Last known names, so offline party members still show by name. */
        final Map<String, String> names = new HashMap<>();
        Data() { }
        Data(List<SavedParty> parties, Map<String, String> leases, Map<String, String> names) {
            this.parties = new ArrayList<>(parties);
            this.leases.putAll(leases);
            this.names.putAll(names);
        }
    }

    private static Data data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Data.TYPE);
    }

    public static PartyBook book(MinecraftServer server) {
        return BOOKS.computeIfAbsent(server, ignored -> {
            PartyBook book = new PartyBook();
            for (SavedParty party : data(server).parties) {
                try {
                    book.restore(party.id(), party.name(), PartyBook.Visibility.parse(party.visibility()), party.leader(),
                            party.maxSize(), party.members(), party.requests());
                } catch (RuntimeException exception) {
                    CrazyWorldProgression.LOGGER.warn("Skipping a saved party that could not be loaded: {}", exception.getMessage());
                }
            }
            return book;
        });
    }

    private static void save(MinecraftServer server) {
        Data data = data(server);
        data.parties = book(server).all().stream().map(party -> new SavedParty(party.id(), party.name(),
                party.visibility().name(), party.leader(), party.maxSize(), party.members(), party.requests())).toList();
        data.setDirty();
    }

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> act(context.player(), payload.action(), payload.a(), payload.b())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            viewers(server).remove(handler.getPlayer().getUUID());
            server.execute(() -> refresh(server));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Data data = data(server);
            String id = handler.getPlayer().getUUID().toString();
            String name = handler.getPlayer().getGameProfile().name();
            if (!name.equals(data.names.get(id))) {
                data.names.put(id, name);
                data.setDirty();
            }
            server.execute(() -> refresh(server));
        });
    }

    // ---------------------------------------------------------------- dungeon leases

    public static String leaseKey(MinecraftServer server, UUID player) {
        return book(server).leaseKey(player);
    }

    private static String instanceKey(PortalData.StoredInstance instance) {
        return instance.slotDimension() + "|" + instance.createdEpochMillis();
    }

    public static void bindLease(MinecraftServer server, PortalData.StoredInstance instance, String key) {
        Data data = data(server);
        data.leases.keySet().removeIf(existing -> existing.startsWith(instance.slotDimension() + "|"));
        data.leases.put(instanceKey(instance), key);
        data.setDirty();
    }

    /** The lease key an instance belongs to, or "" for instances made before parties existed. */
    public static String leaseOf(MinecraftServer server, PortalData.StoredInstance instance) {
        return data(server).leases.getOrDefault(instanceKey(instance), "");
    }

    private static void adoptSoloInstances(MinecraftServer server, UUID leader, PartyBook.Party party) {
        String solo = "solo:" + leader;
        for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
            if (solo.equals(leaseOf(server, instance))) bindLease(server, instance, "party:" + party.id());
        }
    }

    /** The name of the dungeon a party is currently in, for the browser. */
    private static String activity(MinecraftServer server, PartyBook.Party party) {
        String key = "party:" + party.id();
        for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
            if (!key.equals(leaseOf(server, instance))) continue;
            PortalDefinition portal = PortalService.portal(server, instance.portalId());
            return portal == null ? instance.portalId().toString() : portal.effectiveDisplayName();
        }
        return "";
    }

    private record Entrance(PortalDefinition portal) { }

    /** Use the placed entrance, never the projected copy inside a dungeon instance. */
    private static Entrance entrance(MinecraftServer server, PartyBook.Party party, ServerPlayer inviter) {
        String key = "party:" + party.id();
        for (PortalData.StoredInstance instance : PortalData.get(server).instances().values()) {
            if (!key.equals(leaseOf(server, instance))) continue;
            return PortalService.visiblePortals(server).stream()
                    .filter(candidate -> candidate.id().equals(instance.portalId())
                            && !PortalRegistry.instanceSlots().contains(candidate.dimension()))
                    .map(Entrance::new).findFirst().orElse(null);
        }
        return PortalService.visiblePortals(server).stream()
                .filter(candidate -> candidate.dimension().equals(inviter.level().dimension().identifier())
                        && !candidate.isExit() && !candidate.isCoordinates()
                        && candidate.isNear(inviter.position(), 32.0))
                .min(java.util.Comparator.comparingDouble(candidate -> inviter.position().distanceToSqr(
                        candidate.centerX(), candidate.centerY(), candidate.centerZ())))
                .map(Entrance::new).orElse(null);
    }

    private static MutableComponent activityLink(Entrance entrance) {
        PortalDefinition portal = entrance.portal();
        String command = "/cwpnavigate " + portal.dimension() + " " + portal.centerX() + " "
                + portal.centerY() + " " + portal.centerZ();
        return Component.literal(portal.effectiveDisplayName()).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Guide me to the entrance: "
                        + portal.centerX() + ", " + portal.centerY() + ", " + portal.centerZ()))));
    }

    // ---------------------------------------------------------------- actions

    public static void act(ServerPlayer player, String action, String a, String b) {
        MinecraftServer server = player.level().getServer();
        PartyBook book = book(server);
        UUID me = player.getUUID();
        try {
            switch (action) {
                case "open" -> {
                    viewers(server).add(me);
                    send(player, true);
                    return;
                }
                case "close" -> {
                    viewers(server).remove(me);
                    return;
                }
                case "create" -> {
                    PartyBook.Party party = book.create(me, a.isBlank() ? player.getGameProfile().name() + "'s party" : a,
                            b.isBlank() ? PartyBook.Visibility.PRIVATE : PartyBook.Visibility.parse(b));
                    adoptSoloInstances(server, me, party);
                    tell(player, "Party " + party.name() + " created.");
                }
                case "join" -> {
                    PartyBook.Party party = book.party(UUID.fromString(a));
                    if (book.join(me, UUID.fromString(a))) {
                        notifyParty(server, party, player.getGameProfile().name() + " joined the party.");
                    } else {
                        tell(player, "Join request sent to " + name(server, party.leader()) + ".");
                        ServerPlayer leader = server.getPlayerList().getPlayer(party.leader());
                        if (leader != null) leader.sendSystemMessage(Component.literal(player.getGameProfile().name()
                                        + " wants to join your party. ").withStyle(ChatFormatting.YELLOW)
                                .append(button("[Accept]", ChatFormatting.GREEN, "/party requests accept " + player.getGameProfile().name()))
                                .append(" ")
                                .append(button("[Deny]", ChatFormatting.RED, "/party requests deny " + player.getGameProfile().name())));
                    }
                }
                case "invite" -> {
                    ServerPlayer target = online(server, a);
                    book.invite(me, target.getUUID());
                    PartyBook.Party party = book.partyOf(me);
                    adoptSoloInstances(server, me, party);
                    Entrance entrance = entrance(server, party, player);
                    tell(player, "Invited " + target.getGameProfile().name() + ".");
                    MutableComponent message = Component.literal(player.getGameProfile().name() + " invited you to ")
                            .withStyle(ChatFormatting.YELLOW);
                    message.append(entrance == null ? Component.literal(party.name()) : activityLink(entrance));
                    message.append(Component.literal(": ").withStyle(ChatFormatting.YELLOW));
                    message.append(button("Accept", ChatFormatting.GREEN, "/party accept " + player.getGameProfile().name()));
                    message.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY));
                    message.append(button("Decline", ChatFormatting.RED, "/party decline " + player.getGameProfile().name()));
                    target.sendSystemMessage(message);
                }
                case "accept_invite" -> {
                    PartyBook.Party party = book.acceptInvite(me, partyId(server, a));
                    notifyParty(server, party, player.getGameProfile().name() + " joined the party.");
                }
                case "decline_invite" -> book.declineInvite(me, partyId(server, a));
                case "accept_request" -> {
                    UUID target = playerId(server, a);
                    book.acceptRequest(me, target);
                    notifyParty(server, book.partyOf(me), name(server, target) + " joined the party.");
                }
                case "deny_request" -> book.denyRequest(me, playerId(server, a));
                case "leave" -> {
                    PartyBook.Party party = book.partyOf(me);
                    if (party == null) throw new IllegalArgumentException("You are not in a party");
                    leaveInstanceOf(player, party);
                    book.leave(me);
                    tell(player, "You left the party.");
                    if (book.party(party.id()) != null) notifyParty(server, party, player.getGameProfile().name() + " left the party.");
                }
                case "kick" -> {
                    UUID target = playerId(server, a);
                    PartyBook.Party party = book.partyOf(me);
                    ServerPlayer kicked = server.getPlayerList().getPlayer(target);
                    if (party != null && kicked != null && party.leader().equals(me)) leaveInstanceOf(kicked, party);
                    book.kick(me, target);
                    if (kicked != null) tell(kicked, "You were removed from the party.");
                    notifyParty(server, party, name(server, target) + " was removed from the party.");
                }
                case "promote" -> {
                    UUID target = playerId(server, a);
                    book.promote(me, target);
                    notifyParty(server, book.partyOf(me), name(server, target) + " is now the party leader.");
                }
                case "disband" -> {
                    PartyBook.Party party = book.partyOf(me);
                    if (party != null && party.leader().equals(me)) for (UUID member : party.members()) {
                        ServerPlayer online = server.getPlayerList().getPlayer(member);
                        if (online != null) leaveInstanceOf(online, party);
                    }
                    for (UUID member : book.disband(me)) {
                        ServerPlayer online = server.getPlayerList().getPlayer(member);
                        if (online != null) tell(online, "The party was disbanded.");
                    }
                }
                case "rename" -> book.rename(me, a);
                case "visibility" -> book.setVisibility(me, PartyBook.Visibility.parse(a));
                case "max_size" -> book.setMaxSize(me, Integer.parseInt(a));
                default -> throw new IllegalArgumentException("Unknown party action");
            }
            save(server);
        } catch (RuntimeException exception) {
            player.sendSystemMessage(Component.literal(exception.getMessage() == null ? "That did not work"
                    : exception.getMessage()).withStyle(ChatFormatting.RED));
        }
        refresh(server);
    }

    /**
     * Removes offline members from the party a leader starts a dungeon with, so they do not hold the start up.
     * Returns their names (empty when there is no party or nobody is offline).
     */
    public static List<String> removeOffline(MinecraftServer server, UUID leader) {
        PartyBook book = book(server);
        PartyBook.Party party = book.partyOf(leader);
        if (party == null || !party.leader().equals(leader)) return List.of();
        List<String> removed = new java.util.ArrayList<>();
        for (UUID member : List.copyOf(party.members())) {
            if (member.equals(leader) || server.getPlayerList().getPlayer(member) != null) continue;
            book.kick(leader, member);
            removed.add(name(server, member));
        }
        if (removed.isEmpty()) return removed;
        save(server);
        notifyParty(server, party, String.join(", ", removed) + (removed.size() == 1 ? " was" : " were")
                + " offline and removed from the party when the dungeon started.");
        refresh(server);
        return removed;
    }

    /** /party join <leader>: joins (public) or asks to join (semi-private) the party that player leads or is in. */
    public static void joinLeader(ServerPlayer player, String leaderName) {
        MinecraftServer server = player.level().getServer();
        try {
            act(player, "join", partyId(server, leaderName).toString(), "");
        } catch (RuntimeException exception) {
            player.sendSystemMessage(Component.literal(exception.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /** Someone leaving a party also leaves its dungeon, unless leaving is blocked there right now. */
    private static void leaveInstanceOf(ServerPlayer player, PartyBook.Party party) {
        PortalData.StoredInstance instance = PortalService.currentInstance(player);
        if (instance == null) return;
        if (!("party:" + party.id()).equals(leaseOf(player.level().getServer(), instance))) return;
        PortalService.leave(player);
    }

    // ---------------------------------------------------------------- client state

    private static Set<UUID> viewers(MinecraftServer server) {
        return VIEWERS.computeIfAbsent(server, ignored -> new HashSet<>());
    }

    /** Sends fresh state to everyone with the party screen open. */
    public static void refresh(MinecraftServer server) {
        for (UUID viewer : List.copyOf(viewers(server))) {
            ServerPlayer player = server.getPlayerList().getPlayer(viewer);
            if (player == null) viewers(server).remove(viewer);
            else send(player, false);
        }
    }

    public static void open(ServerPlayer player) {
        act(player, "open", "", "");
    }

    private static void send(ServerPlayer player, boolean open) {
        if (!ServerPlayNetworking.canSend(player, StatePayload.TYPE)) return;
        ServerPlayNetworking.send(player, new StatePayload(state(player).toString(), open));
    }

    static JsonObject state(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        PartyBook book = book(server);
        UUID me = player.getUUID();
        JsonObject root = new JsonObject();
        PartyBook.Party mine = book.partyOf(me);
        if (mine != null) {
            JsonObject party = new JsonObject();
            party.addProperty("name", mine.name());
            party.addProperty("visibility", mine.visibility().name());
            party.addProperty("max", mine.maxSize());
            party.addProperty("leader", mine.leader().equals(me));
            party.addProperty("activity", activity(server, mine));
            JsonArray members = new JsonArray();
            for (UUID member : mine.members()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", member.toString());
                entry.addProperty("name", name(server, member));
                entry.addProperty("online", server.getPlayerList().getPlayer(member) != null);
                entry.addProperty("leader", member.equals(mine.leader()));
                members.add(entry);
            }
            party.add("members", members);
            JsonArray requests = new JsonArray();
            for (UUID request : mine.requests()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", request.toString());
                entry.addProperty("name", name(server, request));
                requests.add(entry);
            }
            party.add("requests", requests);
            root.add("party", party);
        }
        JsonArray invites = new JsonArray();
        for (PartyBook.Party party : book.invitesFor(me)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", party.id().toString());
            entry.addProperty("name", party.name());
            entry.addProperty("leader", name(server, party.leader()));
            invites.add(entry);
        }
        root.add("invites", invites);
        JsonArray browser = new JsonArray();
        for (PartyBook.Party party : book.browser()) {
            if (mine != null && party.id().equals(mine.id())) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", party.id().toString());
            entry.addProperty("name", party.name());
            entry.addProperty("leader", name(server, party.leader()));
            entry.addProperty("members", party.members().size());
            entry.addProperty("max", party.maxSize());
            entry.addProperty("visibility", party.visibility().name());
            entry.addProperty("requested", party.requests().contains(me));
            entry.addProperty("activity", activity(server, party));
            browser.add(entry);
        }
        root.add("browser", browser);
        JsonArray online = new JsonArray();
        for (ServerPlayer other : server.getPlayerList().getPlayers())
            if (!other.getUUID().equals(me)) online.add(other.getGameProfile().name());
        root.add("online", online);
        return root;
    }

    // ---------------------------------------------------------------- helpers

    private static void notifyParty(MinecraftServer server, PartyBook.Party party, String message) {
        if (party == null) return;
        for (UUID member : party.members()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) tell(online, message);
        }
    }

    private static void tell(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.AQUA));
    }

    private static MutableComponent button(String label, ChatFormatting color, String command) {
        return Component.literal(label).withStyle(style -> style.withColor(color).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().name();
        return data(server).names.getOrDefault(id.toString(), id.toString().substring(0, 8));
    }

    private static ServerPlayer online(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name.trim());
        if (player == null) throw new IllegalArgumentException("Player is not online: " + name);
        return player;
    }

    /** Accepts a hidden party ID (from the screen) or the leader's name (from chat buttons and commands). */
    private static UUID partyId(MinecraftServer server, String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { }
        ServerPlayer leader = online(server, value);
        PartyBook.Party party = book(server).partyOf(leader.getUUID());
        if (party == null) throw new IllegalArgumentException(value + " is not in a party");
        return party.id();
    }

    /** Accepts a UUID (from the screen) or a player name (from chat buttons and commands). */
    private static UUID playerId(MinecraftServer server, String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { }
        ServerPlayer player = server.getPlayerList().getPlayerByName(value.trim());
        if (player != null) return player.getUUID();
        for (PartyBook.Party party : book(server).all()) {
            for (UUID member : party.members()) if (name(server, member).equalsIgnoreCase(value.trim())) return member;
            for (UUID request : party.requests()) if (name(server, request).equalsIgnoreCase(value.trim())) return request;
        }
        throw new IllegalArgumentException("Unknown player: " + value);
    }

    // ---------------------------------------------------------------- network payloads

    public record StatePayload(String json, boolean open) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("party_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, ByteBufCodecs.BOOL, StatePayload::open,
                StatePayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ActionPayload(String action, String a, String b) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(CrazyWorldProgression.id("party_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), ActionPayload::action, ByteBufCodecs.stringUtf8(64), ActionPayload::a,
                ByteBufCodecs.stringUtf8(64), ActionPayload::b, ActionPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
