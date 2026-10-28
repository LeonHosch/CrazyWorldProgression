package ekuzo.crazyworldprogression.reforge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Enchantment research at the lectern ("Study table"). Studying an enchanted book uses it up and adds each stored
 * enchantment's research points: level I gives 1, II 2, III 4, IV 8, V 16 (doubling per level). Points give a
 * proficiency per enchantment: 3 points → I, 10 → II, 30 → III. An enchantment at III cannot be studied further; a
 * book with only such enchantments is refused, and on a mixed book their points are still recorded (in case the
 * thresholds change) but not shown. At the reforge window a player focuses one researched enchantment; its entries
 * roll with ×2 / ×3 / ×5 weight at no extra cost.
 */
public final class ResearchService {
    private static final int[] THRESHOLDS = {3, 10, 30};
    private static final int[] MULTIPLIERS = {1, 2, 3, 5};
    public static final int MAX_PROFICIENCY = THRESHOLDS.length;
    private static final Gson GSON = new Gson();

    private ResearchService() { }

    /** Study table screen state: research levels and what the book in the slot would add, as JSON. */
    public record StatePayload(String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE = new Type<>(CrazyWorldProgression.id("study_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(262_144), StatePayload::json, StatePayload::new);
        @Override public Type<StatePayload> type() { return TYPE; }
    }

    public static void initialize() {
        ResearchData.initialize();
        StudyMenu.initialize();
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            var state = level.getBlockState(hit.getBlockPos());
            if (!state.is(Blocks.LECTERN) || player.isSpectator()) return InteractionResult.PASS;
            // Lecterns holding a book, and book and quills or written books, keep their vanilla reading.
            ItemStack held = player.getItemInHand(hand);
            if (state.getValue(LecternBlock.HAS_BOOK) || held.is(Items.WRITABLE_BOOK) || held.is(Items.WRITTEN_BOOK))
                return InteractionResult.PASS;
            if (player instanceof ServerPlayer serverPlayer) {
                ContainerLevelAccess access = ContainerLevelAccess.create(level, hit.getBlockPos());
                serverPlayer.openMenu(new ExtendedMenuProvider<String>() {
                    @Override public String getScreenOpeningData(ServerPlayer target) {
                        return state(target, ItemStack.EMPTY, "", false);
                    }
                    @Override public Component getDisplayName() { return Component.literal("Study table"); }
                    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
                        return new StudyMenu(id, inventory, access, "");
                    }
                });
            }
            return InteractionResult.SUCCESS;
        });
    }

    /** 0 to 3 from research points. */
    public static int proficiency(int points) {
        int level = 0;
        for (int threshold : THRESHOLDS) if (points >= threshold) level++;
        return level;
    }

    /** Research points a book enchantment of this level gives: 1, 2, 4, 8, 16, doubling per level. */
    public static int pointsFor(int level) {
        return 1 << Math.max(0, Math.min(level, 16) - 1);
    }

    /** Points needed for the next proficiency, or -1 at the top. */
    public static int nextThreshold(int points) {
        for (int threshold : THRESHOLDS) if (points < threshold) return threshold;
        return -1;
    }

    public static int multiplier(int proficiency) { return MULTIPLIERS[Math.max(0, Math.min(3, proficiency))]; }

    /** Research points per enchantment ID, sorted by ID. */
    public static Map<String, Integer> points(ServerPlayer player) {
        return new TreeMap<>(ResearchData.player(player).points());
    }

    public static String focus(ServerPlayer player) {
        return ResearchData.player(player).focus();
    }

    /** The focused enchantment's ID and weight multiplier, or null without a researched focus. */
    public static Focus activeFocus(ServerPlayer player) {
        String focus = focus(player);
        if (focus.isEmpty()) return null;
        int proficiency = proficiency(points(player).getOrDefault(focus, 0));
        return proficiency == 0 ? null : new Focus(Identifier.tryParse(focus), multiplier(proficiency));
    }

    public record Focus(Identifier enchantment, int multiplier) { }

    /** The researched enchantment at this index of {@link #points} as a focus, or null for -1 or no research. */
    public static Focus focusAt(ServerPlayer player, int index) {
        List<Map.Entry<String, Integer>> entries = List.copyOf(points(player).entrySet());
        if (index < 0 || index >= entries.size()) return null;
        int proficiency = proficiency(entries.get(index).getValue());
        Identifier id = Identifier.tryParse(entries.get(index).getKey());
        return proficiency == 0 || id == null ? null : new Focus(id, multiplier(proficiency));
    }

    /** Focus on the researched enchantment at this index of {@link #points}, or on none with -1. */
    public static void setFocus(ServerPlayer player, int index) {
        ResearchData.Player current = ResearchData.player(player);
        List<String> ids = List.copyOf(points(player).keySet());
        String focus = index < 0 || index >= ids.size() ? "" : ids.get(index);
        if (focus.equals(current.focus())) focus = "";
        ResearchData.put(player, new ResearchData.Player(current.points(), focus));
    }

    /** Why this book cannot be studied, or null when it can. */
    static String problem(ServerPlayer player, ItemStack book) {
        if (book.isEmpty()) return "Place an enchanted book";
        if (!book.is(Items.ENCHANTED_BOOK)) return "Only enchanted books can be studied";
        var stored = book.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) return "This book holds no enchantments";
        Map<String, Integer> points = points(player);
        for (var entry : stored.entrySet()) {
            String id = entry.getKey().unwrapKey().map(key -> key.identifier().toString()).orElse("");
            if (proficiency(points.getOrDefault(id, 0)) < MAX_PROFICIENCY) return null;
        }
        return "Everything in this book is already fully studied";
    }

    /** Uses up the book and adds its enchantments' levels as research points. Returns the result message. */
    static String study(ServerPlayer player, ItemStack book) {
        String problem = problem(player, book);
        if (problem != null) throw new IllegalArgumentException(problem);
        var stored = book.get(DataComponents.STORED_ENCHANTMENTS);
        ResearchData.Player current = ResearchData.player(player);
        Map<String, Integer> points = new LinkedHashMap<>(current.points());
        StringBuilder levelUps = new StringBuilder();
        for (var entry : stored.entrySet()) {
            String id = entry.getKey().unwrapKey().map(key -> key.identifier().toString()).orElse(null);
            if (id == null) continue;
            int before = points.getOrDefault(id, 0);
            // Points beyond the top level are still recorded, in case the thresholds change later.
            int after = before + pointsFor(entry.getIntValue());
            points.put(id, after);
            if (proficiency(after) > proficiency(before)) {
                if (!levelUps.isEmpty()) levelUps.append(", ");
                levelUps.append(entry.getKey().value().description().getString()).append(' ').append(roman(proficiency(after)));
            }
        }
        ResearchData.put(player, new ResearchData.Player(points, current.focus()));
        book.shrink(1);
        player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.0F);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.6F, 1.4F);
        return levelUps.isEmpty() ? "Studied the book" : "Studied the book · new level: " + levelUps;
    }

    static void sendState(ServerPlayer player, ItemStack book, String message, boolean error) {
        if (ServerPlayNetworking.canSend(player, StatePayload.TYPE))
            ekuzo.crazyworldprogression.CwpPayloads.send(player, new StatePayload(state(player, book, message, error)));
    }

    /**
     * Research levels (with the points still needed for the next level, none at the top) and what the book in the
     * slot would add to each of its enchantments.
     */
    static String state(ServerPlayer player, ItemStack book, String message, boolean error) {
        JsonObject state = new JsonObject();
        state.addProperty("message", message == null ? "" : message);
        state.addProperty("error", error);
        Map<String, Integer> points = points(player);
        JsonArray research = new JsonArray();
        points.forEach((id, total) -> {
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            int proficiency = proficiency(total);
            value.addProperty("level", proficiency);
            if (proficiency < MAX_PROFICIENCY) {
                value.addProperty("points", total);
                value.addProperty("next", nextThreshold(total));
            }
            research.add(value);
        });
        state.add("research", research);
        JsonArray gains = new JsonArray();
        var stored = book.isEmpty() ? null : book.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null) for (var entry : stored.entrySet()) {
            String id = entry.getKey().unwrapKey().map(key -> key.identifier().toString()).orElse(null);
            if (id == null) continue;
            JsonObject value = new JsonObject();
            value.addProperty("id", id);
            value.addProperty("points", pointsFor(entry.getIntValue()));
            value.addProperty("maxed", proficiency(points.getOrDefault(id, 0)) >= MAX_PROFICIENCY);
            gains.add(value);
        }
        state.add("book", gains);
        String problem = problem(player, book);
        state.addProperty("problem", problem == null ? "" : problem);
        return GSON.toJson(state);
    }

    static String roman(int level) {
        return switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; default -> Integer.toString(level); };
    }
}
