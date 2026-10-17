package ekuzo.crazyworldprogression.portals;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Synchronizes lightweight portal render data without exposing or moving authoritative entry logic. */
public final class PortalNetworking {
    private static final Gson GSON = new Gson();
    private static final Type SNAPSHOT_LIST_TYPE = new TypeToken<List<PortalSnapshot>>() { }.getType();
    private static final Map<MinecraftServer, Integer> LAST_BROADCAST_HASH = new WeakHashMap<>();

    // Prevent construction of the static portal network bridge.
    private PortalNetworking() {
    }

    // Register the clientbound portal snapshot payload during common startup.
    public static void registerPayloadType() {
        PayloadTypeRegistry.clientboundPlay().register(PortalSnapshotPayload.TYPE, PortalSnapshotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PortalVisibilityPayload.TYPE, PortalVisibilityPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PortalEntryPromptPayload.TYPE, PortalEntryPromptPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PortalEntryResponsePayload.TYPE, PortalEntryResponsePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PortalGroupPayload.TYPE, PortalGroupPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PortalGroupActionPayload.TYPE, PortalGroupActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RaidCheckpointPayload.TYPE, RaidCheckpointPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RaidCheckpointResetPayload.TYPE, RaidCheckpointResetPayload.CODEC);
    }

    // Register the response receiver that hands confirmation back to server-authoritative portal logic.
    public static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(RaidCheckpointResetPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Identifier portalId = Identifier.tryParse(payload.portalId());
            if (portalId == null) return;
            if (RaidCheckpoints.reset(player.level().getServer(), player.getUUID(), portalId))
                player.sendSystemMessage(Component.literal("Raid checkpoint reset. Your next run starts at the first encounter."));
            sendCheckpoint(player, portalId);
        });
        ServerPlayNetworking.registerGlobalReceiver(PortalEntryResponsePayload.TYPE,
                (payload, context) -> PortalService.respondToEntryPrompt(
                        context.player(), payload.portalId(), payload.accepted(), payload.riskConfirmed()));
        ServerPlayNetworking.registerGlobalReceiver(PortalGroupActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            MinecraftServer server = player.level().getServer();
            try {
                switch (payload.action()) {
                    case "open" -> { }
                    case "leave" -> PortalGroups.leave(player);
                    case "invite", "accept" -> {
                        ServerPlayer target = server.getPlayerList().getPlayerByName(payload.player());
                        if (target == null) throw new IllegalArgumentException("Player is not online: " + payload.player());
                        if (payload.action().equals("invite")) PortalGroups.invite(player, target);
                        else PortalGroups.accept(player, target);
                    }
                    default -> throw new IllegalArgumentException("Unknown group action");
                }
            } catch (IllegalArgumentException exception) {
                player.sendSystemMessage(Component.literal(exception.getMessage()));
            }
            openGroupScreen(player);
        });
    }

    // Send the current render snapshot to one joining player.
    public static void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, PortalSnapshotPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PortalSnapshotPayload(GSON.toJson(snapshot(player.level().getServer()))));
        }
        if (ServerPlayNetworking.canSend(player, PortalVisibilityPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PortalVisibilityPayload(GSON.toJson(hiddenPlayerDimensions(player.level().getServer()))));
        }
    }

    // Broadcast changed geometry, colors, or schedule state to every compatible client.
    public static void broadcast(MinecraftServer server) {
        List<PortalSnapshot> snapshot = snapshot(server);
        List<String> hiddenDimensions = hiddenPlayerDimensions(server);
        synchronized (LAST_BROADCAST_HASH) {
            LAST_BROADCAST_HASH.put(server, java.util.Objects.hash(snapshot, hiddenDimensions));
        }
        PortalSnapshotPayload payload = new PortalSnapshotPayload(GSON.toJson(snapshot));
        PortalVisibilityPayload visibility = new PortalVisibilityPayload(GSON.toJson(hiddenDimensions));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, PortalSnapshotPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
            if (ServerPlayNetworking.canSend(player, PortalVisibilityPayload.TYPE)) {
                ServerPlayNetworking.send(player, visibility);
            }
        }
    }

    // Broadcast only when a real-time schedule boundary changes an open portal set.
    public static void broadcastIfChanged(MinecraftServer server) {
        List<PortalSnapshot> snapshot = snapshot(server);
        int hash = java.util.Objects.hash(snapshot, hiddenPlayerDimensions(server));
        synchronized (LAST_BROADCAST_HASH) {
            if (LAST_BROADCAST_HASH.getOrDefault(server, Integer.MIN_VALUE) == hash) return;
        }
        broadcast(server);
    }

    // Forget one stopped server's cached synchronization state.
    public static void clear(MinecraftServer server) {
        synchronized (LAST_BROADCAST_HASH) {
            LAST_BROADCAST_HASH.remove(server);
        }
    }

    // Decode an immutable portal snapshot list for client-side storage.
    public static List<PortalSnapshot> decode(String json) {
        List<PortalSnapshot> decoded = GSON.fromJson(json, SNAPSHOT_LIST_TYPE);
        return decoded == null ? List.of() : List.copyOf(decoded);
    }

    // Reduce persistent portal definitions to the fields required for rendering.
    private static List<PortalSnapshot> snapshot(MinecraftServer server) {
        return PortalService.visiblePortals(server).stream().map(portal -> new PortalSnapshot(
                portal.id().toString(), portal.dimension().toString(), portal.shape().serializedName(),
                portal.plane().serializedName(), portal.centerX(), portal.centerY(), portal.centerZ(),
                portal.width(), portal.height(), portal.colorRgb(), PortalService.isOpen(portal))).toList();
    }

    private static List<String> hiddenPlayerDimensions(MinecraftServer server) {
        return PortalData.get(server).instances().values().stream()
                .filter(instance -> {
                    PortalDefinition portal = PortalService.portal(server, instance.portalId());
                    return portal != null && portal.admission().mode() == PortalDefinition.Mode.PUBLIC
                            && !portal.showOtherPlayers();
                })
                .map(instance -> instance.slotDimension().toString())
                .sorted().toList();
    }

    // Ask one client to confirm travel and describe the lifetime it is accepting.
    public static void prompt(ServerPlayer player, PortalDefinition portal, long secondsRemaining,
                              boolean existingInstance) {
        if (RaidService.isRaidPortal(portal)) sendCheckpoint(player, portal.id());
        if (ServerPlayNetworking.canSend(player, PortalEntryPromptPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PortalEntryPromptPayload(portal.id().toString(),
                    portal.effectiveDisplayName(), portal.description(), secondsRemaining, existingInstance,
                    portal.entryPolicy().serializedName(), portal.difficulty(),
                    portal.admission().mode().serializedName(), portal.admission().maxPlayers(),
                    portal.keepInventory(), portal.isExit() ? "exit" : portal.isCoordinates() ? "coordinates" : "pocket"));
        }
    }

    /** Tell the client the player's checkpoint for this raid so the entry screen can show and reset it. */
    static void sendCheckpoint(ServerPlayer player, Identifier portalId) {
        if (!ServerPlayNetworking.canSend(player, RaidCheckpointPayload.TYPE)) return;
        RaidCheckpoints.Checkpoint checkpoint = RaidCheckpoints.checkpoint(player.level().getServer(), player.getUUID(), portalId);
        long secondsLeft = checkpoint == null ? 0L
                : Math.max(0L, (checkpoint.expiresEpochMillis() - java.time.Instant.now().toEpochMilli()) / 1000L);
        ServerPlayNetworking.send(player, new RaidCheckpointPayload(portalId.toString(),
                checkpoint == null ? 0 : checkpoint.encounters(), secondsLeft));
    }

    public record RaidCheckpointPayload(String portalId, int encounters, long secondsLeft) implements CustomPacketPayload {
        public static final Type<RaidCheckpointPayload> TYPE = new Type<>(CrazyWorldProgression.id("raid_checkpoint"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RaidCheckpointPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RaidCheckpointPayload::portalId, ByteBufCodecs.VAR_INT,
                RaidCheckpointPayload::encounters, ByteBufCodecs.VAR_LONG, RaidCheckpointPayload::secondsLeft,
                RaidCheckpointPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RaidCheckpointResetPayload(String portalId) implements CustomPacketPayload {
        public static final Type<RaidCheckpointResetPayload> TYPE = new Type<>(CrazyWorldProgression.id("raid_checkpoint_reset"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RaidCheckpointResetPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RaidCheckpointResetPayload::portalId, RaidCheckpointResetPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void openGroupScreen(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, Parties.StatePayload.TYPE)) {
            Parties.open(player);
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PortalGroupPayload.TYPE)) return;
        MinecraftServer server = player.level().getServer();
        String members = String.join(", ", PortalGroups.names(server, player.getUUID()));
        String online = String.join(", ", server.getPlayerList().getPlayers().stream()
                .map(other -> other.getGameProfile().name()).toList());
        ServerPlayNetworking.send(player, new PortalGroupPayload(members, online,
                PortalGroups.pendingInvite(server, player.getUUID())));
    }

    /** Client-safe geometry and appearance for one placed portal. */
    public record PortalSnapshot(String id, String dimension, String shape, String plane,
                                 double centerX, double centerY, double centerZ,
                                 double width, double height, int colorRgb, boolean open) {
    }

    /** One bounded JSON snapshot carrying all placed portals to a client. */
    public record PortalSnapshotPayload(String json) implements CustomPacketPayload {
        public static final Type<PortalSnapshotPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("portal_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalSnapshotPayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(1_048_576), PortalSnapshotPayload::json,
                        PortalSnapshotPayload::new);

        // Return the payload identifier registered with Fabric's play protocol.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Active public instance dimensions whose players are hidden by default. */
    public record PortalVisibilityPayload(String json) implements CustomPacketPayload {
        public static final Type<PortalVisibilityPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("portal_visibility"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalVisibilityPayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(65_536), PortalVisibilityPayload::json,
                        PortalVisibilityPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Native confirmation-screen content for one proposed portal journey. */
    public record PortalEntryPromptPayload(String portalId, String displayName, String description,
                                           long secondsRemaining, boolean existingInstance, String entryPolicy,
                                           int difficulty, String accessMode, int maxPlayers,
                                           boolean keepInventory, String destinationKind)
            implements CustomPacketPayload {
        public static final Type<PortalEntryPromptPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("portal_entry_prompt"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalEntryPromptPayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PortalEntryPromptPayload::portalId,
                        ByteBufCodecs.STRING_UTF8, PortalEntryPromptPayload::displayName,
                        ByteBufCodecs.stringUtf8(8_192), PortalEntryPromptPayload::description,
                        ByteBufCodecs.VAR_LONG, PortalEntryPromptPayload::secondsRemaining,
                        ByteBufCodecs.BOOL, PortalEntryPromptPayload::existingInstance,
                        ByteBufCodecs.STRING_UTF8, PortalEntryPromptPayload::entryPolicy,
                        ByteBufCodecs.VAR_INT, PortalEntryPromptPayload::difficulty,
                        ByteBufCodecs.STRING_UTF8, PortalEntryPromptPayload::accessMode,
                        ByteBufCodecs.VAR_INT, PortalEntryPromptPayload::maxPlayers,
                        ByteBufCodecs.BOOL, PortalEntryPromptPayload::keepInventory,
                        ByteBufCodecs.STRING_UTF8, PortalEntryPromptPayload::destinationKind,
                        PortalEntryPromptPayload::new);

        // Return the registered clientbound portal-prompt payload type.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PortalGroupPayload(String members, String online, String invitedBy)
            implements CustomPacketPayload {
        public static final Type<PortalGroupPayload> TYPE = new Type<>(CrazyWorldProgression.id("portal_group"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalGroupPayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PortalGroupPayload::members,
                        ByteBufCodecs.STRING_UTF8, PortalGroupPayload::online,
                        ByteBufCodecs.STRING_UTF8, PortalGroupPayload::invitedBy, PortalGroupPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PortalGroupActionPayload(String action, String player) implements CustomPacketPayload {
        public static final Type<PortalGroupActionPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("portal_group_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalGroupActionPayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PortalGroupActionPayload::action,
                        ByteBufCodecs.STRING_UTF8, PortalGroupActionPayload::player,
                        PortalGroupActionPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client decision returned for a specific server-issued portal prompt. */
    public record PortalEntryResponsePayload(String portalId, boolean accepted,
                                             boolean riskConfirmed) implements CustomPacketPayload {
        public static final Type<PortalEntryResponsePayload> TYPE =
                new Type<>(CrazyWorldProgression.id("portal_entry_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalEntryResponsePayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PortalEntryResponsePayload::portalId,
                        ByteBufCodecs.BOOL, PortalEntryResponsePayload::accepted,
                        ByteBufCodecs.BOOL, PortalEntryResponsePayload::riskConfirmed,
                        PortalEntryResponsePayload::new);

        // Return the registered serverbound portal-response payload type.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
