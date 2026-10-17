package ekuzo.crazyworldprogression.portals;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.loot.PostmasterService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory orbs: when a raid wipes with keep-inventory off, each downed player's inventory is stored as a
 * snapshot behind an orb at their revive marker. Nothing is ever dropped as items. The owner right-clicks the
 * orb to get the snapshot back. A later wipe replaces the orb; an unclaimed orb is lost when the instance ends.
 */
public final class RaidSnapshots extends SavedData {
    static final String ORB_TAG = "cwp.raid.orb.";
    static final Codec<Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("slot").forGetter(Snapshot::slot),
            Codec.DOUBLE.fieldOf("x").forGetter(Snapshot::x),
            Codec.DOUBLE.fieldOf("y").forGetter(Snapshot::y),
            Codec.DOUBLE.fieldOf("z").forGetter(Snapshot::z),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Snapshot::items)
    ).apply(instance, Snapshot::new));
    static final Codec<Logout> LOGOUT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("slot").forGetter(Logout::slot),
            Codec.LONG.fieldOf("instance_created").forGetter(Logout::instanceCreated),
            Codec.STRING.fieldOf("name").forGetter(Logout::name),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(Logout::items)
    ).apply(instance, Logout::new));
    static final Codec<RaidSnapshots> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, SNAPSHOT_CODEC).optionalFieldOf("orbs", Map.of())
                    .forGetter(data -> data.orbs),
            Codec.unboundedMap(Codec.STRING, LOGOUT_CODEC).optionalFieldOf("logouts", Map.of())
                    .forGetter(data -> data.logouts),
            Codec.STRING.listOf().optionalFieldOf("owed", List.of())
                    .forGetter(data -> List.copyOf(data.owed))
    ).apply(instance, RaidSnapshots::new));
    private static final SavedDataType<RaidSnapshots> TYPE = new SavedDataType<>(
            CrazyWorldProgression.id("raid_inventory_orbs"), RaidSnapshots::new, CODEC, null);
    private final Map<String, Snapshot> orbs = new HashMap<>();
    /** Inventory of a participant who logged out during an encounter, kept until they return or a wipe uses it. */
    private final Map<String, Logout> logouts = new HashMap<>();
    /** Players whose inventory went into an orb while they were offline; it is cleared when they log in. */
    private final java.util.Set<String> owed = new java.util.LinkedHashSet<>();

    /** One stored inventory, slot by slot (main, armor, offhand as Inventory numbers them). */
    record Snapshot(String slot, double x, double y, double z, List<ItemStack> items) {
        Snapshot { items = items.stream().map(ItemStack::copy).toList(); }
    }

    record Logout(String slot, long instanceCreated, String name, List<ItemStack> items) {
        Logout { items = items.stream().map(ItemStack::copy).toList(); }
    }

    /** What a returning player's inventory needs. */
    enum Return { NOTHING, CLEAR_ORB_WAITING, CLEAR_ORB_LOST }

    RaidSnapshots() { }
    private RaidSnapshots(Map<String, Snapshot> orbs, Map<String, Logout> logouts, List<String> owed) {
        this.orbs.putAll(orbs);
        this.logouts.putAll(logouts);
        this.owed.addAll(owed);
    }

    void recordLogout(String id, Logout logout) {
        logouts.put(id, logout);
        setDirty();
    }

    /**
     * A wipe while the player is offline. Their old orb is destroyed; the inventory they logged out with becomes
     * the new orb (returned so the caller can place it), and their live inventory is owed (cleared on login).
     */
    Snapshot wipeOffline(String id, String slot, long instanceCreated, RaidData.Spot marker) {
        orbs.remove(id);
        setDirty();
        Logout logout = logouts.get(id);
        if (logout == null || !logout.slot().equals(slot) || logout.instanceCreated() != instanceCreated) return null;
        logouts.remove(id);
        owed.add(id);
        if (logout.items().stream().allMatch(ItemStack::isEmpty)) return null;
        Snapshot snapshot = new Snapshot(slot, marker.x(), marker.y(), marker.z(), logout.items());
        orbs.put(id, snapshot);
        return snapshot;
    }

    /** On login: a logout snapshot is dropped (they came back before any wipe); an owed inventory is cleared. */
    Return returning(String id) {
        boolean wasLoggedOut = logouts.remove(id) != null;
        boolean wasOwed = owed.remove(id);
        if (wasLoggedOut || wasOwed) setDirty();
        if (!wasOwed) return Return.NOTHING;
        return orbs.containsKey(id) ? Return.CLEAR_ORB_WAITING : Return.CLEAR_ORB_LOST;
    }

    /** A participant logs out during an active encounter: remember their inventory for a possible wipe. */
    public static void onDisconnect(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        PortalData.StoredInstance instance = PortalService.currentInstance(player);
        if (instance == null || !RaidService.isRaidInstance(server, instance)
                || !RaidService.inEncounterPublic(server, instance)
                || !RaidData.get(server).encounterPlayers(instance.slotDimension().toString())
                        .contains(player.getUUID().toString())) return;
        Inventory inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>(inventory.getContainerSize());
        for (int index = 0; index < inventory.getContainerSize(); index++) items.add(inventory.getItem(index).copy());
        get(server).recordLogout(player.getUUID().toString(), new Logout(instance.slotDimension().toString(),
                instance.createdEpochMillis(), player.getGameProfile().name(), items));
    }

    /** Wipe commit for an offline participant: the orb appears now, from their logout inventory. */
    static void wipeOffline(MinecraftServer server, String id, PortalData.StoredInstance instance, ServerLevel level,
                            RaidData.Spot marker) {
        RaidSnapshots data = get(server);
        String name = data.logouts.containsKey(id) ? data.logouts.get(id).name() : id;
        UUID owner = UUID.fromString(id);
        removeOrbEntity(server, owner);
        if (data.wipeOffline(id, instance.slotDimension().toString(), instance.createdEpochMillis(), marker) != null)
            spawnOrb(level, owner, name, marker);
    }

    /** On login after an offline wipe the live inventory is cleared, so the items exist only in the orb. */
    public static void onJoin(ServerPlayer player) {
        RaidSnapshots data = get(player.level().getServer());
        String id = player.getUUID().toString();
        Return result = data.returning(id);
        if (result == Return.NOTHING) return;
        player.getInventory().clearContent();
        if (result == Return.CLEAR_ORB_WAITING) {
            Snapshot orb = data.orbs.get(id);
            player.sendSystemMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.orb_offline",
                    "Your raid wiped while you were offline. Your inventory is in an orb at X %s, Y %s, Z %s.",
                    Math.round(orb.x()), Math.round(orb.y()), Math.round(orb.z())).withStyle(ChatFormatting.GOLD));
        } else {
            player.sendSystemMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.orb_lost_offline",
                    "Your raid wiped while you were offline and your inventory orb is gone.").withStyle(ChatFormatting.RED));
        }
    }

    static RaidSnapshots get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Takes the downed player's inventory into a new orb at their revive marker. Any older orb of theirs is
     * destroyed first, together with what it held.
     */
    static void wipe(ServerPlayer player, ServerLevel level, RaidData.Spot marker) {
        MinecraftServer server = level.getServer();
        RaidSnapshots data = get(server);
        String id = player.getUUID().toString();
        if (data.orbs.remove(id) != null) {
            data.setDirty();
            player.sendSystemMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.orb_destroyed",
                    "Your previous inventory orb was destroyed by this wipe.").withStyle(ChatFormatting.RED));
        }
        removeOrbEntity(server, player.getUUID());
        Inventory inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>(inventory.getContainerSize());
        boolean any = false;
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index).copy();
            any |= !stack.isEmpty();
            items.add(stack);
        }
        if (!any) return;
        inventory.clearContent();
        data.orbs.put(id, new Snapshot(level.dimension().identifier().toString(), marker.x(), marker.y(), marker.z(), items));
        data.setDirty();
        spawnOrb(level, player.getUUID(), player.getGameProfile().name(), marker);
        player.sendSystemMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.orb_created",
                "Your inventory was stored in an orb at X %s, Y %s, Z %s. Right-click it to get it back.",
                Math.round(marker.x()), Math.round(marker.y()), Math.round(marker.z())).withStyle(ChatFormatting.GOLD));
    }

    private static void spawnOrb(ServerLevel level, UUID owner, String ownerName, RaidData.Spot marker) {
        ArmorStand orb = new ArmorStand(level, marker.x(), marker.y(), marker.z());
        orb.setInvisible(true);
        orb.setNoGravity(true);
        orb.setInvulnerable(true);
        orb.setGlowingTag(true);
        orb.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.ENDER_EYE));
        orb.setCustomName(Component.literal(ownerName + "'s inventory"));
        orb.setCustomNameVisible(true);
        orb.addTag(ORB_TAG + owner);
        level.addFreshEntity(orb);
    }

    private static void removeOrbEntity(MinecraftServer server, UUID owner) {
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> found = new ArrayList<>();
            for (Entity entity : level.getAllEntities())
                if (entity.entityTags().contains(ORB_TAG + owner)) found.add(entity);
            found.forEach(Entity::discard);
        }
    }

    static boolean isOrb(Entity entity) {
        return entity.entityTags().stream().anyMatch(tag -> tag.startsWith(ORB_TAG));
    }

    /** Right-click on an orb: only the owner can claim it. */
    static void claim(ServerPlayer player, Entity orb) {
        String tag = orb.entityTags().stream().filter(value -> value.startsWith(ORB_TAG)).findFirst().orElse("");
        String owner = tag.substring(ORB_TAG.length());
        if (!owner.equals(player.getUUID().toString())) {
            player.sendOverlayMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.orb_not_yours",
                    "This inventory orb belongs to someone else."));
            return;
        }
        RaidSnapshots data = get(player.level().getServer());
        Snapshot snapshot = data.orbs.get(owner);
        if (snapshot == null) {
            orb.discard();
            return;
        }
        Inventory inventory = player.getInventory();
        List<ItemStack> slots = new ArrayList<>(inventory.getContainerSize());
        for (int index = 0; index < inventory.getContainerSize(); index++) slots.add(inventory.getItem(index).copy());
        List<ItemStack> displaced = restore(slots, snapshot.items());
        List<ItemStack> homeless = fillEmpty(slots, displaced);
        for (int index = 0; index < slots.size(); index++) inventory.setItem(index, slots.get(index));
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack stack : homeless) {
            PostmasterService.DepositResult result = PostmasterService.deposit(player, stack);
            if (result.overflow() > 0) leftover.add(stack.copyWithCount(result.overflow()));
        }
        player.containerMenu.broadcastChanges();
        if (leftover.isEmpty()) {
            data.orbs.remove(owner);
            orb.discard();
            player.sendSystemMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.orb_claimed",
                    "You got your inventory back.").withStyle(ChatFormatting.GREEN));
        } else {
            // Nothing is deleted: what fits nowhere stays in the orb.
            data.orbs.put(owner, new Snapshot(snapshot.slot(), snapshot.x(), snapshot.y(), snapshot.z(), leftover));
            player.sendSystemMessage(Component.translatableWithFallback("message.crazy-world-progression.raid.orb_partial",
                    "Your inventory and postmaster are full. The rest stays in the orb.").withStyle(ChatFormatting.YELLOW));
        }
        data.setDirty();
        if (!homeless.isEmpty() && leftover.size() < homeless.size()) player.sendSystemMessage(Component.translatableWithFallback(
                "message.crazy-world-progression.raid.orb_postmaster",
                "Items that were in the way went to your postmaster (/postmaster)."));
    }

    /** Puts every snapshot item back into its slot and returns what was in those slots (to be re-housed). */
    static List<ItemStack> restore(List<ItemStack> slots, List<ItemStack> snapshot) {
        List<ItemStack> displaced = new ArrayList<>();
        for (int index = 0; index < snapshot.size() && index < slots.size(); index++) {
            ItemStack saved = snapshot.get(index);
            if (saved.isEmpty()) continue;
            if (!slots.get(index).isEmpty()) displaced.add(slots.get(index));
            slots.set(index, saved.copy());
        }
        // A snapshot from a larger inventory layout: extra stacks are re-housed like displaced ones.
        for (int index = slots.size(); index < snapshot.size(); index++)
            if (!snapshot.get(index).isEmpty()) displaced.add(snapshot.get(index).copy());
        return displaced;
    }

    /** Puts stacks into empty main-inventory slots (0-35) and returns what did not fit. */
    static List<ItemStack> fillEmpty(List<ItemStack> slots, List<ItemStack> stacks) {
        List<ItemStack> leftover = new ArrayList<>();
        int main = Math.min(36, slots.size());
        for (ItemStack stack : stacks) {
            int free = -1;
            for (int index = 0; index < main; index++) if (slots.get(index).isEmpty()) { free = index; break; }
            if (free < 0) leftover.add(stack);
            else slots.set(free, stack);
        }
        return leftover;
    }

    /** The instance ended: unclaimed orbs in it are gone (corpse-run penalty). */
    static void instanceEnded(MinecraftServer server, String slot) {
        RaidSnapshots data = get(server);
        if (data.orbs.values().removeIf(snapshot -> snapshot.slot().equals(slot))) data.setDirty();
        // Owed offline inventories stay, so the player still loses them when they log back in.
        if (data.logouts.values().removeIf(logout -> logout.slot().equals(slot))) data.setDirty();
    }
}
