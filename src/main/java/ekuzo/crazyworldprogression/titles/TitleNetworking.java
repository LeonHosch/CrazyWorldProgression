package ekuzo.crazyworldprogression.titles;

import com.google.gson.Gson;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public final class TitleNetworking {
    private static final Gson GSON = new Gson();
    private TitleNetworking() { }
    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OptionsPayload.TYPE, OptionsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> handleAction(context.player(), payload)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> broadcast(server));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
            if (success) server.execute(() -> broadcast(server));
        });
    }
    public static void broadcast(MinecraftServer server) {
        List<Display> titles = new ArrayList<>();
        var definitions = TitleService.definitions(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            TitleService.Definition definition = TitleService.equipped(player, definitions);
            if (definition != null) titles.add(new Display(player.getUUID().toString(), definition.name(),
                    definition.color()));
        }
        Payload payload = new Payload(GSON.toJson(titles));
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (ServerPlayNetworking.canSend(player, Payload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
    /** The server stays authoritative: equip requests go through the same unlock check as /titles equip. */
    private static void handleAction(ServerPlayer player, ActionPayload payload) {
        switch (payload.action()) {
            case "equip" -> {
                try {
                    TitleService.equip(player, Identifier.parse(payload.id()));
                } catch (RuntimeException exception) {
                    CrazyWorldProgression.LOGGER.debug("Rejected title equip from {}: {}",
                            player.getName().getString(), exception.getMessage());
                }
            }
            case "clear" -> TitleService.clear(player);
            default -> { }
        }
        sendOptions(player);
    }

    public static void sendOptions(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, OptionsPayload.TYPE)) return;
        TitleService.Definition equipped = TitleService.equipped(player);
        List<Option> titles = new ArrayList<>();
        for (TitleService.Definition definition : TitleService.unlocked(player))
            titles.add(new Option(definition.id().toString(), definition.name(), definition.color(),
                    definition.achievementId().toString()));
        ServerPlayNetworking.send(player, new OptionsPayload(GSON.toJson(
                new Options(equipped == null ? "" : equipped.id().toString(), titles))));
    }

    public record Display(String playerId, String title, String color) { }
    public record Option(String id, String name, String color, String achievementId) { }
    public record Options(String equipped, List<Option> titles) { }
    public record OptionsPayload(String json) implements CustomPacketPayload {
        public static final Type<OptionsPayload> TYPE = new Type<>(CrazyWorldProgression.id("title_options"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OptionsPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), OptionsPayload::json, OptionsPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** action is "list", "equip" (with id) or "clear". */
    public record ActionPayload(String action, String id) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(CrazyWorldProgression.id("title_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), ActionPayload::action,
                ByteBufCodecs.stringUtf8(256), ActionPayload::id, ActionPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Payload(String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("title_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(65_536), Payload::json, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
