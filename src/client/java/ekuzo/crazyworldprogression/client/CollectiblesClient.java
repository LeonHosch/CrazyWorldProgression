package ekuzo.crazyworldprogression.client;

import com.mojang.blaze3d.platform.InputConstants;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.collectibles.Collectibles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** The mount key (H by default): summons the selected mount, or sends it away while riding it. */
public final class CollectiblesClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(CrazyWorldProgression.id("collectibles"));
    private static KeyMapping mountKey;

    private CollectiblesClient() { }

    public static void initialize() {
        mountKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.crazy-world-progression.mount",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (mountKey.consumeClick()) {
                if (client.player != null && client.gui.screen() == null) send("summon", "");
            }
        });
    }

    /** The key's current name, for hints such as "Press H to ride". */
    static Component keyName() { return mountKey == null ? Component.literal("H") : mountKey.getTranslatedKeyMessage(); }

    static void send(String action, String id) {
        if (ClientPlayNetworking.canSend(Collectibles.ActionPayload.TYPE))
            ekuzo.crazyworldprogression.client.CwpClientPayloads.send(new Collectibles.ActionPayload(action, id));
    }
}
