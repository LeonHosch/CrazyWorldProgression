package ekuzo.crazyworldprogression.progression.skilltrees;

import com.google.gson.Gson;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class SkillTreeNetworking {
    private static final Gson GSON = new Gson();

    // Prevent this static network bridge from being instantiated.
    private SkillTreeNetworking() {
    }

    // Register every clientbound and serverbound payload codec during common startup.
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(RequestSkillTreesPayload.TYPE, RequestSkillTreesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PurchaseSkillPayload.TYPE, PurchaseSkillPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SkillTreeSnapshotPayload.TYPE, SkillTreeSnapshotPayload.CODEC);
    }

    // Register server receivers that answer screen requests and validate purchases.
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(RequestSkillTreesPayload.TYPE, (payload, context) ->
                open(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(PurchaseSkillPayload.TYPE, (payload, context) -> {
            SkillTreeService.purchase(
                    context.player(),
                    payload.treeId(),
                    payload.skillId()
            );
            open(context.player());
        });
    }

    // Send a fresh snapshot that opens or refreshes the skill-tree screen.
    public static void open(ServerPlayer player) {
        SkillTreeService.SkillTreeSnapshot snapshot = SkillTreeService.createSnapshot(player);
        ServerPlayNetworking.send(player, new SkillTreeSnapshotPayload(GSON.toJson(snapshot)));
    }

    // Decode a server snapshot JSON document for the client screen.
    public static SkillTreeService.SkillTreeSnapshot decodeSnapshot(String json) {
        return GSON.fromJson(json, SkillTreeService.SkillTreeSnapshot.class);
    }

    public record RequestSkillTreesPayload() implements CustomPacketPayload {
        public static final Type<RequestSkillTreesPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("request_skill_trees"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSkillTreesPayload> CODEC =
                StreamCodec.unit(new RequestSkillTreesPayload());

        // Return the registered payload type used by the network protocol.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PurchaseSkillPayload(String treeId, String skillId) implements CustomPacketPayload {
        public static final Type<PurchaseSkillPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("purchase_skill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PurchaseSkillPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                PurchaseSkillPayload::treeId,
                ByteBufCodecs.STRING_UTF8,
                PurchaseSkillPayload::skillId,
                PurchaseSkillPayload::new
        );

        // Return the registered payload type used by the network protocol.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SkillTreeSnapshotPayload(String json) implements CustomPacketPayload {
        public static final Type<SkillTreeSnapshotPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("skill_tree_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SkillTreeSnapshotPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(1_048_576),
                        SkillTreeSnapshotPayload::json,
                        SkillTreeSnapshotPayload::new
                );

        // Return the registered payload type used by the network protocol.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
