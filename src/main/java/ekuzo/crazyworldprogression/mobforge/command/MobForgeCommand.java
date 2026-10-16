package ekuzo.crazyworldprogression.mobforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeSpawner;
import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.api.mobforge.EntitySpawnService;
import ekuzo.crazyworldprogression.api.mobforge.SpawnContext;
import net.minecraft.resources.Identifier;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgePurge;
import ekuzo.crazyworldprogression.mobforge.networking.MobForgeNetworking;
import ekuzo.crazyworldprogression.mobforge.script.MobScript;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public final class MobForgeCommand {
	private MobForgeCommand() {}

	public static void initialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("mobforge").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN));
		root.then(Commands.literal("reload").executes(context -> reload(context.getSource())));
		root.then(Commands.literal("purge")
				.executes(context -> purge(context.getSource(), null))
				.then(Commands.argument("template", StringArgumentType.word()).suggests(MobForgeCommand::suggestTemplates)
						.executes(context -> purge(context.getSource(), StringArgumentType.getString(context, "template")))));
		var template = Commands.literal("template");
		template.then(Commands.literal("list").executes(context -> list(context.getSource())));
		template.then(Commands.literal("info").then(Commands.argument("template", StringArgumentType.word()).suggests(MobForgeCommand::suggestTemplates)
				.executes(context -> info(context.getSource(), StringArgumentType.getString(context, "template")))));
		root.then(template);
		root.then(Commands.literal("editor").executes(context -> openEditor(context.getSource(), null))
				.then(Commands.argument("template", StringArgumentType.word()).suggests(MobForgeCommand::suggestTemplates)
						.executes(context -> openEditor(context.getSource(), StringArgumentType.getString(context, "template")))));

		var spawn = Commands.literal("spawn").then(Commands.argument("template", StringArgumentType.word()).suggests(MobForgeCommand::suggestTemplates)
				.executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "template"), context.getSource().getPosition(), 1))
				.then(Commands.argument("position", Vec3Argument.vec3())
						.executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "template"), Vec3Argument.getVec3(context, "position"), 1))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 500))
								.executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "template"), Vec3Argument.getVec3(context, "position"), IntegerArgumentType.getInteger(context, "count"))))));
		root.then(spawn);

		var script = Commands.literal("script");
		script.then(Commands.literal("list").executes(context -> listScripts(context.getSource())));
		script.then(Commands.literal("info").then(Commands.argument("script", StringArgumentType.word()).suggests(MobForgeCommand::suggestScripts)
				.executes(context -> scriptInfo(context.getSource(), StringArgumentType.getString(context, "script")))));
		script.then(Commands.literal("run").then(Commands.argument("script", StringArgumentType.word()).suggests(MobForgeCommand::suggestScripts)
				.executes(context -> runScript(context.getSource(), StringArgumentType.getString(context, "script")))));
		script.then(Commands.literal("purge").then(Commands.argument("script", StringArgumentType.word()).suggests(MobForgeCommand::suggestScripts)
				.executes(context -> purgeScript(context.getSource(), StringArgumentType.getString(context, "script")))));
		script.then(Commands.literal("editor").executes(context -> openScriptEditor(context.getSource(), null))
				.then(Commands.argument("script", StringArgumentType.word()).suggests(MobForgeCommand::suggestScripts)
						.executes(context -> openScriptEditor(context.getSource(), StringArgumentType.getString(context, "script")))));
		root.then(script);

		var ai = Commands.literal("ai").then(Commands.argument("targets", EntityArgument.entities())
				.then(Commands.literal("vanilla").executes(context -> setAi(context, MobTemplate.Ai.VANILLA)))
				.then(Commands.literal("target-position").then(Commands.argument("position", Vec3Argument.vec3())
						.executes(context -> { Vec3 p = Vec3Argument.getVec3(context, "position"); return setAi(context, new MobTemplate.Ai("target_position", true, new MobTemplate.Position(p.x, p.y, p.z), 2, null, 64, null)); })))
				.then(Commands.literal("target-player").then(Commands.argument("player", EntityArgument.player())
						.executes(context -> setAi(context, new MobTemplate.Ai("target_player", true, null, 2, EntityArgument.getPlayer(context, "player").getUUID().toString(), 64, null)))))
				.then(Commands.literal("nearest-player").executes(context -> setAi(context, new MobTemplate.Ai("nearest_player", true, null, 2, null, 64, null)))));
		root.then(ai);

		root.then(Commands.literal("attribute").then(Commands.argument("targets", EntityArgument.entities())
				.then(Commands.argument("attribute", StringArgumentType.word()).suggests((context, builder) -> suggest(MobForge.ATTRIBUTE_NAMES, builder))
						.then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
								.executes(MobForgeCommand::setAttribute)))));
		dispatcher.register(root);
	}

	private static int reload(CommandSourceStack source) {
		var templates = MobForge.TEMPLATES.reload();
		var scripts = MobForge.SCRIPTS.reload();
		source.sendSuccess(() -> Component.literal("Reloaded " + templates.loaded() + " templates and " + scripts.loaded() + " scripts (" + (templates.failed() + scripts.failed()) + " failed)."), true);
		return templates.loaded() + scripts.loaded();
	}
	private static int purge(CommandSourceStack source, String templateId) {
		if (templateId != null && MobForge.TEMPLATES.get(templateId).isEmpty()) { source.sendFailure(Component.literal("Unknown MobForge template: " + templateId)); return 0; }
		int killed = templateId == null ? MobForgePurge.all(source.getServer()) : MobForgePurge.template(source.getServer(), templateId);
		source.sendSuccess(() -> Component.literal("Purged " + killed + " MobForge entities" + (templateId == null ? ". Active scripts were not canceled." : " from template " + templateId + ". Active scripts were not canceled.")), true);
		return killed;
	}
	private static int list(CommandSourceStack source) {
		var templates = MobForge.TEMPLATES.all();
		source.sendSystemMessage(Component.literal(templates.isEmpty() ? "No MobForge templates loaded." : "MobForge templates (" + templates.size() + "): " + String.join(", ", templates.stream().map(MobTemplate::id).toList())));
		return templates.size();
	}
	private static int info(CommandSourceStack source, String id) {
		var optional = MobForge.TEMPLATES.get(id);
		if (optional.isEmpty()) { source.sendFailure(Component.literal("Unknown MobForge template: " + id)); return 0; }
		MobTemplate t = optional.get();
		source.sendSystemMessage(Component.literal(t.id() + " — " + t.entity() + (t.name() == null ? "" : " — " + t.name()) + " — AI: " + t.ai().mode()));
		return 1;
	}
	private static int spawn(CommandSourceStack source, String id, Vec3 position, int count) {
		var optional = MobForge.TEMPLATES.get(id);
		if (optional.isEmpty()) { source.sendFailure(Component.literal("Unknown MobForge template: " + id)); return 0; }
		int spawned = 0;
		try { for (int i = 0; i < count; i++) { EntitySpawnService.spawn(Identifier.fromNamespaceAndPath(CrazyWorldProgression.MOD_ID, id), SpawnContext.at(source.getLevel(), position)); spawned++; } }
		catch (RuntimeException exception) { source.sendFailure(Component.literal("Spawn failed after " + spawned + " entities: " + exception.getMessage())); return spawned; }
		int result = spawned; source.sendSuccess(() -> Component.literal("Spawned " + result + " × " + id + "."), true); return spawned;
	}
	private static int listScripts(CommandSourceStack source) {
		var scripts = MobForge.SCRIPTS.all();
		source.sendSystemMessage(Component.literal(scripts.isEmpty() ? "No MobForge scripts loaded." : "MobForge scripts (" + scripts.size() + "): " + String.join(", ", scripts.stream().map(MobScript::id).toList())));
		return scripts.size();
	}
	private static int scriptInfo(CommandSourceStack source, String id) {
		var found = MobForge.SCRIPTS.get(id);
		if (found.isEmpty()) { source.sendFailure(Component.literal("Unknown MobForge script: " + id)); return 0; }
		int spawnSteps = (int) found.get().steps().stream().filter(MobScript.SpawnStep.class::isInstance).count();
		int active = MobForge.SCRIPT_EXECUTOR.activeRuns(id);
		source.sendSystemMessage(Component.literal(id + " — " + found.get().steps().size() + " steps, " + spawnSteps + " spawn steps, " + active + " active runs"));
		return 1;
	}
	private static int runScript(CommandSourceStack source, String id) {
		var found = MobForge.SCRIPTS.get(id);
		if (found.isEmpty()) { source.sendFailure(Component.literal("Unknown MobForge script: " + id)); return 0; }
		var runId = MobForge.SCRIPT_EXECUTOR.run(found.get(), source.getLevel(), java.util.Map.of("origin", source.getPosition()), null, null);
		source.sendSuccess(() -> Component.literal("Started MobForge script " + id + " (run " + runId + ")."), true);
		return 1;
	}
	private static int purgeScript(CommandSourceStack source, String id) {
		if (!ekuzo.crazyworldprogression.mobforge.template.MobTemplateValidator.SAFE_ID.matcher(id).matches()) { source.sendFailure(Component.literal("Invalid MobForge script ID: " + id)); return 0; }
		int canceled = MobForge.SCRIPT_EXECUTOR.cancel(id);
		int killed = MobForgePurge.script(source.getServer(), id);
		source.sendSuccess(() -> Component.literal("Purged script " + id + ": canceled " + canceled + " active runs and killed " + killed + " entities."), true);
		return killed + canceled;
	}
	private static int openScriptEditor(CommandSourceStack source, String id) {
		var player = source.getPlayer(); if (player == null) { source.sendFailure(Component.literal("The graphical script editor requires a player.")); return 0; }
		MobScript script;
		if (id == null) script = new MobScript("new_script", java.util.List.of());
		else { var found = MobForge.SCRIPTS.get(id); if (found.isEmpty()) { source.sendFailure(Component.literal("Unknown MobForge script: " + id)); return 0; } script = found.get(); }
		if (!MobForgeNetworking.openScript(player, script, id != null)) { source.sendFailure(Component.literal("The MobForge client mod is required to use the script editor.")); return 0; }
		return 1;
	}
	private static int openEditor(CommandSourceStack source, String id) {
		var player = source.getPlayer(); if (player == null) { source.sendFailure(Component.literal("The graphical editor requires a player.")); return 0; }
		MobTemplate template;
		if (id == null) template = new MobTemplate("new_template", "minecraft:zombie", null, java.util.Map.of(), java.util.Map.of(), null, null, java.util.List.of(), null);
		else { var found = MobForge.TEMPLATES.get(id); if (found.isEmpty()) { source.sendFailure(Component.literal("Unknown MobForge template: " + id)); return 0; } template = found.get(); }
		if (!MobForgeNetworking.open(player, template, id != null)) { source.sendFailure(Component.literal("The MobForge client mod is required to use the editor.")); return 0; }
		return 1;
	}
	private static int setAi(CommandContext<CommandSourceStack> context, MobTemplate.Ai ai) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets"); int changed = 0;
		for (Entity entity : targets) if (entity.entityTags().contains(MobForgeEntityData.MARKER)) { MobForgeEntityData.setAi(entity, ai); changed++; }
		int result = changed; context.getSource().sendSuccess(() -> Component.literal("Updated AI for " + result + " MobForge mobs."), true); return changed;
	}
	private static int setAttribute(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		String name = StringArgumentType.getString(context, "attribute"); double value = DoubleArgumentType.getDouble(context, "value"); int changed = 0;
		for (Entity entity : EntityArgument.getEntities(context, "targets")) if (MobForgeSpawner.setAttribute(entity, name, value)) changed++;
		int result = changed; context.getSource().sendSuccess(() -> Component.literal("Updated " + name + " for " + result + " entities."), true); return changed;
	}
	private static CompletableFuture<Suggestions> suggestTemplates(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) { return suggest(MobForge.TEMPLATES.all().stream().map(MobTemplate::id).toList(), builder); }
	private static CompletableFuture<Suggestions> suggestScripts(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) { return suggest(MobForge.SCRIPTS.all().stream().map(MobScript::id).toList(), builder); }
	private static CompletableFuture<Suggestions> suggest(Collection<String> values, SuggestionsBuilder builder) { String remaining = builder.getRemainingLowerCase(); values.stream().filter(value -> value.startsWith(remaining)).forEach(builder::suggest); return builder.buildFuture(); }
}
