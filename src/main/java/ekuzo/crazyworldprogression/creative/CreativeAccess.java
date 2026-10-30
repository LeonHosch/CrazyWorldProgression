package ekuzo.crazyworldprogression.creative;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.CwpPermissions;
import ekuzo.crazyworldprogression.accessory.Accessories;
import ekuzo.crazyworldprogression.accessory.BuffFood;
import ekuzo.crazyworldprogression.ammo.AmmoPouch;
import ekuzo.crazyworldprogression.config.CwpModule;
import ekuzo.crazyworldprogression.portals.PortalRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;

/**
 * Creative and spectator mode are for builders only ({@link CwpPermissions#isBuilder}); anyone else found in them is
 * switched to survival at login and once a second. Spectators inside instance worlds are left alone, because raids
 * down players as spectators there.
 * <p>
 * Creative has its own inventory: entering or leaving creative by any game mode change puts the current inventory
 * (armor and offhand included), accessories, buff food and ammo away and brings back the other mode's.
 */
public final class CreativeAccess {
    private static final Codec<List<ItemStack>> ITEMS = ItemStack.OPTIONAL_CODEC.listOf();

    /** What a player carries; the stored one belongs to the mode group (creative or not) they are not in. */
    public record Stash(List<ItemStack> inventory, List<ItemStack> accessories, ItemStack food, List<ItemStack> ammo) {
        static final Codec<Stash> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ITEMS.optionalFieldOf("inventory", List.of()).forGetter(Stash::inventory),
                ITEMS.optionalFieldOf("accessories", List.of()).forGetter(Stash::accessories),
                ItemStack.OPTIONAL_CODEC.optionalFieldOf("food", ItemStack.EMPTY).forGetter(Stash::food),
                ITEMS.optionalFieldOf("ammo", List.of()).forGetter(Stash::ammo)
        ).apply(instance, Stash::new));
        static final Stash EMPTY = new Stash(List.of(), List.of(), ItemStack.EMPTY, List.of());
    }

    public static final AttachmentType<Stash> OTHER_MODE = AttachmentRegistry.create(
            CrazyWorldProgression.id("other_mode_inventory"), builder -> builder.persistent(Stash.CODEC).copyOnDeath());

    private CreativeAccess() { }

    public static void initialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> enforce(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) enforce(player);
        });
    }

    private static void enforce(ServerPlayer player) {
        GameType mode = player.gameMode();
        if (mode != GameType.CREATIVE && mode != GameType.SPECTATOR) return;
        if (CwpPermissions.isBuilder(player)) return;
        if (mode == GameType.SPECTATOR && PortalRegistry.instanceSlots().contains(player.level().dimension().identifier()))
            return;
        player.setGameMode(GameType.SURVIVAL);
        player.sendSystemMessage(Component.translatable("message.crazy-world-progression.builder_only")
                .withStyle(ChatFormatting.RED));
    }

    /** Called after every successful game mode change (ServerPlayerGameModeMixin). */
    public static void changed(ServerPlayer player, GameType before, GameType after) {
        if ((before == GameType.CREATIVE) == (after == GameType.CREATIVE)) return;
        Stash current = take(player);
        Stash other = player.getAttached(OTHER_MODE);
        player.setAttached(OTHER_MODE, current);
        give(player, other == null ? Stash.EMPTY : other);
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        if (CwpModule.ACCESSORIES.enabled())
            ekuzo.crazyworldprogression.progression.equipment.EquipmentSetService.refreshIfChanged(player);
    }

    private static Stash take(ServerPlayer player) {
        List<ItemStack> inventory = new ArrayList<>();
        var items = player.getInventory();
        for (int slot = 0; slot < items.getContainerSize(); slot++) inventory.add(items.getItem(slot).copy());
        List<ItemStack> accessories = CwpModule.ACCESSORIES.enabled()
                ? Accessories.worn(player).stream().map(ItemStack::copy).toList() : List.of();
        ItemStack food = CwpModule.ACCESSORIES.enabled() ? BuffFood.get(player).copy() : ItemStack.EMPTY;
        List<ItemStack> ammo = new ArrayList<>();
        if (CwpModule.ACCESSORIES.enabled()) {
            Container pouch = AmmoPouch.container(player);
            for (int slot = 0; slot < AmmoPouch.SLOTS; slot++) ammo.add(pouch.getItem(slot).copy());
        }
        return new Stash(inventory, accessories, food, ammo);
    }

    private static void give(ServerPlayer player, Stash stash) {
        var items = player.getInventory();
        for (int slot = 0; slot < items.getContainerSize(); slot++)
            items.setItem(slot, slot < stash.inventory().size() ? stash.inventory().get(slot).copy() : ItemStack.EMPTY);
        if (!CwpModule.ACCESSORIES.enabled()) return;
        for (int slot = 0; slot < Accessories.SLOTS; slot++)
            Accessories.set(player, slot, slot < stash.accessories().size() ? stash.accessories().get(slot).copy() : ItemStack.EMPTY);
        BuffFood.set(player, stash.food().copy());
        Container pouch = AmmoPouch.container(player);
        for (int slot = 0; slot < AmmoPouch.SLOTS; slot++)
            pouch.setItem(slot, slot < stash.ammo().size() ? stash.ammo().get(slot).copy() : ItemStack.EMPTY);
    }
}
