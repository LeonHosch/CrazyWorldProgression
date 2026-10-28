package ekuzo.crazyworldprogression.progression.equipment;

import ekuzo.crazyworldprogression.accessory.Accessories;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillStatRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Applies equipment-set bonuses through the skill stat totals. Worn pieces are armor and, for players, accessories.
 * Stats are refreshed when armor or accessories change and a bonus switches on or off.
 */
public final class EquipmentSetService {
    /** Bonuses each player had active at the last refresh, as "set id#bonus index". */
    private static final Map<ServerPlayer, Set<String>> ACTIVE = new WeakHashMap<>();
    private static final List<EquipmentSlot> ARMOR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private EquipmentSetService() { }

    public static void initialize() {
        ServerEntityEvents.EQUIPMENT_CHANGE.register((entity, slot, previous, next) -> {
            if (entity instanceof ServerPlayer player && ARMOR.contains(slot)) refreshIfChanged(player);
        });
    }

    /** Refreshes the player's stats when the set of active bonuses changed. */
    public static void refreshIfChanged(ServerPlayer player) {
        if (!activeBonuses(player).equals(ACTIVE.getOrDefault(player, Set.of()))) SkillStatRegistry.refresh(player);
    }

    private static List<ItemStack> wornItems(LivingEntity entity) {
        List<ItemStack> items = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR) items.add(entity.getItemBySlot(slot));
        if (entity instanceof Player player) items.addAll(Accessories.worn(player));
        return items;
    }

    /** How many different pieces of the set the entity wears. */
    public static int worn(LivingEntity entity, EquipmentSetRegistry.EquipmentSet set) {
        Set<Identifier> found = new LinkedHashSet<>();
        for (ItemStack stack : wornItems(entity)) {
            if (stack.isEmpty()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (set.pieces().contains(id)) found.add(id);
        }
        return found.size();
    }

    private static Set<String> activeBonuses(LivingEntity entity) {
        Set<String> active = new LinkedHashSet<>();
        for (EquipmentSetRegistry.EquipmentSet set : EquipmentSetRegistry.sets()) {
            int worn = worn(entity, set);
            for (int index = 0; index < set.bonuses().size(); index++)
                if (worn >= set.bonuses().get(index).pieces()) active.add(set.id() + "#" + index);
        }
        return active;
    }

    /** Summed raw stat values of every active bonus, keyed like the skill stat totals. */
    public static Map<String, Double> totals(ServerPlayer player) {
        Map<String, Double> totals = new LinkedHashMap<>();
        Set<String> active = activeBonuses(player);
        ACTIVE.put(player, active);
        if (active.isEmpty()) return totals;
        for (EquipmentSetRegistry.EquipmentSet set : EquipmentSetRegistry.sets())
            for (int index = 0; index < set.bonuses().size(); index++) {
                if (!active.contains(set.id() + "#" + index)) continue;
                for (String expression : set.bonuses().get(index).stats()) {
                    SkillStatRegistry.ParsedStat stat = SkillStatRegistry.parse(expression);
                    totals.merge(stat.functionName(), stat.value(), Double::sum);
                }
            }
        return totals;
    }

    /** Sets the item belongs to, for tooltips. */
    public static List<EquipmentSetRegistry.EquipmentSet> setsOf(Identifier item) {
        return EquipmentSetRegistry.sets().stream().filter(set -> set.pieces().contains(item)).toList();
    }
}
