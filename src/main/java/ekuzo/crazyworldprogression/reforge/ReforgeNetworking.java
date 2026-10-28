package ekuzo.crazyworldprogression.reforge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.Blocks;

/** Opens the reforge menu instead of vanilla enchanting, and keeps its screen state current. */
public final class ReforgeNetworking {
    private static final Gson GSON = new Gson();

    private ReforgeNetworking() { }

    /** Tiers, the player's proficiency, currency balances and the last result, as JSON. */
    public record StatePayload(String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("reforge_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1_048_576), StatePayload::json, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    public static void initialize() {
        ReforgeMenu.initialize();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.getBlockState(hit.getBlockPos()).is(Blocks.ENCHANTING_TABLE) || player.isSpectator())
                return InteractionResult.PASS;
            if (player.isSecondaryUseActive() && !player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                ContainerLevelAccess access = ContainerLevelAccess.create(level, hit.getBlockPos());
                serverPlayer.openMenu(new ExtendedMenuProvider<String>() {
                    @Override public String getScreenOpeningData(ServerPlayer target) { return state(target, "", false); }
                    @Override public Component getDisplayName() { return Component.literal(ReforgeBranding.get().name()); }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                        return new ReforgeMenu(id, inventory, access, "");
                    }
                });
            }
            return InteractionResult.SUCCESS;
        });
    }

    static void sendState(ServerPlayer player, String message, boolean error) {
        if (ServerPlayNetworking.canSend(player, StatePayload.TYPE))
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new StatePayload(state(player, message, error)));
    }

    private static JsonObject poolJson(java.util.Map<String, ReforgeData.Pool> pools,
                                       net.minecraft.resources.Identifier id, int depth) {
        JsonObject value = new JsonObject();
        ReforgeData.Pool pool = pools.get(id.toString());
        value.addProperty("name", pool == null ? id.toString() : pool.name());
        JsonArray entries = new JsonArray();
        if (pool != null && depth <= ReforgeService.MAX_DEPTH) for (ReforgeData.Entry entry : pool.entries()) {
            if (entry.weight() <= 0) continue;
            JsonObject item = new JsonObject();
            item.addProperty("kind", entry.kind());
            item.addProperty("weight", entry.weight());
            if (entry.kind().equals("pool")) item.add("pool", poolJson(pools, entry.target(), depth + 1));
            else {
                item.addProperty("id", entry.target().toString());
                item.addProperty("level", Math.max(1, entry.level()));
            }
            entries.add(item);
        }
        value.add("entries", entries);
        return value;
    }

    static String state(ServerPlayer player, String message, boolean error) {
        JsonObject state = new JsonObject();
        int proficiency = ReforgeService.proficiency(player);
        state.addProperty("proficiency", proficiency);
        state.addProperty("message", message == null ? "" : message);
        state.addProperty("error", error);
        JsonArray tiers = new JsonArray();
        for (ReforgeData.Tier tier : ReforgeService.tiers(player.level().getServer())) {
            JsonObject value = new JsonObject();
            value.addProperty("name", tier.name());
            value.addProperty("required", tier.requiredLevel());
            JsonArray costs = new JsonArray();
            for (ReforgeData.Cost cost : tier.costs()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("kind", cost.kind());
                entry.addProperty("target", cost.target().toString());
                entry.addProperty("amount", cost.amount());
                entry.addProperty("label", ReforgeService.label(cost));
                costs.add(entry);
            }
            value.add("costs", costs);
            // The whole pool tree, so the screen can work out what the item in the slot can get by itself.
            value.add("pool", poolJson(ReforgeService.pools(player.level().getServer()), tier.pool(), 0));
            tiers.add(value);
        }
        state.add("tiers", tiers);
        JsonObject balances = new JsonObject();
        ReforgeService.balances(player).forEach((id, balance) -> balances.addProperty(id.toString(), balance));
        state.add("balances", balances);
        // Research, sorted by enchantment ID like ResearchService.points. The window picks the focus itself.
        JsonArray research = new JsonArray();
        ResearchService.points(player).forEach((id, points) -> {
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            value.addProperty("points", points);
            int researched = ResearchService.proficiency(points);
            value.addProperty("proficiency", researched);
            value.addProperty("next", ResearchService.nextThreshold(points));
            value.addProperty("multiplier", ResearchService.multiplier(researched));
            research.add(value);
        });
        state.add("research", research);
        return GSON.toJson(state);
    }
}
