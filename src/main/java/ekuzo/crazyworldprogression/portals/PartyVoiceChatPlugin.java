package ekuzo.crazyworldprogression.portals;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** SVC loads this entrypoint only when installed; the ordinary party code has no SVC dependency. */
public final class PartyVoiceChatPlugin implements VoicechatPlugin {
    private final ThreadLocal<Boolean> assigningParty = ThreadLocal.withInitial(() -> false);
    private volatile VoicechatServerApi voicechat;
    private volatile MinecraftServer currentServer;

    @Override
    public String getPluginId() { return "cwp_parties"; }

    @Override
    public void initialize(VoicechatApi api) {
        PartyVoiceBridge.install(this::sync);
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            voicechat = event.getVoicechat();
            scheduleSync();
        });
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            voicechat = null;
            currentServer = null;
        });
        registration.registerEvent(PlayerConnectedEvent.class, event -> scheduleSync());

        // The SVC group screen must not create a second, independent membership system.
        registration.registerEvent(CreateGroupEvent.class, event -> {
            if (!assigningParty.get() && event.getConnection() != null) event.cancel();
        });
        registration.registerEvent(JoinGroupEvent.class, event -> {
            if (!assigningParty.get()) event.cancel();
        });
        registration.registerEvent(LeaveGroupEvent.class, event -> {
            if (!assigningParty.get()) event.cancel();
        });
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, this::adjustVolume);
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, this::adjustVolume);
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, this::adjustVolume);
    }

    private void adjustVolume(ClientReceiveSoundEvent event) {
        short[] adjusted = PartyVoiceVolumeBridge.adjust(event.getId(), event.getRawAudio());
        if (adjusted != event.getRawAudio()) event.setRawAudio(adjusted);
    }

    private void scheduleSync() {
        MinecraftServer server = currentServer;
        if (server != null) server.execute(() -> sync(server, Parties.book(server)));
    }

    private void sync(MinecraftServer server, PartyBook parties) {
        currentServer = server;
        VoicechatServerApi api = voicechat;
        if (api == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            VoicechatConnection connection = api.getConnectionOf(player.getUUID());
            if (connection == null) continue;
            PartyBook.Party party = parties.partyOf(player.getUUID());
            UUID wanted = party == null ? null : voiceGroupId(party);
            Group current = connection.getGroup();
            UUID actual = current == null ? null : current.getId();
            if (java.util.Objects.equals(wanted, actual)) continue;

            Group group = null;
            if (wanted != null) {
                group = api.getGroups().stream().filter(existing -> wanted.equals(existing.getId())).findFirst()
                        .orElseGet(() -> api.groupBuilder().setId(wanted).setName("CWP Party")
                                .setHidden(true).setPersistent(false)
                                .setType(party.privateVoice() ? Group.Type.ISOLATED : Group.Type.OPEN).build());
            }
            assigningParty.set(true);
            try {
                if (current != null) connection.setGroup(null);
                connection.setGroup(group);
            } finally {
                assigningParty.remove();
            }
        }
    }

    static UUID voiceGroupId(PartyBook.Party party) {
        String key = "cwp:party:" + party.id() + ":" + (party.privateVoice() ? "private" : "open");
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
