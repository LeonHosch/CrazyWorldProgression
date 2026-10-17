package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.EchelonClientSettings;
import ekuzo.crazyworldprogression.client.TitleClient;
import ekuzo.crazyworldprogression.client.TitleRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarTitleMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void echelon$addTitle(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo callback) {
        ((TitleRenderState) state).echelon$setTitle(null);
        if (!(entity instanceof Player player) || state.nameTag == null) return;
        var display = TitleClient.get(player.getUUID());
        if (display != null) {
            state.nameTag = Component.literal(player.getName().getString()).withColor(TitleClient.color(display));
            if (EchelonClientSettings.showTitles)
                ((TitleRenderState) state).echelon$setTitle(Component.literal(display.title())
                        .withColor(TitleClient.color(display)));
        }
        if (!EchelonClientSettings.showPlayerNames) state.nameTag = null;
    }

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("TAIL"))
    private void echelon$renderTitle(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                     CameraRenderState camera, CallbackInfo callback) {
        Component title = ((TitleRenderState) state).echelon$getTitle();
        if (title == null || state.nameTagAttachment == null) return;
        poseStack.pushPose();
        poseStack.translate(0.0F, -0.259F, 0.0F);
        collector.submitNameTag(poseStack, state.nameTagAttachment, state.showExtraEars ? -10 : 0,
                title, !state.isDiscrete, state.lightCoords, camera);
        poseStack.popPose();
    }
}
