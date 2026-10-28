package ekuzo.crazyworldprogression.portals;

import java.util.UUID;
import java.util.function.BiFunction;

/** The optional voice plugin receives audio on a voice thread; the client installs its local gain lookup here. */
public final class PartyVoiceVolumeBridge {
    private static volatile BiFunction<UUID, short[], short[]> adjuster;

    private PartyVoiceVolumeBridge() { }

    public static void install(BiFunction<UUID, short[], short[]> handler) { adjuster = handler; }

    public static short[] adjust(UUID sender, short[] audio) {
        BiFunction<UUID, short[], short[]> handler = adjuster;
        return handler == null ? audio : handler.apply(sender, audio);
    }
}
