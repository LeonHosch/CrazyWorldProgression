package ekuzo.crazyworldprogression.mixin;

import ekuzo.crazyworldprogression.portals.PortalEnvironmentService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Instance worlds skip vanilla mob spawning unless their portal turns it on. Script mobs are not affected. */
@Mixin(NaturalSpawner.class)
abstract class InstanceMobSpawnMixin {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void cwp$noInstanceSpawns(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
                                             List<MobCategory> categories, CallbackInfo info) {
        if (PortalEnvironmentService.mobSpawnsOff(level)) info.cancel();
    }
}
