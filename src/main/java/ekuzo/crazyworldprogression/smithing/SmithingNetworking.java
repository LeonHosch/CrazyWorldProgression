package ekuzo.crazyworldprogression.smithing;

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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/** Opens the resmith menu instead of the vanilla anvil, and keeps its screen state current. */
public final class SmithingNetworking {
    private static final Gson GSON = new Gson();

    private SmithingNetworking() { }

    /** The player's tier, the item's costs and success rate, and the last result, as JSON. */
    public record StatePayload(String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("smithing_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    public static void initialize() {
        SmithingMenu.initialize();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.getBlockState(hit.getBlockPos()).is(BlockTags.ANVIL) || player.isSpectator())
                return InteractionResult.PASS;
            if (player.isSecondaryUseActive() && !player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                ContainerLevelAccess access = ContainerLevelAccess.create(level, hit.getBlockPos());
                serverPlayer.openMenu(new ExtendedMenuProvider<String>() {
                    @Override public String getScreenOpeningData(ServerPlayer target) {
                        return state(target, ItemStack.EMPTY, "", false);
                    }
                    @Override public Component getDisplayName() { return Component.literal(SmithingBranding.get().name()); }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                        return new SmithingMenu(id, inventory, access, "");
                    }
                });
            }
            return InteractionResult.SUCCESS;
        });
    }

    static void sendState(ServerPlayer player, ItemStack stack, String message, boolean error) {
        if (ServerPlayNetworking.canSend(player, StatePayload.TYPE))
            ServerPlayNetworking.send(player, new StatePayload(state(player, stack, message, error)));
    }

    /**
     * Everything the window needs to work out the repair itself: the player's proficiency, every tier with its
     * required level, failure chance and item costs, and the balances of the currencies those costs use. Sent when
     * the window opens and after each repair; the item in the slot is evaluated in the window.
     */
    static String state(ServerPlayer player, ItemStack ignored, String message, boolean error) {
        JsonObject state = new JsonObject();
        state.addProperty("proficiency", SmithingService.proficiency(player));
        state.addProperty("message", message == null ? "" : message);
        state.addProperty("error", error);
        JsonArray tiers = new JsonArray();
        JsonObject balances = new JsonObject();
        for (SmithingData.Tier tier : SmithingService.tiers(player.level().getServer())) {
            JsonObject value = new JsonObject();
            value.addProperty("name", tier.name());
            value.addProperty("required", tier.requiredLevel());
            value.addProperty("failure", SmithingService.failureChance(player, tier));
            JsonObject items = new JsonObject();
            for (SmithingData.ItemCost item : tier.items()) {
                JsonArray costs = new JsonArray();
                for (ReforgeData.Cost cost : item.costs()) {
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
                items.add(item.item().toString(), costs);
            }
            value.add("items", items);
            tiers.add(value);
        }
        state.add("tiers", tiers);
        // Chest upgrades, Normal to Perfect: the rank each needs and its costs.
        JsonArray chests = new JsonArray();
        for (ChestQuality.Step step : ChestQuality.STEPS) {
            JsonObject value = new JsonObject();
            value.addProperty("rank", step.rank());
            JsonArray costs = new JsonArray();
            for (ReforgeData.Cost cost : step.costs()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("kind", cost.kind());
                entry.addProperty("target", cost.target().toString());
                entry.addProperty("amount", cost.amount());
                entry.addProperty("label", ReforgeService.label(cost));
                costs.add(entry);
            }
            value.add("costs", costs);
            chests.add(value);
        }
        state.add("chests", chests);
        state.add("balances", balances);
        return GSON.toJson(state);
    }
}
