package ekuzo.crazyworldprogression.mixin.client;

import ekuzo.crazyworldprogression.client.TitleRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateTitleMixin implements TitleRenderState {
    @Unique private Component echelon$title;
    @Override public void echelon$setTitle(Component title) { echelon$title = title; }
    @Override public Component echelon$getTitle() { return echelon$title; }
}
