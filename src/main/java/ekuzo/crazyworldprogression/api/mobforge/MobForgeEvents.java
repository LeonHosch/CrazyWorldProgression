package ekuzo.crazyworldprogression.api.mobforge;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Listener style matches CWP's portal lifecycle hooks. Events run on the server thread. */
public final class MobForgeEvents {
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private MobForgeEvents() { }
    public static void register(Listener listener) { LISTENERS.add(Objects.requireNonNull(listener)); }
    public static void spawned(MobForgeHandle mob, SpawnContext context) { dispatch(listener -> listener.onSpawned(mob, context)); }
    public static void phaseChanged(MobForgeHandle mob, String previous, String current) { dispatch(listener -> listener.onPhaseChanged(mob, previous, current)); }
    public static void abilityStarted(MobForgeHandle mob, Identifier ability) { dispatch(listener -> listener.onAbilityStarted(mob, ability)); }
    public static void abilityCompleted(MobForgeHandle mob, Identifier ability) { dispatch(listener -> listener.onAbilityCompleted(mob, ability)); }
    public static void abilityFailed(MobForgeHandle mob, Identifier ability, RuntimeException cause) { dispatch(listener -> listener.onAbilityFailed(mob, ability, cause)); }
    public static void damaged(MobForgeHandle mob, float amount) { dispatch(listener -> listener.onDamaged(mob, amount)); }
    public static void killed(MobForgeHandle mob, Entity killer) { dispatch(listener -> listener.onKilled(mob, killer)); }
    private static void dispatch(java.util.function.Consumer<Listener> call) {
        for (Listener listener : LISTENERS) try { call.accept(listener); }
        catch (RuntimeException exception) { CrazyWorldProgression.LOGGER.error("MobForge listener failed", exception); }
    }
    public interface Listener {
        default void onSpawned(MobForgeHandle mob, SpawnContext context) { }
        default void onPhaseChanged(MobForgeHandle mob, String previous, String current) { }
        default void onAbilityStarted(MobForgeHandle mob, Identifier ability) { }
        default void onAbilityCompleted(MobForgeHandle mob, Identifier ability) { }
        default void onAbilityFailed(MobForgeHandle mob, Identifier ability, RuntimeException cause) { }
        default void onDamaged(MobForgeHandle mob, float amount) { }
        default void onKilled(MobForgeHandle mob, Entity killer) { }
    }
}
