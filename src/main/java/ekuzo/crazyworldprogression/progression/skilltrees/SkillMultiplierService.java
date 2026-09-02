package ekuzo.crazyworldprogression.progression.skilltrees;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Applies CWP's built-in percentage reward multipliers without changing unrelated administrative operations. */
public final class SkillMultiplierService {
    private static final int MAX_GENERATED_DROP_STACKS = 1024;
    public static final String MOB_DROP = "addMobDropMultiplier";
    public static final String EXPERIENCE = "addExperienceMultiplier";
    public static final String FARMING = "addFarmingMultiplier";

    // Prevent instantiation of the stat-backed multiplier utility.
    private SkillMultiplierService() {
    }

    // Return the mob-loot multiplier for a connected player, where a YAML value of 50 becomes 1.5.
    public static double mobDropMultiplier(ServerPlayer player) {
        return multiplier(player, MOB_DROP);
    }

    // Return the received-experience multiplier for a connected player.
    public static double experienceMultiplier(ServerPlayer player) {
        return multiplier(player, EXPERIENCE);
    }

    // Return the tagged-crop drop multiplier for a connected player.
    public static double farmingMultiplier(ServerPlayer player) {
        return multiplier(player, FARMING);
    }

    // Scale positive experience points while leaving zero and XP-removal operations unchanged.
    public static int multiplyExperience(ServerPlayer player, int amount) {
        if (amount <= 0) return amount;
        return (int) Math.min(Integer.MAX_VALUE, multiplyWhole(amount, experienceMultiplier(player)));
    }

    // Create safely split copies of loot stacks using randomized rounding for fractional expected drops.
    public static List<ItemStack> multiplyDrops(ServerPlayer player, List<ItemStack> drops, double multiplier) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack drop : drops) appendMultiplied(player.getRandom(), result::add, drop, multiplier);
        return List.copyOf(result);
    }

    // Forward one loot stack as zero or more legal stack-sized copies after randomized multiplication.
    public static void appendMultiplied(RandomSource random, Consumer<ItemStack> output,
                                        ItemStack original, double multiplier) {
        long remaining = multiplyRandomized(original.getCount(), multiplier, random);
        remaining = Math.min(remaining, (long) original.getMaxStackSize() * MAX_GENERATED_DROP_STACKS);
        while (remaining > 0L) {
            int count = (int) Math.min(remaining, original.getMaxStackSize());
            output.accept(original.copyWithCount(count));
            remaining -= count;
        }
    }

    // Convert a registered percentage bonus into a non-negative multiplier for a connected player.
    private static double multiplier(ServerPlayer player, String functionName) {
        return multiplier(SkillStatRegistry.getTotal(player, functionName), functionName);
    }

    // Combine one declared baseline with a cumulative YAML percentage and clamp reductions at zero output.
    private static double multiplier(double percentage, String functionName) {
        SkillStatRegistry.StatDefinition definition = SkillStatRegistry.definition(functionName);
        return Math.max(0.0, BaselineStatRegistry.calculate(1.0,
                BaselineStatRegistry.modifiers(functionName), definition.operation(), percentage * definition.scale()));
    }

    // Multiply a whole-number reward with deterministic floor rounding and saturated overflow detection.
    private static long multiplyWhole(long amount, double multiplier) {
        double result = amount * multiplier;
        if (!Double.isFinite(result) || result >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.floor(result);
    }

    // Randomly round a fractional drop expectation so repeated drops preserve the configured average multiplier.
    private static long multiplyRandomized(long amount, double multiplier, RandomSource random) {
        double result = amount * multiplier;
        if (!Double.isFinite(result) || result >= Long.MAX_VALUE) return Long.MAX_VALUE;
        long whole = (long) Math.floor(result);
        return whole < Long.MAX_VALUE && random.nextDouble() < result - whole ? whole + 1L : whole;
    }
}
