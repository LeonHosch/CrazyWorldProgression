package ekuzo.crazyworldprogression.portals;

import net.minecraft.server.MinecraftServer;

/** Kept free of voice chat API types so parties work when Simple Voice Chat is absent. */
public final class PartyVoiceBridge {
    public interface Sync {
        void sync(MinecraftServer server, PartyBook parties);
    }

    private static volatile Sync sync;

    private PartyVoiceBridge() { }

    public static void install(Sync handler) { sync = handler; }

    public static void sync(MinecraftServer server, PartyBook parties) {
        Sync handler = sync;
        if (handler != null) handler.sync(server, parties);
    }
}
