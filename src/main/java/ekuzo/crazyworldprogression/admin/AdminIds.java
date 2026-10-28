package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.List;

/** Plain names in CWP's admin editor; persisted IDs remain fully namespaced. */
final class AdminIds {
    private AdminIds() { }

    static Identifier create(String raw) {
        String value = required(raw);
        Identifier id = Identifier.tryParse(value.contains(":") ? value
                : CrazyWorldProgression.MOD_ID + ":" + value);
        if (id == null) throw new IllegalArgumentException("Invalid ID: " + value);
        return id;
    }

    static Identifier reference(String raw, Collection<Identifier> existing) {
        String value = required(raw);
        if (value.contains(":")) return create(value);
        List<Identifier> matches = existing.stream().filter(id -> id.getPath().equals(value)).distinct().toList();
        if (matches.size() == 1) return matches.getFirst();
        if (matches.size() > 1)
            throw new IllegalArgumentException("Several IDs use '" + value + "'. Enter mod:name to choose one.");
        return create(value);
    }

    static Identifier createAvailable(String raw, Collection<Identifier> existing) {
        Identifier id = create(raw);
        boolean bare = raw != null && !raw.contains(":");
        if (existing.stream().anyMatch(previous -> previous.equals(id)
                || bare && previous.getPath().equals(id.getPath())))
            throw new IllegalArgumentException("ID already exists: " + raw.trim());
        return id;
    }

    static String display(Identifier id) {
        return id.getNamespace().equals(CrazyWorldProgression.MOD_ID) || id.getNamespace().equals("cwp")
                ? id.getPath() : id.toString();
    }

    private static String required(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter an ID");
        return value;
    }
}
