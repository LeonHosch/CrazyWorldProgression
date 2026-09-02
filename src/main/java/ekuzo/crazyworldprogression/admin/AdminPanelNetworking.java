package ekuzo.crazyworldprogression.admin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Comparator;
import java.util.Map;

/** Secure request/action transport for CWP's generic, server-authoritative admin panel. */
public final class AdminPanelNetworking {
    private static final Gson GSON = new Gson();
    private static final String EMPTY_SELECTION = "";

    // Prevent construction of the static network bridge.
    private AdminPanelNetworking() {
    }

    // Register all admin-panel payload codecs during common initialization.
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.serverboundPlay().register(RequestPanelPayload.TYPE, RequestPanelPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ExecuteActionPayload.TYPE, ExecuteActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PanelSnapshotPayload.TYPE, PanelSnapshotPayload.CODEC);
    }

    // Register server receivers and recheck game-master permission for every client request.
    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(RequestPanelPayload.TYPE, (payload, context) -> {
            if (isAdministrator(context.player())) {
                send(context.player(), payload.moduleId(), payload.pageId(), "", false);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ExecuteActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!isAdministrator(player)) return;
            Identifier moduleId = Identifier.tryParse(payload.moduleId());
            AdminPanelRegistry.AdminModule module = moduleId == null ? null : AdminPanelRegistry.module(moduleId);
            if (module == null) {
                send(player, EMPTY_SELECTION, EMPTY_SELECTION, "Unknown admin module", true);
                return;
            }
            AdminPanelRegistry.ActionResult result;
            try {
                Map<String, String> values = GSON.fromJson(payload.valuesJson(),
                        new TypeToken<Map<String, String>>() { }.getType());
                result = module.actions().execute(player, payload.pageId(), payload.actionId(),
                        values == null ? Map.of() : Map.copyOf(values));
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                result = AdminPanelRegistry.ActionResult.failure(message);
            }
            send(player, payload.moduleId(), payload.pageId(), result.message(), result.error());
        });
    }

    // Open the module dashboard for an authorized player.
    public static boolean open(ServerPlayer player) {
        if (!isAdministrator(player)) return false;
        send(player, EMPTY_SELECTION, EMPTY_SELECTION, "", false);
        return true;
    }

    // Convert a serialized snapshot back into the stable public wire model on the client.
    public static PanelSnapshot decodeSnapshot(String json) {
        return GSON.fromJson(json, PanelSnapshot.class);
    }

    // Serialize current form values for one generic action payload.
    public static String encodeValues(Map<String, String> values) {
        return GSON.toJson(values);
    }

    // Build and send either the module dashboard or one current module page.
    private static void send(ServerPlayer player, String requestedModule, String requestedPage,
                             String notice, boolean error) {
        List<ModuleSnapshot> modules = AdminPanelRegistry.modules().stream()
                .sorted(Comparator.comparing(module -> module.id().toString()))
                .map(module -> new ModuleSnapshot(module.id().toString(), module.displayName(),
                        module.description(), module.icon())).toList();
        AdminPanelRegistry.AdminPage page = null;
        String selectedModule = EMPTY_SELECTION;
        if (requestedModule != null && !requestedModule.isBlank()) {
            Identifier id = Identifier.tryParse(requestedModule);
            AdminPanelRegistry.AdminModule module = id == null ? null : AdminPanelRegistry.module(id);
            if (module != null) {
                selectedModule = module.id().toString();
                String pageId = requestedPage == null || requestedPage.isBlank()
                        ? module.defaultPage() : requestedPage;
                page = module.pages().create(player, pageId);
            }
        }
        ServerPlayNetworking.send(player, new PanelSnapshotPayload(GSON.toJson(
                new PanelSnapshot(modules, selectedModule, page, notice, error))));
    }

    // Check the same game-master permission used by /cwp before exposing state or executing controls.
    private static boolean isAdministrator(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
    }

    /** Lightweight top-level module card sent to the dashboard. */
    public record ModuleSnapshot(String id, String displayName, String description, String icon) {
    }

    /** Complete screen state, refreshed after navigation and every action. */
    public record PanelSnapshot(List<ModuleSnapshot> modules, String selectedModule,
                                AdminPanelRegistry.AdminPage page, String notice, boolean error) {
    }

    /** Client request for the dashboard or a particular module page. */
    public record RequestPanelPayload(String moduleId, String pageId) implements CustomPacketPayload {
        public static final Type<RequestPanelPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("request_admin_panel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestPanelPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestPanelPayload::moduleId,
                ByteBufCodecs.STRING_UTF8, RequestPanelPayload::pageId,
                RequestPanelPayload::new);

        // Return the protocol identifier for this request.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client request to execute one action advertised by the currently visible module. */
    public record ExecuteActionPayload(String moduleId, String pageId, String actionId,
                                       String valuesJson) implements CustomPacketPayload {
        public static final Type<ExecuteActionPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("execute_admin_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ExecuteActionPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ExecuteActionPayload::moduleId,
                ByteBufCodecs.STRING_UTF8, ExecuteActionPayload::pageId,
                ByteBufCodecs.STRING_UTF8, ExecuteActionPayload::actionId,
                ByteBufCodecs.stringUtf8(65_536), ExecuteActionPayload::valuesJson,
                ExecuteActionPayload::new);

        // Return the protocol identifier for this action request.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server response used to open or refresh the generic admin screen. */
    public record PanelSnapshotPayload(String json) implements CustomPacketPayload {
        public static final Type<PanelSnapshotPayload> TYPE =
                new Type<>(CrazyWorldProgression.id("admin_panel_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PanelSnapshotPayload> CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(1_048_576),
                        PanelSnapshotPayload::json, PanelSnapshotPayload::new);

        // Return the protocol identifier for this snapshot.
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
