package ekuzo.crazyworldprogression.storage;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.social.ChestLocks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Chest filters and quicksort between server and client: the opened chest's filter, saving a filter, the quicksort
 * settings window, and the quicksort effects (items flying into a chest, its lid opening silently for a moment).
 */
public final class StorageNetworking {
    /** Server to client: the filter of the chest just opened ("" = every type). */
    public record FilterInfoPayload(BlockPos pos, String categories) implements CustomPacketPayload {
        public static final Type<FilterInfoPayload> TYPE = new Type<>(CrazyWorldProgression.id("chest_filter_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FilterInfoPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, FilterInfoPayload::pos, ByteBufCodecs.STRING_UTF8, FilterInfoPayload::categories, FilterInfoPayload::new);
        @Override public Type<FilterInfoPayload> type() { return TYPE; }
    }

    /** Client to server: set a chest's filter (comma-separated category IDs; "" = every type). */
    public record SetFilterPayload(BlockPos pos, String categories) implements CustomPacketPayload {
        public static final Type<SetFilterPayload> TYPE = new Type<>(CrazyWorldProgression.id("chest_filter_set"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetFilterPayload> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, SetFilterPayload::pos, ByteBufCodecs.STRING_UTF8, SetFilterPayload::categories, SetFilterPayload::new);
        @Override public Type<SetFilterPayload> type() { return TYPE; }
    }

    /** Both ways: quicksort settings (the client sends them to change them, or with open=true to ask for the window). */
    public record QuicksortPayload(String categories, boolean fill, boolean on, boolean open) implements CustomPacketPayload {
        public static final Type<QuicksortPayload> TYPE = new Type<>(CrazyWorldProgression.id("quicksort"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QuicksortPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, QuicksortPayload::categories, ByteBufCodecs.BOOL, QuicksortPayload::fill,
                ByteBufCodecs.BOOL, QuicksortPayload::on, ByteBufCodecs.BOOL, QuicksortPayload::open, QuicksortPayload::new);
        @Override public Type<QuicksortPayload> type() { return TYPE; }
    }

    /** Server to client: these items flew from the player into the chest at this position. */
    public record SortedPayload(Vec3 from, BlockPos chest, List<ItemStack> items) implements CustomPacketPayload {
        public static final Type<SortedPayload> TYPE = new Type<>(CrazyWorldProgression.id("quicksort_sorted"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SortedPayload> CODEC = StreamCodec.composite(
                Vec3.STREAM_CODEC, SortedPayload::from, BlockPos.STREAM_CODEC, SortedPayload::chest,
                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list(3)), SortedPayload::items, SortedPayload::new);
        @Override public Type<SortedPayload> type() { return TYPE; }
    }

    /** Lids opened by quicksort, closed again after a moment. */
    private record OpenLid(ServerLevel level, BlockPos pos, long closeAt) { }

    private static final List<OpenLid> LIDS = new ArrayList<>();

    private StorageNetworking() { }

    public static void initialize() {
        ItemCategories.initialize();
        Quicksort.initialize();
        ItemLocks.initialize();
        SortActions.initialize();
        PayloadTypeRegistry.clientboundPlay().register(FilterInfoPayload.TYPE, FilterInfoPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetFilterPayload.TYPE, SetFilterPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(QuicksortPayload.TYPE, QuicksortPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(QuicksortPayload.TYPE, QuicksortPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SortedPayload.TYPE, SortedPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetFilterPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Level level = player.level();
            if (!(level.getBlockEntity(payload.pos()) instanceof ChestBlockEntity) || !player.isWithinBlockInteractionRange(payload.pos(), 4.0)
                    || !ChestLocks.canAccess(player, level, payload.pos())) return;
            ChestFilters.set(level, payload.pos(), payload.categories().isEmpty() ? null : Arrays.asList(payload.categories().split(",")));
            sendFilterInfo(player, level, payload.pos());
        });
        ServerPlayNetworking.registerGlobalReceiver(QuicksortPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!payload.open()) {
                List<String> categories = payload.categories().isEmpty() ? List.of() : Arrays.asList(payload.categories().split(","));
                player.setAttached(Quicksort.SETTINGS, new Quicksort.Settings(categories, payload.fill(), payload.on()));
            }
            Quicksort.Settings settings = player.getAttachedOrCreate(Quicksort.SETTINGS);
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new QuicksortPayload(String.join(",", settings.categories()), settings.fill(),
                    settings.on(), payload.open()));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Iterator<OpenLid> it = LIDS.iterator(); it.hasNext(); ) {
                OpenLid lid = it.next();
                if (lid.level().getGameTime() < lid.closeAt()) continue;
                it.remove();
                var state = lid.level().getBlockState(lid.pos());
                lid.level().blockEvent(lid.pos(), state.getBlock(), 1, ChestBlockEntity.getOpenCount(lid.level(), lid.pos()));
            }
        });
    }

    /** Sends the filter of a chest the player just opened (for the Filter button in its window). */
    public static void sendFilterInfo(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest) || !ServerPlayNetworking.canSend(player, FilterInfoPayload.TYPE)) return;
        Set<String> filter = ChestFilters.filter(chest);
        ekuzo.crazyworldprogression.CwpPayloads.send(player, new FilterInfoPayload(pos, filter == null ? "" : String.join(",", filter)));
    }

    /** Quicksort put items into a chest: they fly there for the player, and the lid opens silently for half a second. */
    static void showSort(ServerPlayer player, ServerLevel level, BlockPos chest, List<ItemStack> items) {
        if (ServerPlayNetworking.canSend(player, SortedPayload.TYPE))
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new SortedPayload(player.position().add(0, 1.0, 0), chest,
                    items.stream().filter(stack -> !stack.isEmpty()).limit(3).map(ItemStack::copy).toList()));
        // A block event opens the lid on clients without the chest's open sound.
        for (BlockPos half : ChestFilters.halves(level, chest)) {
            level.blockEvent(half, level.getBlockState(half).getBlock(), 1, ChestBlockEntity.getOpenCount(level, half) + 1);
            LIDS.add(new OpenLid(level, half, level.getGameTime() + 10));
        }
    }
}
