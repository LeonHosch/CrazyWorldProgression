package ekuzo.crazyworldprogression.buildings;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exact non-air building blocks shown only in the pocket's editor dimension. */
public final class BuildingPreviewNetworking {
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Preview>>() { }.getType();
    private static final Map<UUID, String> LAST_SENT = new HashMap<>();

    private BuildingPreviewNetworking() { }

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

    public static List<Preview> decode(String json) {
        List<Preview> values = GSON.fromJson(json, LIST_TYPE);
        return values == null ? List.of() : List.copyOf(values);
    }

    private static List<Preview> snapshot(MinecraftServer server, Identifier pocket, Identifier dimension) {
        List<Preview> result = new ArrayList<>();
        for (String scriptId : PocketEditorService.scripts(server, pocket)) {
            MobScript script = MobForge.SCRIPTS.get(scriptId).orElse(null);
            if (script == null) continue;
            for (MobScript.Step step : script.steps()) {
                if (!(step instanceof MobScript.BuildingStep building) || !building.action().equals("add")) continue;
                BuildingService.BuildingInfo info = BuildingService.building(server, building.buildId());
                if (info == null) continue;
                var template = server.getStructureManager().get(Identifier.fromNamespaceAndPath(
                        building.buildId().getNamespace(), "cwp_buildings/" + building.buildId().getPath())).orElse(null);
                if (template == null) continue;
                CompoundTag tag = template.save(new CompoundTag());
                var palette = tag.getListOrEmpty("palette");
                var blocks = tag.getListOrEmpty("blocks");
                BitSet occupied = new BitSet(info.sizeX() * info.sizeY() * info.sizeZ());
                for (int index = 0; index < blocks.size(); index++) {
                    var block = blocks.getCompoundOrEmpty(index);
                    int stateIndex = block.getIntOr("state", -1);
                    if (stateIndex < 0 || stateIndex >= palette.size()) continue;
                    String blockId = palette.getCompoundOrEmpty(stateIndex).getStringOr("Name", "minecraft:air");
                    if (blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air")
                            || blockId.equals("minecraft:void_air")) continue;
                    var pos = block.getListOrEmpty("pos");
                    if (pos.size() != 3) continue;
                    int x = pos.getIntOr(0, -1), y = pos.getIntOr(1, -1), z = pos.getIntOr(2, -1);
                    if (x >= 0 && x < info.sizeX() && y >= 0 && y < info.sizeY() && z >= 0 && z < info.sizeZ())
                        occupied.set((y * info.sizeZ() + z) * info.sizeX() + x);
                }
                BlockPos origin = BlockPos.containing(building.location().x(), building.location().y(), building.location().z());
                result.add(new Preview(dimension.toString(), origin.getX(), origin.getY(), origin.getZ(),
                        info.sizeX(), info.sizeY(), info.sizeZ(), Base64.getEncoder().encodeToString(occupied.toByteArray())));
            }
        }
        return result;
    }

    public record Preview(String dimension, int x, int y, int z, int sizeX, int sizeY, int sizeZ, String blocks) { }

    public record Payload(String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("building_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1_048_576), Payload::json, Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
