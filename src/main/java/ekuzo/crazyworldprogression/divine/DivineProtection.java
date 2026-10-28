package ekuzo.crazyworldprogression.divine;

import ekuzo.crazyworldprogression.accessory.Accessories;
import ekuzo.crazyworldprogression.reforge.ReforgeData;
import ekuzo.crazyworldprogression.reforge.ReforgeService;
import ekuzo.crazyworldprogression.skills.Skills;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Divine Protection: up to {@link #MAX_CHARGES} charges on an item. A death that would cost the player their items
 * (keep-inventory off, also inside dungeons and raids that turn it off) keeps every protected item in the inventory
 * and uses up one charge of each; a raid wipe does not put them into the inventory orb. Where keep-inventory is on,
 * nothing is used up.
 *
 * <p>Charges are bought one at a time at a block the content mod provides ({@link DivineProtectionNetworking#open}).
 * Each next charge has its own costs and needs a higher Enchanting Proficiency (the same number the reforge window
 * shows: its proficiency, from skill tree nodes only; charging gives {@link Skills.Skill#ENCHANTING} level XP). A content mod sets the charges with {@link #setCharges}.
 */
public final class DivineProtection {
    public static final int MAX_CHARGES = 5;
    private static final String KEY = "cwp_divine_protection";

    /** How much each proficiency level above a charge's requirement lowers its failure chance (hundredths of a %). */
    public static final int FAILURE_DROP_PER_LEVEL = 500;
    /** How much each level of the skill (Soulweaving, levelled by doing) lowers every failure chance (hundredths of a %). */
    public static final int FAILURE_DROP_PER_SKILL_LEVEL = 200;

    /**
     * One charge: the proficiency it needs, what it costs, the skill XP it gives (also on a failure) and its failure
     * chance at exactly the required level, in hundredths of a percent. A failure uses up the costs and adds no
     * charge; each level above the requirement lowers the chance by {@link #FAILURE_DROP_PER_LEVEL}, and each skill
     * level by {@link #FAILURE_DROP_PER_SKILL_LEVEL}.
     *
     * @param costs item or currency costs, as reforge tiers use them
     */
    public record Charge(int requiredLevel, List<ReforgeData.Cost> costs, int xp, int failureChance) {
        public Charge {
            costs = List.copyOf(costs);
            if (requiredLevel < 0 || xp < 0) throw new IllegalArgumentException("Negative level or XP");
            if (failureChance < 0 || failureChance > 10_000) throw new IllegalArgumentException("Failure chance must be 0-100%");
        }

        /** A charge that never fails. */
        public Charge(int requiredLevel, List<ReforgeData.Cost> costs, int xp) { this(requiredLevel, costs, xp, 0); }

        /** The failure chance (hundredths of a percent) for a player with this proficiency and skill level. */
        public int failureChance(int proficiency, int skillLevel) {
            return Math.max(0, failureChance - FAILURE_DROP_PER_SKILL_LEVEL * skillLevel
                    - FAILURE_DROP_PER_LEVEL * Math.max(0, proficiency - requiredLevel));
        }
    }

    /** What a blessing did: a charge was added, or the costs were used up for nothing. */
    public record Result(boolean success, String message) { }

    private static volatile List<Charge> charges = List.of(
            new Charge(1, List.of(item("gold_ingot", 4)), 25, 2500),
            new Charge(2, List.of(item("diamond", 2)), 50, 3000),
            new Charge(4, List.of(item("diamond", 4)), 100, 3500),
            new Charge(6, List.of(item("netherite_ingot", 1)), 200, 4000),
            new Charge(8, List.of(item("nether_star", 1)), 400, 5000));
    /** Protected items taken out of a dead player's inventory, given back when they respawn. */
    private static final Map<UUID, List<Kept>> PENDING = new ConcurrentHashMap<>();

    /** A protected item and the slot it came from (Inventory numbering, then accessory slots after it). */
    public record Kept(int slot, ItemStack stack) { }

    private DivineProtection() { }

    public static void initialize() {
        ServerPlayerEvents.COPY_FROM.register((previous, player, alive) -> {
            List<Kept> kept = PENDING.remove(previous.getUUID());
            if (kept != null) restore(player, kept);
        });
    }

    /** Replaces the charges (exactly {@link #MAX_CHARGES}, in order). Call from a content mod's initializer. */
    public static void setCharges(List<Charge> list) {
        if (list.size() != MAX_CHARGES) throw new IllegalArgumentException("Divine Protection needs " + MAX_CHARGES + " charges");
        charges = List.copyOf(Objects.requireNonNull(list));
    }

    public static List<Charge> charges() { return charges; }

    public static ReforgeData.Cost item(String vanillaId, int amount) {
        return new ReforgeData.Cost("item", Identifier.withDefaultNamespace(vanillaId), amount);
    }

    public static int charges(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? 0 : data.copyTag().getIntOr(KEY, 0);
    }

    public static void setCharges(ItemStack stack, int amount) {
        int value = Math.max(0, Math.min(MAX_CHARGES, amount));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            if (value == 0) tag.remove(KEY);
            else tag.putInt(KEY, value);
        });
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
    }

    /** Why this item cannot get its next charge right now, or null when it can (costs aside). */
    public static String problem(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return "Place an item first";
        if (stack.getCount() != 1) return "Place one item at a time";
        int current = charges(stack);
        if (current >= MAX_CHARGES) return "This item has all " + MAX_CHARGES + " charges";
        int required = charges.get(current).requiredLevel();
        int level = ReforgeService.proficiency(player);
        if (level < required) return "Needs " + ekuzo.crazyworldprogression.skills.Proficiency.name(required) + " " + Skills.Skill.ENCHANTING.label() + " (you are " + ekuzo.crazyworldprogression.skills.Proficiency.name(level) + ")";
        return null;
    }

    /** Tries to add the next charge (it can fail, using up the costs). Throws when nothing was used up. */
    public static Result charge(ServerPlayer player, ItemStack stack) {
        String problem = problem(player, stack);
        if (problem != null) throw new IllegalArgumentException(problem);
        Charge next = charges.get(charges(stack));
        List<String> missing = ReforgeService.missingCosts(player, next.costs());
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing " + String.join(", ", missing));
        ReforgeService.payCosts(player, next.costs());
        boolean failed = player.getRandom().nextInt(10_000) < next.failureChance(ReforgeService.proficiency(player),
                Skills.level(player, Skills.Skill.ENCHANTING));
        Skills.add(player, Skills.Skill.ENCHANTING, next.xp());
        if (failed) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.8F, 1.2F);
            return new Result(false, "The blessing faded. The offering is lost.");
        }
        setCharges(stack, charges(stack) + 1);
        player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 0.8F, 1.4F);
        return new Result(true, "Divine Protection " + charges(stack) + "/" + MAX_CHARGES + ": " + stack.getHoverName().getString());
    }

    /**
     * Takes every protected item out of the inventory and accessory slots, using up one charge of each, and tells
     * the player. Put them back with {@link #restore}.
     */
    public static List<Kept> preserve(ServerPlayer player) { return preserve(player, true); }

    private static List<Kept> preserve(ServerPlayer player, boolean accessories) {
        List<Kept> kept = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (charges(stack) <= 0) continue;
            kept.add(new Kept(slot, use(player, stack.copy())));
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        if (!accessories) return kept;
        List<ItemStack> worn = Accessories.worn(player);
        for (int index = 0; index < worn.size(); index++) {
            if (charges(worn.get(index)) <= 0) continue;
            kept.add(new Kept(inventory.getContainerSize() + index, use(player, worn.get(index).copy())));
            Accessories.set(player, index, ItemStack.EMPTY);
        }
        return kept;
    }

    /** Puts preserved items back into their slots (or any free slot, or the player's feet when full). */
    public static void restore(ServerPlayer player, List<Kept> kept) {
        Inventory inventory = player.getInventory();
        for (Kept entry : kept) {
            int accessory = entry.slot() - inventory.getContainerSize();
            if (accessory >= 0 && accessory < Accessories.SLOTS && Accessories.worn(player).get(accessory).isEmpty()) {
                Accessories.set(player, accessory, entry.stack());
            } else if (accessory < 0 && inventory.getItem(entry.slot()).isEmpty()) {
                inventory.setItem(entry.slot(), entry.stack());
            } else if (!inventory.add(entry.stack())) {
                player.drop(entry.stack(), false);
            }
        }
        player.containerMenu.broadcastChanges();
    }

    /**
     * Death with keep-inventory off: protected inventory items wait for the respawn instead of dropping. Protected
     * accessories stay worn ({@link Accessories#dropAll}) and come along with the accessory slots.
     */
    public static void preserveForRespawn(ServerPlayer player) {
        List<Kept> kept = preserve(player, false);
        if (!kept.isEmpty()) PENDING.put(player.getUUID(), kept);
    }

    /** Copies of items with the protected ones left out (empty stacks), for snapshots that must not hold them. */
    public static List<ItemStack> withoutProtected(List<ItemStack> items) {
        return items.stream().map(stack -> charges(stack) > 0 ? ItemStack.EMPTY : stack).toList();
    }

    /** Uses up one charge of the item (in place) and tells the player it was kept. */
    public static ItemStack use(ServerPlayer player, ItemStack stack) {
        int left = charges(stack) - 1;
        setCharges(stack, left);
        player.sendSystemMessage(Component.literal("Divine Protection kept your " + stack.getHoverName().getString()
                + (left == 0 ? ". Its protection is used up." : " (" + left + " " + (left == 1 ? "charge" : "charges") + " left)."))
                .withStyle(left == 0 ? ChatFormatting.YELLOW : ChatFormatting.GOLD));
        return stack;
    }
}
