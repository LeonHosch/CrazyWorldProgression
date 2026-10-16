package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class AbilityRegistry {
    private final Map<Identifier, Supplier<? extends MobAbility>> entries = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void register(Identifier id, Supplier<? extends MobAbility> factory) {
        requireOpen();
        Objects.requireNonNull(id, "ability ID");
        Objects.requireNonNull(factory, "ability factory for " + id);
        if (entries.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("Duplicate MobForge ability: " + id);
    }
    public synchronized Optional<Supplier<? extends MobAbility>> get(Identifier id) { return Optional.ofNullable(entries.get(id)); }
    public synchronized Map<Identifier, Supplier<? extends MobAbility>> all() { return Map.copyOf(entries); }
    public synchronized void freeze() { frozen = true; }
    private void requireOpen() { if (frozen) throw new IllegalStateException("MobForge ability registration closed; register during Fabric mod initialization"); }
}
