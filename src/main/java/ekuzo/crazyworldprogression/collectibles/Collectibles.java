package ekuzo.crazyworldprogression.collectibles;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mounts and pets, collected like titles: they never exist as items. A player unlocks one through a loot pool entry of
 * kind {@code mount} or {@code pet} (or {@code /collectibles grant}), then selects it in the loot journal. The selected
 * mount is summoned with the mount key (H, {@link MountService}); the selected pet follows the player
 * ({@link PetService}). Content mods register the mounts and pets in their initializer with {@link #registerMount} and
 * {@link #registerPet}.
 */
public final class Collectibles {
    public static final String MOUNT = "mount", PET = "pet";

    /** Speed is the movement speed attribute (a horse has 0.1125 to 0.3375), jump strength like a horse's (0.4 to 1.0). */
    public record MountDefinition(Identifier id, String name, Rarity rarity, EntityType<? extends CwpMount> type,
                                  double speed, double jumpStrength) { }

    public record PetDefinition(Identifier id, String name, Rarity rarity, EntityType<? extends CwpPet> type) { }

    private static final Map<Identifier, MountDefinition> MOUNTS = new LinkedHashMap<>();
    private static final Map<Identifier, PetDefinition> PETS = new LinkedHashMap<>();

    /** Unlocked mount and pet IDs ("mount:<id>" / "pet:<id>"). */
    public static final AttachmentType<List<String>> UNLOCKED = AttachmentRegistry.create(
            CrazyWorldProgression.id("collectibles"),
            builder -> builder.persistent(Codec.STRING.listOf()).copyOnDeath().initializer(List::of));
    /** The selected mount and pet ("mount" / "pet" to the ID). */
    public static final AttachmentType<Map<String, String>> SELECTED = AttachmentRegistry.create(
            CrazyWorldProgression.id("selected_collectibles"),
            builder -> builder.persistent(Codec.unboundedMap(Codec.STRING, Codec.STRING)).copyOnDeath().initializer(Map::of));

    private Collectibles() { }

    public static void registerMount(MountDefinition mount) { MOUNTS.put(mount.id(), mount); }

    public static void registerPet(PetDefinition pet) { PETS.put(pet.id(), pet); }

    public static List<MountDefinition> mounts() { return List.copyOf(MOUNTS.values()); }

    public static List<PetDefinition> pets() { return List.copyOf(PETS.values()); }

    public static MountDefinition mount(Identifier id) { return id == null ? null : MOUNTS.get(id); }

    public static PetDefinition pet(Identifier id) { return id == null ? null : PETS.get(id); }

    /** Whether a mount or pet with this ID is registered. */
    public static boolean exists(String kind, Identifier id) {
        return kind.equals(MOUNT) ? MOUNTS.containsKey(id) : kind.equals(PET) && PETS.containsKey(id);
    }

    public static boolean unlocked(ServerPlayer player, String kind, Identifier id) {
        return player.getAttachedOrCreate(UNLOCKED).contains(kind + ":" + id);
    }

    /** Unlocks a mount or pet and tells the player; false when they already had it. */
    public static boolean unlock(ServerPlayer player, String kind, Identifier id) {
        if (!exists(kind, id) || unlocked(player, kind, id)) return false;
        List<String> all = new ArrayList<>(player.getAttachedOrCreate(UNLOCKED));
        all.add(kind + ":" + id);
        player.setAttached(UNLOCKED, List.copyOf(all));
        String name = kind.equals(MOUNT) ? mount(id).name() : pet(id).name();
        Rarity rarity = kind.equals(MOUNT) ? mount(id).rarity() : pet(id).rarity();
        player.sendSystemMessage(Component.literal("New " + kind + " unlocked: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(name + " (" + rarity.label() + ")").withStyle(rarity.color()))
                .append(Component.literal(". Select it in your Loot Journal.").withStyle(ChatFormatting.GRAY)));
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7F, 1.0F);
        return true;
    }

    public static void revoke(ServerPlayer player, String kind, Identifier id) {
        List<String> all = new ArrayList<>(player.getAttachedOrCreate(UNLOCKED));
        all.remove(kind + ":" + id);
        player.setAttached(UNLOCKED, List.copyOf(all));
        if (id.toString().equals(selected(player, kind))) select(player, kind, null);
    }

    /** The selected mount or pet ID, or null. */
    public static String selected(ServerPlayer player, String kind) { return player.getAttachedOrCreate(SELECTED).get(kind); }

    /** Selects an unlocked mount or pet (null clears it). */
    public static void select(ServerPlayer player, String kind, Identifier id) {
        if (id != null && !unlocked(player, kind, id)) throw new IllegalArgumentException("Not unlocked: " + id);
        Map<String, String> all = new LinkedHashMap<>(player.getAttachedOrCreate(SELECTED));
        if (id == null) all.remove(kind);
        else all.put(kind, id.toString());
        player.setAttached(SELECTED, Map.copyOf(all));
        if (kind.equals(PET)) PetService.refresh(player);
    }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> context.server().execute(() -> {
            ServerPlayer player = context.player();
            switch (payload.action()) {
                case "summon" -> MountService.toggle(player);
                case "select_mount", "select_pet" -> {
                    String kind = payload.action().equals("select_mount") ? MOUNT : PET;
                    Identifier id = payload.id().isEmpty() ? null : Identifier.tryParse(payload.id());
                    try {
                        select(player, kind, id);
                    } catch (IllegalArgumentException ignored) {
                        return;
                    }
                    ekuzo.crazyworldprogression.loot.FishingJournal.open(player);
                }
                default -> { }
            }
        }));
        MountService.initialize();
        PetService.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("collectibles").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(grantOrRevoke("grant", true))
                        .then(grantOrRevoke("revoke", false))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> grantOrRevoke(String name, boolean grant) {
        return Commands.literal(name).then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of(MOUNT, PET), builder))
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        (StringArgumentType.getString(context, "kind").equals(PET)
                                                ? PETS.keySet() : MOUNTS.keySet()).stream().map(Identifier::toString), builder))
                                .executes(context -> {
                                    ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                    String kind = StringArgumentType.getString(context, "kind");
                                    Identifier id = Identifier.tryParse(StringArgumentType.getString(context, "id").trim());
                                    if (id == null || !exists(kind, id)) {
                                        context.getSource().sendFailure(Component.literal("Unknown " + kind + ": " + id));
                                        return 0;
                                    }
                                    if (grant) unlock(player, kind, id);
                                    else revoke(player, kind, id);
                                    context.getSource().sendSuccess(() -> Component.literal((grant ? "Granted " : "Revoked ")
                                            + kind + " " + id + (grant ? " to " : " from ") + player.getGameProfile().name()), true);
                                    return Command.SINGLE_SUCCESS;
                                }))));
    }

    /** action: "summon", "select_mount" or "select_pet" (id, or empty to clear). */
    public record ActionPayload(String action, String id) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(CrazyWorldProgression.id("collectible_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), ActionPayload::action, ByteBufCodecs.stringUtf8(256), ActionPayload::id, ActionPayload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
