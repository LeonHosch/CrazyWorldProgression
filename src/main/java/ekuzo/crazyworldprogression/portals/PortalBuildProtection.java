package ekuzo.crazyworldprogression.portals;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;

/** Prevent player block edits in protected instances while keeping ordinary block controls usable. */
public final class PortalBuildProtection {
    private PortalBuildProtection() { }

    public static void initialize() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) ->
                !(player instanceof ServerPlayer serverPlayer) || PortalService.canBuild(serverPlayer));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || PortalService.canBuild(serverPlayer)) {
                return InteractionResult.PASS;
            }
            var item = player.getItemInHand(hand).getItem();
            if (!(item instanceof BlockItem) && !(item instanceof BucketItem)
                    && !(item instanceof BoneMealItem) && !(item instanceof FireChargeItem)
                    && !(item instanceof FlintAndSteelItem) && !(item instanceof HoeItem)
                    && !(item instanceof ShovelItem) && !(item instanceof AxeItem)) return InteractionResult.PASS;
            var block = level.getBlockState(hit.getBlockPos()).getBlock();
            if (!player.isShiftKeyDown() && (level.getBlockState(hit.getBlockPos())
                    .getMenuProvider(level, hit.getBlockPos()) != null
                    || block instanceof DoorBlock || block instanceof TrapDoorBlock
                    || block instanceof FenceGateBlock || block instanceof ButtonBlock
                    || block instanceof LeverBlock)) return InteractionResult.PASS;
            return InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, level, hand) ->
                player instanceof ServerPlayer serverPlayer && !PortalService.canBuild(serverPlayer)
                        && player.getItemInHand(hand).getItem() instanceof BucketItem
                        ? InteractionResult.FAIL : InteractionResult.PASS);
    }
}
