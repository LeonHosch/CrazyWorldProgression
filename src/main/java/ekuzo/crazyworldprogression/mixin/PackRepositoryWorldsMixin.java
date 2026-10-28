package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.config.GeneratedWorlds;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/** Adds the generated instance and workshop worlds (GeneratedWorlds) to every data pack list. */
@Mixin(PackRepository.class)
abstract class PackRepositoryWorldsMixin {
    @Shadow @Final @Mutable private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cwp$addWorlds(RepositorySource[] given, CallbackInfo info) {
        // Only data pack lists have the vanilla server source; resource pack lists (client) are left alone.
        if (sources.stream().noneMatch(ServerPacksSource.class::isInstance)) return;
        Set<RepositorySource> extended = new LinkedHashSet<>(sources);
        extended.add(GeneratedWorlds::addPack);
        sources = extended;
    }
}
