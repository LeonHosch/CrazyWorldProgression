package ekuzo.crazyworldprogression.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.smithing.ChestQuality;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import ekuzo.crazyworldprogression.client.ChestQualityStateHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chests look like their quality: Miserable weathered with rusty bands, Fine with iron, Superior dark with gold,
 * Perfect with diamond; Normal and chests from before qualities stay vanilla (textures/entity/chest/&lt;quality&gt;).
 */
@Mixin(ChestRenderer.class)
public abstract class ChestRendererQualityMixin {
    private static final String[] NAMES = {"miserable", null, "fine", "superior", "perfect"};

    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At("TAIL"))
    private void cwp$quality(BlockEntity entity, ChestRenderState state, float delta, Vec3 camera,
                             ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfo info) {
        ((ChestQualityStateHolder) state).cwp$setQuality(entity instanceof ChestBlockEntity ? ChestQuality.placed(entity) : -1);
    }

    @WrapOperation(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Sheets;chooseSprite(Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState$ChestMaterialType;Lnet/minecraft/world/level/block/state/properties/ChestType;)Lnet/minecraft/client/resources/model/sprite/SpriteId;"))
    private SpriteId cwp$qualitySprite(ChestRenderState.ChestMaterialType material, ChestType type, Operation<SpriteId> original,
                                       @Local(argsOnly = true) ChestRenderState state) {
        int quality = ((ChestQualityStateHolder) state).cwp$quality();
        boolean trapped = material == ChestRenderState.ChestMaterialType.TRAPPED;
        if (quality < 0 || quality >= NAMES.length || NAMES[quality] == null
                || !(trapped || material == ChestRenderState.ChestMaterialType.REGULAR)) return original.call(material, type);
        String side = type == ChestType.LEFT ? "_left" : type == ChestType.RIGHT ? "_right" : "";
        return new SpriteId(Sheets.CHEST_SHEET, CrazyWorldProgression.id("entity/chest/" + (trapped ? "trapped_" : "") + NAMES[quality] + side));
    }
}
