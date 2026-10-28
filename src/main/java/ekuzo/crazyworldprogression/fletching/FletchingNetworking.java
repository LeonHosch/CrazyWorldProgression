package ekuzo.crazyworldprogression.fletching;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import ekuzo.crazyworldprogression.skills.Skills;
import ekuzo.crazyworldprogression.smithing.SmithingService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Opens the fletching menu at a fletching table, and keeps its screen state current. */
public final class FletchingNetworking {
    private static final Gson GSON = new Gson();

    private FletchingNetworking() { }

    /** The player's rank and level, every recipe with its costs, balances, and the last result, as JSON. */
    public record StatePayload(String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("fletching_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    static void initialize() {
        FletchingMenu.initialize();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.getBlockState(hit.getBlockPos()).is(Blocks.FLETCHING_TABLE) || player.isSpectator())
                return InteractionResult.PASS;
            if (player.isSecondaryUseActive() && !player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                ContainerLevelAccess access = ContainerLevelAccess.create(level, hit.getBlockPos());
                serverPlayer.openMenu(new ExtendedMenuProvider<String>() {
                    @Override public String getScreenOpeningData(ServerPlayer target) { return state(target, "", false); }
                    @Override public Component getDisplayName() { return Component.literal("Fletching"); }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                        return new FletchingMenu(id, inventory, access, "");
                    }
                });
            }
            return InteractionResult.SUCCESS;
        });
    }

    static void sendState(ServerPlayer player, String message, boolean error) {
        if (ServerPlayNetworking.canSend(player, StatePayload.TYPE))
            ServerPlayNetworking.send(player, new StatePayload(state(player, message, error)));
    }

    static String state(ServerPlayer player, String message, boolean error) {
        JsonObject state = new JsonObject();
        state.addProperty("rank", SmithingService.proficiency(player));
        int level = Skills.level(player, Skills.Skill.SMITHING);
        state.addProperty("level", level);
        state.addProperty("extra", (int) Math.round(Math.min(1.0, level * FletchingService.EXTRA_BATCH_PER_LEVEL) * 100));
        state.addProperty("message", message == null ? "" : message);
        state.addProperty("error", error);
        JsonArray recipes = new JsonArray();
        JsonObject balances = new JsonObject();
        for (FletchingData.Recipe recipe : FletchingService.recipes(player.level().getServer())) {
            JsonObject value = new JsonObject();
            value.addProperty("output", recipe.output().toString());
            value.addProperty("name", BuiltInRegistries.ITEM.getOptional(recipe.output())
                    .map(item -> new ItemStack(item).getHoverName().getString()).orElse(recipe.output().toString()));
            value.addProperty("count", recipe.count());
            value.addProperty("rank", recipe.rank());
            JsonArray costs = new JsonArray();
            for (ReforgeData.Cost cost : recipe.costs()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("kind", cost.kind());
                entry.addProperty("target", cost.target().toString());
                entry.addProperty("amount", cost.amount());
                entry.addProperty("label", ReforgeService.label(cost));
                costs.add(entry);
                if (cost.kind().equals("currency") && !balances.has(cost.target().toString()))
                    balances.addProperty(cost.target().toString(),
                            CurrencyService.getBalance(player.level().getServer(), player.getUUID(), cost.target()));
            }
            value.add("costs", costs);
            recipes.add(value);
        }
        state.add("recipes", recipes);
        state.add("balances", balances);
        return GSON.toJson(state);
    }
}
