package ekuzo.crazyworldprogression;

import ekuzo.crazyworldprogression.command.BalanceCommands;
import ekuzo.crazyworldprogression.command.KingCommands;
import ekuzo.crazyworldprogression.command.KingdomPointsCommands;
import ekuzo.crazyworldprogression.command.PersonalCurrencyCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.resources.Identifier;

import ekuzo.crazyworldprogression.progression.PlayerModifications;
import ekuzo.crazyworldprogression.veil.VeilManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CrazyWorldProgression implements ModInitializer {
	public static final String MOD_ID = "crazy-world-progression";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Initialize every server-side system and command owned by the mod.
	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Initializing Crazy World Progression");

		// Gameplay systems listen for server events, while commands expose progression state to players and admins.
		VeilManager.initialize();
		PlayerModifications.initialize();

		// Register all server commands through one callback while keeping startup ownership in this entrypoint.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			BalanceCommands.register(dispatcher);
			PersonalCurrencyCommands.register(dispatcher);
			KingdomPointsCommands.register(dispatcher);
			KingCommands.register(dispatcher);
		});
	}

	// Create an identifier inside this mod's namespace.
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
