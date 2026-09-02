package ekuzo.crazyworldprogression.selection;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Tiny selection-mode synchronization channel used for immediate client interaction prediction. */
public final class AreaSelectionNetworking {
    // Prevent construction of the static network helper.
    private AreaSelectionNetworking() {
    }

    // Register the selection-mode payload during common initialization.
    public static void registerPayloadType() {
        PayloadTypeRegistry.serverboundPlay().register(SelectionPointPayload.TYPE, SelectionPointPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SelectionModePayload.TYPE, SelectionModePayload.CODEC);
    }

    // Receive explicit selector clicks because the client suppresses the underlying block interaction.
    public static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(SelectionPointPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            BlockPos position = new BlockPos(payload.x(), payload.y(), payload.z());
            AreaSelectionService.selectPoint(player, position, payload.first());
        });
    }

    // Tell one client whether left/right block clicks belong to the area selector.
    static void send(ServerPlayer player, boolean active) {
        if (ServerPlayNetworking.canSend(player, SelectionModePayload.TYPE)) {
            ServerPlayNetworking.send(player, new SelectionModePayload(active));
        }
    }

    /** One left- or right-clicked block sent to the authoritative selector. */
    public record SelectionPointPayload(int x, int y, int z, boolean first) implements CustomPacketPayload {
        public static final Type<SelectionPointPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("area_selection_point"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectionPointPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SelectionPointPayload::x,
                        ByteBufCodecs.VAR_INT, SelectionPointPayload::y,
                        ByteBufCodecs.VAR_INT, SelectionPointPayload::z,
                        ByteBufCodecs.BOOL, SelectionPointPayload::first,
                        SelectionPointPayload::new);

        // Return the protocol identifier for this selection click.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server-authoritative selection-mode flag. */
    public record SelectionModePayload(boolean active) implements CustomPacketPayload {
        public static final Type<SelectionModePayload> TYPE =
                new Type<>(CrazyWorldProgression.id("area_selection_mode"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectionModePayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, SelectionModePayload::active, SelectionModePayload::new);

        // Return the protocol identifier for this selection update.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
