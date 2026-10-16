package ekuzo.crazyworldprogression.mobforge;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.api.mobforge.AbilityRegistry;
import ekuzo.crazyworldprogression.api.mobforge.BehaviorRegistry;
import ekuzo.crazyworldprogression.api.mobforge.MobRegistry;
import ekuzo.crazyworldprogression.api.mobforge.MobForgeEvents;
import ekuzo.crazyworldprogression.api.mobforge.RegisteredMobHandle;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.resources.Identifier;

import ekuzo.crazyworldprogression.mobforge.ai.MobAiRegistry;
import ekuzo.crazyworldprogression.mobforge.command.MobForgeCommand;
import ekuzo.crazyworldprogression.mobforge.networking.MobForgeNetworking;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateRegistry;
import ekuzo.crazyworldprogression.mobforge.template.MobTemplateValidator;
import ekuzo.crazyworldprogression.mobforge.script.MobScriptExecutor;
import ekuzo.crazyworldprogression.mobforge.script.MobScriptRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MobForge {
	public static final String MOD_ID = CrazyWorldProgression.MOD_ID;

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final MobTemplateRegistry TEMPLATES = new MobTemplateRegistry();
	public static final MobScriptRegistry SCRIPTS = new MobScriptRegistry();
	public static final MobScriptExecutor SCRIPT_EXECUTOR = new MobScriptExecutor();
	private static final MobRegistry MOBS = new MobRegistry();
	private static final AbilityRegistry ABILITIES = new AbilityRegistry();
	private static final BehaviorRegistry BEHAVIORS = new BehaviorRegistry();
	public static final java.util.Set<String> ATTRIBUTE_NAMES = MobTemplateValidator.ATTRIBUTES;

	private MobForge() {}
	public static MobRegistry mobs() { return MOBS; }
	public static AbilityRegistry abilities() { return ABILITIES; }
	public static BehaviorRegistry behaviors() { return BEHAVIORS; }

	public static void initialize() {
		MobAiRegistry.initialize();
		MobForgeCommand.initialize();
		MobForgeNetworking.initialize();
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, amount, damageTaken, blocked) -> {
			if (damageTaken > 0 && MobForgeEntityData.definition(entity).flatMap(MOBS::get).isPresent())
				MobForgeEvents.damaged(new RegisteredMobHandle(entity), damageTaken);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity.level() instanceof net.minecraft.server.level.ServerLevel level) SCRIPT_EXECUTOR.entityDied(level, entity.getUUID());
			if (MobForgeEntityData.definition(entity).flatMap(MOBS::get).isPresent())
				MobForgeEvents.killed(new RegisteredMobHandle(entity), source.getEntity());
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TEMPLATES.reload();
			SCRIPTS.reload();
		});
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			MOBS.freeze(ABILITIES, BEHAVIORS);
			ABILITIES.freeze();
			BEHAVIORS.freeze();
		});
		ServerTickEvents.END_SERVER_TICK.register(SCRIPT_EXECUTOR::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SCRIPT_EXECUTOR.clear());
		LOGGER.info("MobForge initialized.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
