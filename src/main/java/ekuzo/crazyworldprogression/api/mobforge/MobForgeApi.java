package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/** Stable CWP entry point for content mods and other CWP systems. */
public final class MobForgeApi {
    private MobForgeApi() { }
    public static MobRegistry mobs() { return ekuzo.crazyworldprogression.mobforge.MobForge.mobs(); }
    public static AbilityRegistry abilities() { return ekuzo.crazyworldprogression.mobforge.MobForge.abilities(); }
    public static BehaviorRegistry behaviors() { return ekuzo.crazyworldprogression.mobforge.MobForge.behaviors(); }
    public static ScriptEntityHandle spawn(Identifier id, SpawnContext context) { return EntitySpawnService.spawn(id, context); }
    public static Optional<MobRuntime> runtime(Entity entity) { return MobRuntime.of(entity); }
    public static Optional<MobForgeHandle> handle(Entity entity) {
        return MobRuntime.of(entity).filter(runtime -> mobs().get(runtime.definitionId()).isPresent())
                .map(ignored -> new RegisteredMobHandle(entity));
    }
    public static int cancelPocketRuns(java.util.UUID pocketInstanceId) {
        return ekuzo.crazyworldprogression.mobforge.MobForge.SCRIPT_EXECUTOR.cancelPocket(java.util.Objects.requireNonNull(pocketInstanceId));
    }
}
