package ekuzo.crazyworldprogression.portals;

import com.google.gson.Gson;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Preview scripted portal placements in their pocket's authoring dimension. */
public final class PortalPreviewNetworking {
    private static final Gson GSON = new Gson();
    private static final Map<UUID, String> LAST_SENT = new HashMap<>();

    private PortalPreviewNetworking() { }

    public static void registerPayloadType() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 40 != 0) return;
        LAST_SENT.keySet().retainAll(server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        Map<Identifier, String> snapshots = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Identifier dimension = player.level().dimension().identifier();
            Identifier pocket = PocketEditorService.pocketForDimension(server, dimension);
            if (pocket == null) {
                LAST_SENT.remove(player.getUUID());
                continue;
            }
            String json = snapshots.computeIfAbsent(pocket, id -> GSON.toJson(snapshot(server, id, dimension)));
            String key = dimension + "|" + json;
            if (key.equals(LAST_SENT.get(player.getUUID()))) continue;
            if (ServerPlayNetworking.canSend(player, Payload.TYPE)) {
                ServerPlayNetworking.send(player, new Payload(json));
                LAST_SENT.put(player.getUUID(), key);
            }
        }
    }

    static List<PortalNetworking.PortalSnapshot> snapshot(MinecraftServer server, Identifier pocket,
                                                           Identifier dimension) {
        List<PortalNetworking.PortalSnapshot> result = new ArrayList<>();
        for (String scriptId : PocketEditorService.scripts(server, pocket)) {
            MobScript script = MobForge.SCRIPTS.get(scriptId).orElse(null);
            if (script == null) continue;
            for (MobScript.Step step : script.steps()) {
                if (!(step instanceof MobScript.PortalLocationStep placement)
                        || !placement.action().equals("add")) continue;
                PortalDefinition preset = PortalService.portal(server, placement.portalId());
                if (preset == null) continue;
                result.add(new PortalNetworking.PortalSnapshot(
                        placement.portalId() + "/" + placement.locationId(), dimension.toString(),
                        preset.shape().serializedName(), preset.plane().serializedName(),
                        placement.position().x(), placement.position().y(), placement.position().z(),
                        preset.width(), preset.height(), 0xB455F5, true));
            }
        }
        return result;
    }

    public record Payload(String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("portal_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1_048_576), Payload::json, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
