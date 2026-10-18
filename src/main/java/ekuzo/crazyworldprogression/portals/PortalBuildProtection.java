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
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.MinecartItem;
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
            if (player instanceof ServerPlayer serverPlayer && spawnEggBlocked(serverPlayer, player.getItemInHand(hand).getItem()))
                return InteractionResult.FAIL;
            if (player instanceof ServerPlayer serverPlayer && PortalService.inInstance(serverPlayer)
                    && placesEntity(player.getItemInHand(hand).getItem())) {
                boolean opensBlock = !player.isShiftKeyDown()
                        && level.getBlockState(hit.getBlockPos()).getMenuProvider(level, hit.getBlockPos()) != null;
                if (!opensBlock) {
                    serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                            "You cannot place that inside an instance."));
                    return InteractionResult.FAIL;
                }
            }
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
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            Item item = player.getItemInHand(hand).getItem();
            if (spawnEggBlocked(serverPlayer, item)) return InteractionResult.FAIL;
            if (PortalService.inInstance(serverPlayer) && placesEntity(item)) {
                serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                        "You cannot place that inside an instance."));
                return InteractionResult.FAIL;
            }
            return !PortalService.canBuild(serverPlayer) && item instanceof BucketItem
                    ? InteractionResult.FAIL : InteractionResult.PASS;
        });
    }

    /**
     * Spawn eggs (also on spawners) only work for administrators in creative mode, everywhere. Dispensers are not
     * affected.
     */
    static boolean spawnEggBlocked(ServerPlayer player, Item item) {
        if (!(item instanceof net.minecraft.world.item.SpawnEggItem)) return false;
        if (player.isCreative() && player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
            return false;
        player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Spawn eggs are disabled."));
        return true;
    }

    /**
     * Items whose use spawns a persistent entity. Blocked for players in every portal instance, even when
     * the portal allows building; dispensers do not go through these player callbacks and keep working.
     */
    static boolean placesEntity(Item item) {
        return item instanceof BoatItem || item instanceof MinecartItem || item instanceof ArmorStandItem
                || item instanceof HangingEntityItem || item instanceof EndCrystalItem || item instanceof LeadItem;
    }
}
