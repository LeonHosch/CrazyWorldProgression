package ekuzo.crazyworldprogression.farming;

import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.skills.Proficiency;
import ekuzo.crazyworldprogression.skills.Skills;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Produce quality: meat from animals a player kills, crops a player harvests fully grown, eggs a player picks up and
 * fish a player catches get a quality, from Miserable to Perfect. Which items count is set by the item tags
 * {@code crazy-world-progression:quality_meat}, {@code quality_crops}, {@code quality_eggs} and {@code quality_fish}
 * (seeds are not in them). Fish use the fishing score instead ({@link #fishingScore}).
 *
 * <p>The player's farming score is their Farming skill level plus twice their farming proficiency ranks above Novice
 * (addFarmingLevel), so the proficiency counts double. A higher score gives better quality on average and less chance to ruin the produce: at
 * score 0 half of all harvests, kills and eggs give no produce at all, falling to none at score 10.
 *
 * <p>Produce made any other way is always Miserable, to punish automation: crops broken by water, pistons or anything
 * but a player, meat from mobs a player did not kill, and produce a hopper picks up. So is food from vanilla recipes
 * (furnace, smoker, campfire, crafting table). The CWP cooking stations roll the dish's quality from its ingredients
 * ({@link #cook}).
 *
 * <p>Eating food with a quality restores part of its hunger and saturation ({@link #NUTRITION}, {@link #SATURATION}).
 */
public final class ProduceQuality {
    public enum Quality {
        MISERABLE("Miserable", ChatFormatting.DARK_GRAY),
        NORMAL("Normal", ChatFormatting.WHITE),
        FINE("Fine", ChatFormatting.GREEN),
        SUPERIOR("Superior", ChatFormatting.AQUA),
        PERFECT("Perfect", ChatFormatting.GOLD);

        private final String label;
        private final ChatFormatting color;

        Quality(String label, ChatFormatting color) {
            this.label = label;
            this.color = color;
        }

        public String label() { return label; }

        public ChatFormatting color() { return color; }

        public static Quality of(int index) { return values()[Math.max(0, Math.min(values().length - 1, index))]; }
    }

    /** The quality on a stack, as its index (0 Miserable … 4 Perfect). */
    public static final DataComponentType<Integer> QUALITY = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            CrazyWorldProgression.id("quality"), DataComponentType.<Integer>builder()
                    .persistent(Codec.intRange(0, 4)).networkSynchronized(ByteBufCodecs.VAR_INT).build());
    public static final TagKey<Item> MEAT = tag("quality_meat");
    public static final TagKey<Item> CROPS = tag("quality_crops");
    public static final TagKey<Item> EGGS = tag("quality_eggs");
    public static final TagKey<Item> FISH = tag("quality_fish");
    /** Hidden cooking value of each quality, Miserable to Perfect. */
    static final int[] VALUES = {1, 3, 5, 8, 12};
    /** Share of the hunger and saturation a food restores, by quality (Miserable to Perfect). */
    public static final double[] NUTRITION = {0.5, 0.75, 0.9, 1.0, 1.0};
    public static final double[] SATURATION = {0.25, 0.5, 0.75, 1.0, 1.5};
    /** Chance to ruin the produce at score 0, and how much each score point takes off. */
    static final double RUIN_AT_ZERO = 0.5;
    static final double RUIN_PER_POINT = 0.05;
    /**
     * A dish's quality roll: the ingredients' quality, less {@link #COOK_OFFSET}, plus {@link #COOK_PER_POINT} per point
     * of cooking score (Cooking level + 2 x proficiency ranks above Novice, 0 to 18). With Perfect ingredients: 1% Perfect
     * for a level 0 Novice, 50% at level 5 Journeyman, 99% at level 10 Master.
     */
    static final double COOK_OFFSET = 1.66;
    static final double COOK_PER_POINT = 0.129;

    private ProduceQuality() { }

    private static TagKey<Item> tag(String path) { return TagKey.create(Registries.ITEM, CrazyWorldProgression.id(path)); }

    /** Loads the component (registered when this class loads). */
    public static void initialize() { }

    public static Quality get(ItemStack stack) {
        Integer index = stack.get(QUALITY);
        return index == null ? null : Quality.of(index);
    }

    /** Farming skill level plus twice the farming proficiency ranks above Novice. */
    public static int score(ServerPlayer player) {
        return Skills.level(player, Skills.Skill.FARMING)
                + 2 * (CrossbreedingService.level(player) - ekuzo.crazyworldprogression.skills.Proficiency.NOVICE);
    }

    /** Fishing skill level plus twice the fishing proficiency ranks above Novice, for caught fish. */
    public static int fishingScore(ServerPlayer player) {
        return Skills.level(player, Skills.Skill.FISHING)
                + 2 * (ekuzo.crazyworldprogression.loot.FishingJournal.level(player) - ekuzo.crazyworldprogression.skills.Proficiency.NOVICE);
    }

    public static double ruinChance(int score) { return Math.max(0.0, RUIN_AT_ZERO - RUIN_PER_POINT * score); }

    /**
     * A random quality around an average that rises with the score: mostly Miserable and Normal at 0; at 18 (level 10
     * and Master) 25% Perfect.
     */
    public static Quality roll(RandomSource random, int score) {
        double average = 0.6 + 0.131 * score;
        return Quality.of((int) Math.round(average + random.nextGaussian() * 0.8));
    }

    /**
     * Farming XP for one harvest, kill or collected egg or milk ({@link Skills#xpFor}: the rank it needs and its
     * quality). Ruined produce gives none.
     */
    public static void farmingXp(ServerPlayer player, int rank, Quality quality) {
        Skills.add(player, Skills.Skill.FARMING, Skills.xpFor(rank, quality.ordinal()));
    }

    /** One harvest: the produce in {@code drops} is ruined (removed) or all gets one quality. Other drops stay. */
    public static List<ItemStack> harvest(ServerPlayer player, List<ItemStack> drops, TagKey<Item> produce) {
        return harvest(player, drops, produce, Proficiency.NOVICE);
    }

    /** {@link #harvest}, giving farming XP for crops by the crop's farming rank (fish give fishing XP elsewhere). */
    public static List<ItemStack> harvest(ServerPlayer player, List<ItemStack> drops, TagKey<Item> produce, int rank) {
        if (drops.stream().noneMatch(stack -> stack.is(produce) && !stack.has(QUALITY))) return drops;
        int score = produce == FISH ? fishingScore(player) : score(player);
        boolean ruined = player.getRandom().nextDouble() < ruinChance(score);
        Quality quality = ruined ? null : roll(player.getRandom(), score);
        List<ItemStack> result = new ArrayList<>(drops.size());
        for (ItemStack stack : drops) {
            if (!stack.is(produce) || stack.has(QUALITY)) result.add(stack);
            else if (!ruined) result.add(withQuality(stack, quality));
        }
        if (ruined) ruinedMessage(player, produce == MEAT ? "You botched the butchering. The meat is ruined."
                : produce == FISH ? "The fish slipped off the hook." : "Your clumsy harvest ruined the crop.");
        else if (produce != FISH) farmingXp(player, rank, quality);
        return List.copyOf(result);
    }

    /**
     * Mob loot for one kill: stacks pass through to {@code output}; meat is ruined or gets a quality, decided once for
     * the whole kill.
     */
    public static Consumer<ItemStack> butcher(ServerPlayer player, Consumer<ItemStack> output) {
        Quality[] decided = new Quality[1];
        boolean[] ruined = {false, false};
        return stack -> {
            if (!stack.is(MEAT) || stack.has(QUALITY)) {
                output.accept(stack);
                return;
            }
            if (!ruined[1]) {
                int score = score(player);
                ruined[1] = true;
                ruined[0] = player.getRandom().nextDouble() < ruinChance(score);
                if (ruined[0]) ruinedMessage(player, "You botched the butchering. The meat is ruined.");
                else {
                    decided[0] = roll(player.getRandom(), score);
                    farmingXp(player, Proficiency.NOVICE, decided[0]);
                }
            }
            if (!ruined[0]) output.accept(withQuality(stack, decided[0]));
        };
    }

    /**
     * The same per-kill meat rule for loot pool rolls (mob drops): the first unrated meat stack decides whether the
     * kill's meat is ruined (empty stacks) or which quality it all gets.
     */
    public static java.util.function.UnaryOperator<ItemStack> butcherAdjust(ServerPlayer player) {
        Quality[] decided = new Quality[1];
        boolean[] ruined = {false, false};
        return stack -> {
            if (!stack.is(MEAT) || stack.has(QUALITY)) return stack;
            if (!ruined[1]) {
                int score = score(player);
                ruined[1] = true;
                ruined[0] = player.getRandom().nextDouble() < ruinChance(score);
                if (ruined[0]) ruinedMessage(player, "You botched the butchering. The meat is ruined.");
                else {
                    decided[0] = roll(player.getRandom(), score);
                    farmingXp(player, Proficiency.NOVICE, decided[0]);
                }
            }
            return ruined[0] ? ItemStack.EMPTY : withQuality(stack, decided[0]);
        };
    }

    /** A player collects an egg from a chicken: it cracks (empty) or gets a quality, with farming XP. */
    public static ItemStack collectEgg(ServerPlayer player, ItemStack stack) {
        if (!stack.is(EGGS) || stack.has(QUALITY)) return stack;
        int score = score(player);
        if (player.getRandom().nextDouble() < ruinChance(score)) {
            ruinedMessage(player, "The egg cracked in your clumsy hands.");
            return ItemStack.EMPTY;
        }
        Quality quality = roll(player.getRandom(), score);
        farmingXp(player, Proficiency.NOVICE, quality);
        return withQuality(stack, quality);
    }

    /** A player touches an egg on the ground: it cracks in their hands or gets a quality. */
    public static boolean pickUpEgg(ServerPlayer player, ItemEntity egg) {
        ItemStack stack = egg.getItem();
        if (!stack.is(EGGS) || stack.has(QUALITY) || player.isCreative() || player.isSpectator()) return true;
        int score = score(player);
        if (player.getRandom().nextDouble() < ruinChance(score)) {
            if (egg.level() instanceof ServerLevel level) {
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack.getItem()),
                        egg.getX(), egg.getY() + 0.2, egg.getZ(), 8, 0.1, 0.1, 0.1, 0.05);
                level.playSound(null, egg.blockPosition(), SoundEvents.TURTLE_EGG_CRACK, SoundSource.PLAYERS, 0.8F, 1.3F);
            }
            ruinedMessage(player, "The egg cracked in your clumsy hands.");
            egg.discard();
            return false;
        }
        Quality quality = roll(player.getRandom(), score);
        egg.setItem(withQuality(stack, quality));
        farmingXp(player, Proficiency.NOVICE, quality);
        return true;
    }

    /** Produce that can carry a quality (crops, meat, eggs) or any food. */
    public static boolean gradable(ItemStack stack) {
        return stack.is(CROPS) || stack.is(MEAT) || stack.is(EGGS) || stack.is(FISH) || stack.has(net.minecraft.core.component.DataComponents.FOOD);
    }

    /** Automation and vanilla recipes: produce and food without a quality becomes Miserable (changes the stack). */
    public static ItemStack miserable(ItemStack stack) {
        if (stack.isEmpty() || stack.has(QUALITY) || !gradable(stack)) return stack;
        stack.set(QUALITY, Quality.MISERABLE.ordinal());
        return stack;
    }

    /**
     * A cooked dish's quality: the hidden values of its ingredients (Miserable 1, Normal 3, Fine 5, Superior 8,
     * Perfect 12; produce or food without a quality counts as Miserable, other ingredients such as sugar do not count)
     * are added up and divided by the number of ingredients that count, and the quality is rolled around that average.
     * With no ingredient that counts, the ingredients count as Normal. The cook's score shifts that average (see
     * {@link #COOK_OFFSET}): beginners cook below their ingredients, masters above.
     */
    public static Quality cook(RandomSource random, List<ItemStack> ingredients, int cookingLevel, int proficiency) {
        int sum = 0, counted = 0;
        for (ItemStack ingredient : ingredients) {
            if (ingredient.isEmpty() || !gradable(ingredient)) continue;
            Quality quality = get(ingredient);
            sum += VALUES[quality == null ? 0 : quality.ordinal()];
            counted++;
        }
        double average = counted == 0 ? VALUES[1] : sum / (double) counted;
        int score = cookingLevel + 2 * (proficiency - ekuzo.crazyworldprogression.skills.Proficiency.NOVICE);
        return Quality.of((int) Math.round(index(average) - COOK_OFFSET + COOK_PER_POINT * score + random.nextGaussian() * 0.5));
    }

    /** A value turned back into a (fractional) quality index between the qualities' values. */
    static double index(double value) {
        if (value <= VALUES[0]) return 0;
        for (int k = 1; k < VALUES.length; k++)
            if (value <= VALUES[k]) return k - 1 + (value - VALUES[k - 1]) / (double) (VALUES[k] - VALUES[k - 1]);
        return VALUES.length - 1;
    }

    public static ItemStack withQuality(ItemStack stack, Quality quality) {
        ItemStack copy = stack.copy();
        copy.set(QUALITY, quality.ordinal());
        return copy;
    }

    private static void ruinedMessage(ServerPlayer player, String text) {
        player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.RED));
    }
}
