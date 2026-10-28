package ekuzo.crazyworldprogression.farming;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StemBlock;
import ekuzo.crazyworldprogression.mixin.StemBlockAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Farming levels of crossbred crops: a player below a crop's level cannot plant its seed, and breaking the crop
 * destroys it without drops. Protected blocks are the block the result places (the crop) and, for stem crops such as
 * melons and pumpkins, the fruit and the attached stem too. Note this covers every block of that kind, also ones
 * that grew naturally. They also only drop when a player breaks them (BlockStateDropsMixin): water, pistons,
 * explosions and the like just destroy them.
 */
public final class FarmingLevels {
    /** Block → farming level, rebuilt from the recipes when they change. */
    private static volatile Map<Block, Integer> protectedBlocks;

    private FarmingLevels() { }

    static void invalidate() { protectedBlocks = null; }

    /**
     * Whether a crossbred crop block may drop items: only when broken by a player who has its farming level (also in
     * creative). Other blocks always may. Players below the level never get here; their break is handled earlier.
     */
    public static boolean dropsFor(MinecraftServer server, Block block, net.minecraft.world.entity.Entity breaker) {
        if (server == null) return true;
        Map<Block, Integer> blocks = protectedBlocks;
        if (blocks != null && blocks.isEmpty()) return true;
        int needed = requiredLevel(server, block);
        if (needed <= 0 && !isProtected(server, block)) return true;
        return breaker instanceof ServerPlayer player && (player.isCreative() || CrossbreedingService.level(player) >= needed);
    }

    private static boolean isProtected(MinecraftServer server, Block block) {
        requiredLevel(server, block);
        return protectedBlocks != null && protectedBlocks.containsKey(block);
    }

    /** The farming level needed to harvest this block, or 0. */
    public static int requiredLevel(MinecraftServer server, Block block) {
        Map<Block, Integer> blocks = protectedBlocks;
        if (blocks == null) {
            Map<Block, Integer> built = new HashMap<>();
            for (CrossbreedingData.Recipe recipe : CrossbreedingService.recipes(server)) {
                if (!(BuiltInRegistries.ITEM.getValue(recipe.output()) instanceof BlockItem item)) continue;
                List<Block> crop = new ArrayList<>(List.of(item.getBlock()));
                if (item.getBlock() instanceof StemBlock stem) {
                    StemBlockAccessor accessor = (StemBlockAccessor) stem;
                    BuiltInRegistries.BLOCK.getOptional(accessor.cwp$fruit()).ifPresent(crop::add);
                    BuiltInRegistries.BLOCK.getOptional(accessor.cwp$attachedStem()).ifPresent(crop::add);
                }
                for (Block part : crop) built.merge(part, recipe.level(), Math::min);
            }
            protectedBlocks = blocks = Map.copyOf(built);
        }
        return blocks.getOrDefault(block, 0);
    }

    public static void initialize() {
        // Another world (single player) has other recipes.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> invalidate());
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || player.isCreative()) return InteractionResult.PASS;
            Item held = player.getItemInHand(hand).getItem();
            if (!(held instanceof BlockItem)) return InteractionResult.PASS;
            int needed = CrossbreedingService.requiredLevel(serverPlayer.level().getServer(), held);
            if (needed <= CrossbreedingService.level(serverPlayer)) return InteractionResult.PASS;
            tooLow(serverPlayer, needed, "plant");
            return InteractionResult.FAIL;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || player.isCreative()) return true;
            int needed = requiredLevel(serverPlayer.level().getServer(), state.getBlock());
            if (needed <= CrossbreedingService.level(serverPlayer)) return true;
            // Too inexperienced: the crop is ruined and nothing drops.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (level instanceof ServerLevel server)
                server.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5,
                        8, 0.25, 0.2, 0.25, 0.01);
            level.playSound(null, pos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 0.6F);
            tooLow(serverPlayer, needed, "harvest");
            return false;
        });
    }

    private static void tooLow(ServerPlayer player, int needed, String action) {
        player.sendOverlayMessage(Component.literal("You need " + ekuzo.crazyworldprogression.skills.Proficiency.name(needed) + " farming proficiency to " + action + " this crop"
                + (action.equals("harvest") ? ". It was ruined." : "")).withStyle(ChatFormatting.RED));
    }
}
