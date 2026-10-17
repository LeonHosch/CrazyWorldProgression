package ekuzo.crazyworldprogression.portals;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

/**
 * Large encounter announcements shown near the top of the screen instead of chat. Players without the
 * client mod get the same text as a vanilla title.
 */
public final class EncounterBanner {
    private static final String KEY = "banner.crazy-world-progression.encounter.";

    private EncounterBanner() { }

    public enum Kind {
        STARTED(0x55FF55, "Encounter started", "Each player has one revive"),
        COMPLETE(0xFFD700, "Encounter complete", "Revives and entry locks reset"),
        WIPED(0xFF5555, "Raid wiped", "Attempt %s is ready"),
        RESUMED(0x55FFFF, "Checkpoint", "Resuming at encounter %s"),
        FAILED(0xFF5555, "Encounter failed", "Resetting in %s seconds"),
        CLUTCH(0xFFD700, "Cleared in the clutch!", "The encounter ended just in time");

        final int color;
        final String title;
        final String subtitle;

        Kind(int color, String title, String subtitle) {
            this.color = color;
            this.title = title;
            this.subtitle = subtitle;
        }

        public int color() { return color; }
        public String titleKey() { return KEY + name().toLowerCase(java.util.Locale.ROOT); }
        public String subtitleKey() { return titleKey() + ".subtitle"; }
    }

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
    }

    public static void show(Collection<ServerPlayer> players, Kind kind, String argument) {
        Payload payload = new Payload(kind.name(), argument == null ? "" : argument);
        for (ServerPlayer player : players) {
            if (ServerPlayNetworking.canSend(player, Payload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
                continue;
            }
            player.connection.send(new ClientboundSetTitlesAnimationPacket(6, 50, 14));
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.translatableWithFallback(kind.titleKey(), kind.title).withColor(kind.color)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.translatableWithFallback(kind.subtitleKey(), kind.subtitle, payload.argument())));
        }
    }

    public record Payload(String kind, String argument) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(CrazyWorldProgression.id("encounter_banner"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), Payload::kind, ByteBufCodecs.stringUtf8(64), Payload::argument,
                Payload::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
