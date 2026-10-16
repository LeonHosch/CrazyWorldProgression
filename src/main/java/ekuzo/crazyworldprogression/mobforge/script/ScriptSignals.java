package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicit script lifecycle callbacks for encounters and content mods. */
public final class ScriptSignals {
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private ScriptSignals() { }
    public static void register(Listener listener) { LISTENERS.add(java.util.Objects.requireNonNull(listener)); }
    static void trigger(UUID run, Identifier event, ServerLevel world, Map<String, ScriptEntityHandle> handles) {
        for (Listener listener : LISTENERS) try { listener.onTrigger(run, event, world, Map.copyOf(handles)); }
        catch (RuntimeException exception) { CrazyWorldProgression.LOGGER.error("Script trigger listener failed for {} in run {}", event, run, exception); }
    }
    static void failed(UUID run, String script, ServerLevel world, String reason) {
        for (Listener listener : LISTENERS) try { listener.onFailed(run, script, world, reason); }
        catch (RuntimeException exception) { CrazyWorldProgression.LOGGER.error("Script failure listener failed for run {}", run, exception); }
    }
    public interface Listener {
        default void onTrigger(UUID run, Identifier event, ServerLevel world, Map<String, ScriptEntityHandle> handles) { }
        default void onFailed(UUID run, String script, ServerLevel world, String reason) { }
    }
}
