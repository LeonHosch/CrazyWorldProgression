package ekuzo.crazyworldprogression.skills;

import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where skill XP comes from (fishing and cooking award theirs where the catch or dish is made):
 * <ul>
 *   <li>Mining: breaking ores without Silk Touch, by ore ({@link #ORES}). Ores a player placed give nothing. The Mining
 *       level can double those ores' drops ({@link #miningBonus}).</li>
 *   <li>Farming: harvesting a fully grown crop (or a melon/pumpkin grown from a stem), butchering an animal,
 *       collecting eggs and milk, by the rank needed and the produce's quality (ProduceQuality.farmingXp).</li>
 *   <li>Fighting: killing a MobForge mob gives its template's XP; other mobs (and templates with XP -1) use
 *       {@link #MOBS}. MobForge mobs drop no vanilla XP orbs.</li>
 * </ul>
 */
public final class SkillXp {
    /** Mining XP per ore block (path of the block ID; deepslate variants count the same). */
    static final Map<String, Integer> ORES = Map.ofEntries(
            Map.entry("coal_ore", 1), Map.entry("deepslate_coal_ore", 1),
            Map.entry("copper_ore", 1), Map.entry("deepslate_copper_ore", 1),
            Map.entry("iron_ore", 5), Map.entry("deepslate_iron_ore", 5),
            Map.entry("gold_ore", 10), Map.entry("deepslate_gold_ore", 10), Map.entry("nether_gold_ore", 3),
            Map.entry("redstone_ore", 5), Map.entry("deepslate_redstone_ore", 5),
            Map.entry("lapis_ore", 8), Map.entry("deepslate_lapis_ore", 8),
            Map.entry("diamond_ore", 20), Map.entry("deepslate_diamond_ore", 20),
            Map.entry("emerald_ore", 15), Map.entry("deepslate_emerald_ore", 15),
            Map.entry("nether_quartz_ore", 2), Map.entry("ancient_debris", 25));

    /** Fighting XP per vanilla mob type ID (also for MobForge templates with XP -1). Unlisted hostile mobs give 1. */
    static final Map<String, Integer> MOBS = Map.ofEntries(
            Map.entry("zombie", 1), Map.entry("husk", 1), Map.entry("drowned", 1),
            Map.entry("zombie_villager", 1), Map.entry("skeleton", 1), Map.entry("stray", 1),
            Map.entry("bogged", 1), Map.entry("spider", 1), Map.entry("cave_spider", 1),
            Map.entry("silverfish", 1), Map.entry("endermite", 1), Map.entry("slime", 1),
            Map.entry("magma_cube", 1), Map.entry("phantom", 2), Map.entry("creeper", 3),
            Map.entry("witch", 3), Map.entry("pillager", 3), Map.entry("vindicator", 3),
            Map.entry("guardian", 3), Map.entry("shulker", 3), Map.entry("blaze", 3),
            Map.entry("hoglin", 3), Map.entry("zoglin", 3), Map.entry("piglin", 2),
            Map.entry("zombified_piglin", 2), Map.entry("vex", 2), Map.entry("enderman", 5),
            Map.entry("ghast", 5), Map.entry("wither_skeleton", 5), Map.entry("piglin_brute", 5),
            Map.entry("breeze", 5), Map.entry("evoker", 8), Map.entry("ravager", 15),
            Map.entry("elder_guardian", 50), Map.entry("warden", 50), Map.entry("wither", 100),
            Map.entry("ender_dragon", 200));

    private SkillXp() { }

    /** A melon or pumpkin about to be broken that still had its stem attached (the stem detaches when it breaks). */
    private static final ThreadLocal<BlockPos> GROWN_FRUIT = new ThreadLocal<>();

    static void initialize() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            GROWN_FRUIT.set(isStemFruit(state.getBlock()) && level instanceof ServerLevel server && stemAttached(server, pos)
                    ? pos.immutable() : null);
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || player.isCreative() || !(level instanceof ServerLevel server)) return;
            boolean placed = PlacedBlocks.get(server).remove(server, pos);
            if (placed) return;
            int mining = miningXp(state);
            if (mining > 0 && !silkTouch(server, player.getMainHandItem())) Skills.add(serverPlayer, Skills.Skill.MINING, mining);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            ServerPlayer killer = source.getEntity() instanceof ServerPlayer player ? player
                    : entity.getKillCredit() instanceof ServerPlayer player ? player : null;
            if (killer == null || entity instanceof Player) return;
            Skills.add(killer, Skills.Skill.FIGHTING, fightingXp(entity));
        });
    }

    /** A player placed a block: remember it if breaking it would give XP. */
    public static void placed(ServerLevel level, BlockPos pos, BlockState state) {
        if (miningXp(state) > 0 || isStemFruit(state.getBlock())) PlacedBlocks.get(level).add(level, pos);
    }

    static int miningXp(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals("minecraft") ? ORES.getOrDefault(id.getPath(), 0) : 0;
    }

    /** Chance per Mining level that an ore broken without Silk Touch drops everything twice (50% at level 10). */
    public static final double DOUBLE_ORE_PER_LEVEL = 0.05;

    /**
     * The Mining level's passive: the drops of an ore (one that gives mining XP, not placed by a player) a player
     * broke without Silk Touch, sometimes doubled.
     */
    public static List<ItemStack> miningBonus(ServerLevel level, BlockPos pos, BlockState state, Entity breaker,
                                              ItemStack tool, List<ItemStack> drops) {
        if (!(breaker instanceof ServerPlayer player) || player.isCreative() || drops.isEmpty() || miningXp(state) <= 0
                || (tool != null && silkTouch(level, tool)) || PlacedBlocks.get(level).contains(level, pos)) return drops;
        if (player.getRandom().nextDouble() >= Skills.level(player, Skills.Skill.MINING) * DOUBLE_ORE_PER_LEVEL) return drops;
        List<ItemStack> doubled = new ArrayList<>(drops.size() * 2);
        for (ItemStack stack : drops) {
            doubled.add(stack);
            doubled.add(stack.copy());
        }
        player.sendOverlayMessage(Component.literal(Skills.Skill.MINING.label() + " bonus: double ore!").withStyle(ChatFormatting.GOLD));
        return doubled;
    }

    private static boolean silkTouch(ServerLevel level, ItemStack tool) {
        var silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.SILK_TOUCH);
        return silk.isPresent() && EnchantmentHelper.getItemEnchantmentLevel(silk.get(), tool) > 0;
    }

    /**
     * Whether a block a player is breaking right now is a fully grown crop (or a melon/pumpkin grown from a stem), for
     * its drops. Unlike {@link #grownCrop} this does not use up the stem record.
     */
    public static boolean grownProduce(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return crop.isMaxAge(state);
        if (block instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        if (block instanceof CocoaBlock) return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        return isStemFruit(block) && pos.equals(GROWN_FRUIT.get());
    }

    private static boolean stemAttached(ServerLevel level, BlockPos pos) {
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL)
            if (level.getBlockState(pos.relative(direction)).getBlock() instanceof AttachedStemBlock) return true;
        return false;
    }

    private static boolean isStemFruit(Block block) {
        return block == net.minecraft.world.level.block.Blocks.MELON || block == net.minecraft.world.level.block.Blocks.PUMPKIN;
    }

    static int fightingXp(LivingEntity entity) {
        int template = MobForgeEntityData.xp(entity).orElse(-1);
        if (template >= 0) return template;
        var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        Integer known = type.getNamespace().equals("minecraft") ? MOBS.get(type.getPath()) : null;
        if (known != null) return known;
        return entity instanceof net.minecraft.world.entity.monster.Enemy ? 1 : 0;
    }
}
