package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.respawn.RespawnFeedback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * The client side of {@link RespawnFeedback}: dying respawns at once instead of opening the death screen
 * (GuiInstantRespawnMixin), and coming back plays CWP's Return by Death sound to the returning player alone. After a
 * death it plays as soon as the respawned world is shown (ClientRespawnSoundMixin notices the respawn); after a raid
 * self-revive when the server says so. Both are on by default; a content mod can change them in its client
 * initializer ({@link #setReturnSound}, {@link #setInstantRespawn}). Raid downs never open the death screen, so their spectator state is not affected.
 */
public final class RespawnFeedbackClient {
    private static volatile SoundEvent returnSound = SoundEvent.createVariableRangeEvent(
            ekuzo.crazyworldprogression.CrazyWorldProgression.id("return_by_death"));
    private static volatile boolean instantRespawn = true;
    /** A respawn after death arrived; the sound plays once the world is shown again (not under the loading screen). */
    private static boolean pendingReturn;
    private static boolean dead;

    private RespawnFeedbackClient() { }

    /** The sound (an ID from the mod's sounds.json) played to the returning player; null for none. */
    public static void setReturnSound(Identifier sound) {
        returnSound = sound == null ? null : SoundEvent.createVariableRangeEvent(sound);
    }

    /** True: dying respawns at once instead of showing the death screen (not in hardcore worlds). */
    public static void setInstantRespawn(boolean value) { instantRespawn = value; }

    static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(RespawnFeedback.ReturnedPayload.TYPE, (payload, context) -> playReturn());
        // Checked at both ends of each tick, so it starts with the first tick that shows the world again.
        ClientTickEvents.START_CLIENT_TICK.register(RespawnFeedbackClient::playIfShown);
        ClientTickEvents.END_CLIENT_TICK.register(RespawnFeedbackClient::playIfShown);
    }

    /** The local player died (ClientRespawnSoundMixin, from the kill packet). */
    public static void died() { dead = true; }

    /** A respawn arrived (ClientRespawnSoundMixin); after a death the sound is due. Leaving the End is not one. */
    public static void respawned() {
        if (!dead) return;
        dead = false;
        pendingReturn = true;
    }

    private static void playIfShown(Minecraft client) {
        if (!pendingReturn || client.player == null || client.level == null
                || client.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) return;
        pendingReturn = false;
        playReturn();
    }

    public static void playReturn() {
        SoundEvent sound = returnSound;
        if (sound != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F));
    }

    /** Called before a screen opens: true when it is the death screen and the player was respawned instead. */
    public static boolean respawnInsteadOf(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        if (!instantRespawn || !(screen instanceof DeathScreen) || client.player == null || client.level == null
                || client.level.getLevelData().isHardcore()) return false;
        client.player.respawn();
        return true;
    }
}
