package ekuzo.crazyworldprogression.api;

import net.minecraft.resources.Identifier;

/** Identifier rules at public CWP registration boundaries. */
public final class CwpIdentifiers {
    private CwpIdentifiers() { }

    /** Caller-defined content needs an explicit namespace; parsing a bare ID defaults to minecraft. */
    public static Identifier requireCustomRegistration(Identifier id) {
        if (id == null || id.getNamespace().equals("minecraft"))
            throw new IllegalArgumentException("Registered CWP content needs an explicit mod:name ID");
        return id;
    }
}
