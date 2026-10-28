package ekuzo.crazyworldprogression.skills;

import com.mojang.brigadier.Command;
import com.mojang.serialization.Codec;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Levels: from 0 to {@link #MAX_LEVEL}, gained only by doing things: cooking, fishing, farming, mining, dungeoning,
 * fighting, enchanting (named after ReforgeBranding's skill, e.g. Soulweaving; it levels by reforging and by charging
 * Divine Protection) and smithing (named after SmithingBranding, Craftsmanship by default; it levels by repairing). They are entirely separate from proficiency, which only skill tree nodes give (the stats
 * addFishingLevel, addCookingLevel, addEnchantingProficiency, ... that gate recipes, catches and reforges). A skill tree
 * node can require a level ({@code requires} in its YAML). XP sources: {@link SkillXp}. {@code /skills} shows them.
 *
 * <p>Each level's passive: Cooking, better dish quality and a chance of an extra batch (CookingService); Fishing,
 * shorter bite time (FishingHookLevelMixin) and better fish quality (ProduceQuality); Farming, produce quality;
 * Mining, a chance of double ore without Silk Touch ({@link SkillXp#miningBonus}); Dungeoning and Fighting, extra
 * loot rolls ({@link #bonusRoll}); Enchanting, a lower Divine Protection failure chance. Smithing has none yet.
 *
 * <p>Cooking, fishing, farming and smithing XP use {@link #xpFor}: the proficiency rank the dish, catch or repair needs, times
 * its quality.
 */
public final class Skills {
    public static final int MAX_LEVEL = 10;
    /** Total XP needed to reach each level (index = level). */
    private static final int[] THRESHOLDS = {0, 50, 150, 350, 700, 1200, 2000, 3200, 5000, 7500, 11000};
    public static final AttachmentType<Map<String, Integer>> XP = AttachmentRegistry.create(
            CrazyWorldProgression.id("skill_xp"),
            builder -> builder.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT)).copyOnDeath().initializer(Map::of));

    private Skills() { }

    public enum Skill {
        COOKING("Cooking"), FISHING("Fishing"), FARMING("Farming"), MINING("Mining"), DUNGEONING("Dungeoning"),
        FIGHTING("Fighting"), ENCHANTING("Enchanting"), SMITHING("Smithing");

        private final String label;

        Skill(String label) { this.label = label; }

        public String label() {
            if (this == ENCHANTING) return ekuzo.crazyworldprogression.reforge.ReforgeBranding.get().skill();
            if (this == SMITHING) return ekuzo.crazyworldprogression.smithing.SmithingBranding.get().name();
            return label;
        }

        public String key() { return name().toLowerCase(Locale.ROOT); }

        public static Skill parse(String value) {
            for (Skill skill : values())
                if (skill.key().equalsIgnoreCase(value.trim()) || skill.label().equalsIgnoreCase(value.trim())) return skill;
            throw new IllegalArgumentException("Unknown skill " + value
                    + " (cooking, fishing, farming, mining, dungeoning, fighting, enchanting, smithing)");
        }
    }

    public static void initialize() {
        SkillXp.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("skills").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    player.sendSystemMessage(Component.literal("Levels").withStyle(ChatFormatting.GOLD));
                    for (Skill skill : Skill.values()) {
                        int xp = xp(player, skill), level = levelFor(xp);
                        String progress = level >= MAX_LEVEL ? "max" : xp + "/" + THRESHOLDS[level + 1] + " XP";
                        player.sendSystemMessage(Component.literal(" " + skill.label() + " " + level + " · " + progress)
                                .withStyle(ChatFormatting.GRAY));
                    }
                    return Command.SINGLE_SUCCESS;
                })));
    }

    public static int xp(ServerPlayer player, Skill skill) {
        return player.getAttachedOrCreate(XP).getOrDefault(skill.key(), 0);
    }

    public static int level(ServerPlayer player, Skill skill) { return levelFor(xp(player, skill)); }

    public static int levelFor(int xp) {
        int level = 0;
        while (level < MAX_LEVEL && xp >= THRESHOLDS[level + 1]) level++;
        return level;
    }

    /** XP needed in total to reach a level. */
    public static int threshold(int level) { return THRESHOLDS[Math.max(0, Math.min(MAX_LEVEL, level))]; }

    /** Adds XP (up to the max level's total), shows it on the action bar and announces level-ups. */
    public static void add(ServerPlayer player, Skill skill, int amount) {
        if (amount <= 0) return;
        Map<String, Integer> all = new HashMap<>(player.getAttachedOrCreate(XP));
        int before = all.getOrDefault(skill.key(), 0);
        int after = (int) Math.min(THRESHOLDS[MAX_LEVEL], (long) before + amount);
        if (after == before) return;
        all.put(skill.key(), after);
        player.setAttached(XP, Map.copyOf(all));
        int oldLevel = levelFor(before), newLevel = levelFor(after);
        String progress = newLevel >= MAX_LEVEL ? "max" : after + "/" + THRESHOLDS[newLevel + 1];
        player.sendOverlayMessage(Component.literal("+" + (after - before) + " " + skill.label() + " XP (" + progress + ")")
                .withStyle(ChatFormatting.GREEN));
        if (newLevel > oldLevel) {
            player.sendSystemMessage(Component.literal(skill.label() + " reached level " + newLevel + "!")
                    .withStyle(ChatFormatting.GOLD));
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.2F);
        }
    }

    /** Chance per level of an extra loot pool roll: Dungeoning for script loot pools, Fighting for mob loot pools. */
    public static final double BONUS_ROLL_PER_LEVEL = 0.03;

    /** Rolls the level's extra loot roll chance (3% per level, 30% at the max), telling the player when it hits. */
    public static boolean bonusRoll(ServerPlayer player, Skill skill) {
        if (player.getRandom().nextDouble() >= level(player, skill) * BONUS_ROLL_PER_LEVEL) return false;
        player.sendOverlayMessage(Component.literal(skill.label() + " bonus: an extra loot roll!").withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** XP by the proficiency rank something needs, Novice to Master. */
    private static final int[] RANK_XP = {4, 8, 15, 25, 40};
    /** XP multiplier by quality, Miserable to Perfect. */
    private static final double[] QUALITY_XP = {0.5, 1.0, 1.25, 1.6, 2.5};
    /** The quality index of things without a quality (Fine), for {@link #xpFor}. */
    public static final int FINE = 2;

    /**
     * Cooking, fishing and smithing XP for one dish, item or repair: the rank it needs (0 counts as Novice) times its
     * quality index (0 Miserable to 4 Perfect; use {@link #FINE} for things without a quality). Novice and Miserable
     * give 2, Master and Perfect 100.
     */
    public static int xpFor(int rank, int quality) {
        int r = Math.max(1, Math.min(RANK_XP.length, rank)) - 1;
        int q = Math.max(0, Math.min(QUALITY_XP.length - 1, quality));
        return (int) Math.round(RANK_XP[r] * QUALITY_XP[q]);
    }

}
