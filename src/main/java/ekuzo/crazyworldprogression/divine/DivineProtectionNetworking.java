package ekuzo.crazyworldprogression.divine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.currency.CurrencyService;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import ekuzo.crazyworldprogression.skills.Skills;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Opens the Divine Protection menu and keeps its screen state current. */
public final class DivineProtectionNetworking {
    private static final Gson GSON = new Gson();

    private DivineProtectionNetworking() { }

    /** The player's Enchanting level, every charge's requirement and costs, and the last result, as JSON. */
    public record StatePayload(String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("divine_protection_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    public static void initialize() {
        DivineProtectionMenu.initialize();
        DivineProtection.initialize();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
    }

    /**
     * Opens the menu for a block at {@code pos}; it closes when the player walks away or the block no longer
     * matches {@code block}. A content mod calls this from its block's use method.
     */
    public static void open(ServerPlayer player, BlockPos pos, Predicate<BlockState> block) {
        ContainerLevelAccess access = ContainerLevelAccess.create(player.level(), pos);
        player.openMenu(new ExtendedMenuProvider<String>() {
            @Override public String getScreenOpeningData(ServerPlayer target) { return state(target, "", false); }
            @Override public Component getDisplayName() { return Component.literal("Divine Protection"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                return new DivineProtectionMenu(id, inventory, access, block, "");
            }
        });
    }

    static void sendState(ServerPlayer player, String message, boolean error) {
        if (ServerPlayNetworking.canSend(player, StatePayload.TYPE))
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new StatePayload(state(player, message, error)));
    }

    static String state(ServerPlayer player, String message, boolean error) {
        JsonObject state = new JsonObject();
        state.addProperty("skill", Skills.Skill.ENCHANTING.label());
        int xp = Skills.xp(player, Skills.Skill.ENCHANTING), level = Skills.levelFor(xp);
        state.addProperty("level", ReforgeService.proficiency(player));
        state.addProperty("skillLevel", level);
        state.addProperty("xp", xp);
        state.addProperty("next", level >= Skills.MAX_LEVEL ? -1 : Skills.threshold(level + 1));
        state.addProperty("message", message == null ? "" : message);
        state.addProperty("error", error);
        JsonArray charges = new JsonArray();
        JsonObject balances = new JsonObject();
        for (DivineProtection.Charge charge : DivineProtection.charges()) {
            JsonObject value = new JsonObject();
            value.addProperty("required", charge.requiredLevel());
            // With the skill level's drop already taken off; the client takes off the proficiency's.
            value.addProperty("failure", Math.max(0, charge.failureChance() - DivineProtection.FAILURE_DROP_PER_SKILL_LEVEL * level));
            JsonArray costs = new JsonArray();
            for (ReforgeData.Cost cost : charge.costs()) {
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
            charges.add(value);
        }
        state.add("charges", charges);
        state.addProperty("failureDrop", DivineProtection.FAILURE_DROP_PER_LEVEL);
        state.add("balances", balances);
        return GSON.toJson(state);
    }
}
