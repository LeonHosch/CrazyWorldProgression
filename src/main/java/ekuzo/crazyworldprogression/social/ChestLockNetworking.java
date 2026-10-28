package ekuzo.crazyworldprogression.social;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The lock button in the chest window: when a player opens a chest or barrel the server sends its lock
 * ({@link InfoPayload}); the button asks for another mode ({@link SetPayload}), and the server answers with the new
 * lock or why not.
 */
public final class ChestLockNetworking {
    /** The opened chest's lock: mode, readable text, whether this player may change it, and their limits. */
    public record InfoPayload(BlockPos pos, String mode, String text, boolean manage, boolean inClan, String usage,
                              String error) implements CustomPacketPayload {
        public static final Type<InfoPayload> TYPE = new Type<>(CrazyWorldProgression.id("chest_lock_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InfoPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, InfoPayload::pos, ByteBufCodecs.STRING_UTF8, InfoPayload::mode,
                ByteBufCodecs.STRING_UTF8, InfoPayload::text, ByteBufCodecs.BOOL, InfoPayload::manage,
                ByteBufCodecs.BOOL, InfoPayload::inClan, ByteBufCodecs.STRING_UTF8, InfoPayload::usage,
                ByteBufCodecs.STRING_UTF8, InfoPayload::error, InfoPayload::new);
        @Override public Type<InfoPayload> type() { return TYPE; }
    }

    /** Client to server: set the chest at this position to this mode. */
    public record SetPayload(BlockPos pos, String mode) implements CustomPacketPayload {
        public static final Type<SetPayload> TYPE = new Type<>(CrazyWorldProgression.id("chest_lock_set"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetPayload::pos, ByteBufCodecs.STRING_UTF8, SetPayload::mode, SetPayload::new);
        @Override public Type<SetPayload> type() { return TYPE; }
    }

    private ChestLockNetworking() { }

    static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(InfoPayload.TYPE, InfoPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetPayload.TYPE, SetPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Level level = player.level();
            BlockPos pos = payload.pos();
            if (!ChestLocks.lockable(level.getBlockEntity(pos)) || !player.isWithinBlockInteractionRange(pos, 4.0)) return;
            String error = "";
            try {
                ChestLocks.setMode(player, level, pos, ChestLocks.Mode.valueOf(payload.mode()));
            } catch (IllegalArgumentException exception) {
                error = exception.getMessage();
            }
            send(player, level, pos, error);
        });
    }

    static void sendInfo(ServerPlayer player, Level level, BlockPos pos) { send(player, level, pos, ""); }

    private static void send(ServerPlayer player, Level level, BlockPos pos, String error) {
        if (!ServerPlayNetworking.canSend(player, InfoPayload.TYPE)) return;
        MinecraftServer server = player.level().getServer();
        ChestLocks.Lock lock = ChestLocks.lockAt(level, pos);
        SocialData.Clan clan = SocialData.get(server).clanOf(player.getUUID());
        LockRegistry registry = LockRegistry.get(server);
        String usage = "Private and Friends chests: " + registry.personal(server, player.getUUID()) + "/" + LockRegistry.PLAYER_LIMIT
                + (clan == null ? "" : " · Clan chests: " + registry.clan(server, clan.id()) + "/" + LockRegistry.clanLimit(clan));
        ServerPlayNetworking.send(player, new InfoPayload(pos, lock == null ? "PUBLIC" : lock.mode().name(),
                ChestLocks.describe(server, lock), ChestLocks.mayManage(player, level, pos), clan != null, usage, error));
    }
}
