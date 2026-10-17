package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.admin.AdminPanelNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client receiver and navigation sender for CWP's server-described admin panel. */
public final class AdminPanelClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("crazy-world-progression", "admin"));
    private static KeyMapping openKey;
    private static boolean ignoreNextClosedPanelSnapshot;
    // Prevent construction of the static client bridge.
    private AdminPanelClient() {
    }

    // Register the one snapshot receiver used by CWP and every dependent admin module.
    public static void initialize() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.crazy-world-progression.open_admin", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_A, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null && client.gui.screen() == null
                        && modifiersDown(client)) {
                    ClientPlayNetworking.send(new AdminPanelNetworking.RequestPanelPayload("@last", ""));
                }
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(AdminPanelNetworking.PanelSnapshotPayload.TYPE,
                (payload, context) -> show(context.client(), AdminPanelNetworking.decodeSnapshot(payload.json())));
    }

    private static boolean modifiersDown(Minecraft client) {
        long window = client.getWindow().handle();
        boolean control = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        return control && shift;
    }

    // Open a new screen or update the currently open panel without losing its parent.
    private static void show(Minecraft client, AdminPanelNetworking.PanelSnapshot snapshot) {
        if (client.gui.screen() instanceof AdminPanelScreen screen) {
            screen.updateSnapshot(snapshot);
        } else {
            if (ignoreNextClosedPanelSnapshot) {
                ignoreNextClosedPanelSnapshot = false;
                return;
            }
            client.gui.setScreen(new AdminPanelScreen(client.gui.screen(), snapshot));
        }
    }

    static void ignoreNextClosedPanelSnapshot() {
        ignoreNextClosedPanelSnapshot = true;
    }
}
