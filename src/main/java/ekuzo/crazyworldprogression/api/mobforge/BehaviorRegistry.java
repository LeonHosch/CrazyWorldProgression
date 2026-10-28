package ekuzo.crazyworldprogression.api.mobforge;

import ekuzo.crazyworldprogression.api.CwpIdentifiers;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BehaviorRegistry {
    private final Map<Identifier, MobBehavior> entries = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void register(Identifier id, MobBehavior behavior) {
        requireOpen();
        Objects.requireNonNull(id, "behavior ID");
        CwpIdentifiers.requireCustomRegistration(id);
        Objects.requireNonNull(behavior, "behavior for " + id);
        if (entries.putIfAbsent(id, behavior) != null) throw new IllegalArgumentException("Duplicate MobForge behavior: " + id);
    }
    public synchronized Optional<MobBehavior> get(Identifier id) { return Optional.ofNullable(entries.get(id)); }
    public synchronized Map<Identifier, MobBehavior> all() { return Map.copyOf(entries); }
    public synchronized void freeze() { frozen = true; }
    private void requireOpen() { if (frozen) throw new IllegalStateException("MobForge behavior registration closed; register during Fabric mod initialization"); }
}
