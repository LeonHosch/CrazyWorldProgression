package ekuzo.crazyworldprogression.mobforge.networking;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateYaml;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.script.MobScriptYaml;
import ekuzo.crazyworldprogression.portals.PocketEditorService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class MobForgeNetworking {
	private static final MobTemplateYaml YAML = new MobTemplateYaml();
	private static final MobScriptYaml SCRIPT_YAML = new MobScriptYaml();
	private MobForgeNetworking() {}

	public static void initialize() {
		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.OpenScriptEditor.TYPE, EditorPayloads.OpenScriptEditor.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.SaveScript.TYPE, EditorPayloads.SaveScript.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.DeleteScript.TYPE, EditorPayloads.DeleteScript.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.SaveResult.TYPE, EditorPayloads.SaveResult.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.SaveScript.TYPE, (payload, context) -> context.server().execute(() -> saveScript(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.DeleteScript.TYPE, (payload, context) -> context.server().execute(() -> deleteScript(payload, context.player())));
	}

	public static boolean openScript(ServerPlayer player, MobScript script) {
		return openScript(player, script, true);
	}
	public static boolean openScript(ServerPlayer player, MobScript script, boolean existing) {
		if (!ServerPlayNetworking.canSend(player, EditorPayloads.OpenScriptEditor.TYPE)) return false;
		String templates = String.join("\n", MobForge.TEMPLATES.all().stream().map(MobTemplate::id).toList());
		java.util.Set<String> ids = new java.util.TreeSet<>();
		MobForge.mobs().all().keySet().forEach(id -> ids.add(id.toString()));
		MobForge.TEMPLATES.all().forEach(template -> ids.add(MobForge.id(template.id()).toString()));
		net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> ids.add(id.toString()));
		String abilities = String.join("\n", MobForge.abilities().all().keySet().stream()
				.map(Object::toString).sorted().toList());
		ServerPlayNetworking.send(player, new EditorPayloads.OpenScriptEditor(SCRIPT_YAML.encode(script),
				templates, String.join("\n", ids), abilities, existing)); return true;
	}

	private static boolean authorized(ServerPlayer player) { return player.permissions().hasPermission(Permissions.COMMANDS_ADMIN); }
	private static void saveScript(EditorPayloads.SaveScript payload, ServerPlayer player) {
		if (!authorized(player)) { player.sendSystemMessage(Component.literal("You do not have permission to edit MobForge scripts.")); return; }
		try { MobScript script = SCRIPT_YAML.decode(payload.yaml());
			if (!script.id().equals(payload.originId()) && MobForge.SCRIPTS.get(script.id()).isPresent()) {
				throw new IllegalArgumentException("Script ID already exists: " + script.id());
			}
			MobForge.SCRIPTS.save(script); result(player, "script", true, "Saved MobForge script " + script.id() + "."); }
		catch (Exception exception) { result(player, "script", false, "MobForge script save failed: " + exception.getMessage()); }
	}
	private static void result(ServerPlayer player, String kind, boolean success, String message) {
		player.sendSystemMessage(Component.literal(message));
		if (ServerPlayNetworking.canSend(player, EditorPayloads.SaveResult.TYPE)) {
			ServerPlayNetworking.send(player, new EditorPayloads.SaveResult(kind, success, message));
		}
	}
	private static void deleteScript(EditorPayloads.DeleteScript payload, ServerPlayer player) {
		if (!authorized(player)) { player.sendSystemMessage(Component.literal("You do not have permission to edit MobForge scripts.")); return; }
		try {
			MobForge.SCRIPT_EXECUTOR.cancel(payload.id());
			boolean deleted = MobForge.SCRIPTS.delete(payload.id());
			if (deleted) PocketEditorService.detachScriptEverywhere(player.level().getServer(), payload.id());
			player.sendSystemMessage(Component.literal(deleted ? "Deleted MobForge script " + payload.id() + "." : "Script was not found."));
		} catch (Exception exception) { player.sendSystemMessage(Component.literal("MobForge script delete failed: " + exception.getMessage())); }
	}
}
