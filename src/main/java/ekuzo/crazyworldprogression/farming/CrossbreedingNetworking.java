package ekuzo.crazyworldprogression.farming;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;

/**
 * Opens the crossbreeding menu when a composter is right-clicked with an empty hand (a full composter still gives its
 * bone meal), and sends the window what it may show for the seeds in the slots.
 */
public final class CrossbreedingNetworking {
    private static final Gson GSON = new Gson();

    private CrossbreedingNetworking() { }

    /** What the window shows for the current seeds, as JSON (see {@link #state}). */
    public record StatePayload(String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("crossbreeding_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    public static void initialize() {
        CrossbreedingMenu.initialize();
        CrossbreedingService.initialize();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            var state = level.getBlockState(hit.getBlockPos());
            if (!state.is(Blocks.COMPOSTER) || player.isSpectator() || hand != InteractionHand.MAIN_HAND
                    || !player.getItemInHand(hand).isEmpty() || player.isSecondaryUseActive()
                    || state.getValue(ComposterBlock.LEVEL) >= ComposterBlock.READY)
                return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                ContainerLevelAccess access = ContainerLevelAccess.create(level, hit.getBlockPos());
                serverPlayer.openMenu(new ExtendedMenuProvider<String>() {
                    @Override public String getScreenOpeningData(ServerPlayer target) {
                        return state(target, null, "", false);
                    }
                    @Override public Component getDisplayName() { return Component.literal("Crossbreeding"); }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                        return new CrossbreedingMenu(id, inventory, access, "");
                    }
                });
            }
            return InteractionResult.SUCCESS;
        });
    }

    static void sendState(ServerPlayer player, CrossbreedingMenu menu, String message, boolean error) {
        if (ServerPlayNetworking.canSend(player, StatePayload.TYPE))
            ServerPlayNetworking.send(player, new StatePayload(state(player, menu, message, error)));
    }

    /**
     * The player's farming level, the last result message, and for the seeds in the slots: a problem if they cannot
     * be crossbred, otherwise the costs (with balances), the level and, only if the player already knows it, the
     * result. For an unknown result the failure chance is sent instead; the result itself never is.
     */
    static String state(ServerPlayer player, CrossbreedingMenu menu, String message, boolean error) {
        JsonObject state = new JsonObject();
        state.addProperty("level", CrossbreedingService.level(player));
        state.addProperty("message", message == null ? "" : message);
        state.addProperty("error", error);
        if (menu == null || menu.first().isEmpty() || menu.second().isEmpty()) {
            state.addProperty("problem", "Place two seeds");
            return GSON.toJson(state);
        }
        var server = player.level().getServer();
        // The result slot only matters for whether there is room; it is not shown as a problem before costs.
        String problem = CrossbreedingService.problem(player, menu.first(), menu.second(), menu.output());
        CrossbreedingData.Recipe recipe = CrossbreedingService.find(server,
                CrossbreedingService.key(menu.first()), CrossbreedingService.key(menu.second()));
        if (problem != null) state.addProperty("problem", problem);
        if (recipe == null) return GSON.toJson(state);
        boolean known = CrossbreedingService.discovered(player, recipe);
        state.addProperty("known", known);
        state.addProperty("required", recipe.level());
        if (known) {
            state.addProperty("output", recipe.output().toString());
            state.addProperty("count", recipe.count());
        } else state.addProperty("failure", recipe.failureChance());
        JsonArray costs = new JsonArray();
        JsonObject balances = new JsonObject();
        for (ReforgeData.Cost cost : recipe.costs()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", cost.kind());
            entry.addProperty("target", cost.target().toString());
            entry.addProperty("amount", cost.amount());
            entry.addProperty("label", ReforgeService.label(cost));
            costs.add(entry);
            if (cost.kind().equals("currency") && !balances.has(cost.target().toString()))
                balances.addProperty(cost.target().toString(),
                        CurrencyService.getBalance(server, player.getUUID(), cost.target()));
        }
        state.add("costs", costs);
        state.add("balances", balances);
        return GSON.toJson(state);
    }
}
