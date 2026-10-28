package ekuzo.crazyworldprogression.cooking;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Opens the cooking window: a cauldron right-clicked with an empty hand (buckets and bottles keep working), or a
 * smoker (sneak-right-click still opens the vanilla smoker). Sends what the window shows.
 */
public final class CookingNetworking {
    private static final Gson GSON = new Gson();

    private CookingNetworking() { }

    public record StatePayload(String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("cooking_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    public static void initialize() {
        CookingMenu.initialize();
        CookingService.initialize();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            var state = level.getBlockState(hit.getBlockPos());
            CookingData.Station station = state.is(BlockTags.CAULDRONS) ? CookingData.Station.STEW
                    : state.is(Blocks.SMOKER) ? CookingData.Station.SMOKER : null;
            if (station == null || player.isSpectator() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            // Sneaking keeps vanilla (the smoker's own window, placing blocks); a cauldron also needs an empty hand.
            if (player.isSecondaryUseActive()) return InteractionResult.PASS;
            if (station == CookingData.Station.STEW && !player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) open(serverPlayer, station, level, hit.getBlockPos());
            return InteractionResult.SUCCESS;
        });
    }

    private static void open(ServerPlayer player, CookingData.Station station, Level level, BlockPos pos) {
        ContainerLevelAccess access = ContainerLevelAccess.create(level, pos);
        player.openMenu(new ExtendedMenuProvider<String>() {
            @Override public String getScreenOpeningData(ServerPlayer target) {
                return state(target, station, level, pos, null, "", false);
            }
            @Override public Component getDisplayName() {
                return Component.literal(station == CookingData.Station.STEW ? "Stew Pot" : "Smoker");
            }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                return new CookingMenu(id, inventory, access, station, "");
            }
        });
    }

    static void sendState(ServerPlayer player, CookingMenu menu, String message, boolean error) {
        if (!ServerPlayNetworking.canSend(player, StatePayload.TYPE)) return;
        String json = menu.atStation((level, pos) -> state(player, menu.station(), level, pos, menu, message, error));
        ServerPlayNetworking.send(player, new StatePayload(json));
    }

    /** Station, cooking level, whether the station works, the dish the ingredients make, and the last message. */
    static String state(ServerPlayer player, CookingData.Station station, Level level, BlockPos pos, CookingMenu menu,
                        String message, boolean error) {
        JsonObject state = new JsonObject();
        state.addProperty("station", station.key());
        state.addProperty("level", CookingService.level(player));
        state.addProperty("message", message == null ? "" : message);
        state.addProperty("error", error);
        String stationProblem = CookingService.stationProblem(station, level, pos);
        if (stationProblem != null) state.addProperty("station_problem", stationProblem);
        if (menu == null) return GSON.toJson(state);
        CookingData.Recipe recipe = CookingService.find(player.level().getServer(), station, menu.ingredients());
        if (recipe != null) {
            state.addProperty("output", recipe.output().toString());
            state.addProperty("count", recipe.count());
            state.addProperty("required", recipe.level());
        }
        String problem = CookingService.problem(player, station, level, pos, menu.ingredients(), menu.tool(), menu.output());
        if (problem != null) state.addProperty("problem", problem);
        return GSON.toJson(state);
    }
}
