package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.TitleRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateTitleMixin implements TitleRenderState {
    @Unique private Component cwp$title;
    @Override public void cwp$setTitle(Component title) { cwp$title = title; }
    @Override public Component cwp$getTitle() { return cwp$title; }
}
