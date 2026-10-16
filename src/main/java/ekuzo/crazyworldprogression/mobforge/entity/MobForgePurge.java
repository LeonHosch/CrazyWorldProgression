package ekuzo.crazyworldprogression.mobforge.entity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class MobForgePurge {
	private MobForgePurge() { }

	public static int all(MinecraftServer server) {
		return purge(server, entity -> entity.entityTags().contains(MobForgeEntityData.MARKER));
	}

	public static int template(MinecraftServer server, String templateId) {
		return purge(server, entity -> entity.entityTags().contains(MobForgeEntityData.MARKER)
				&& MobForgeEntityData.template(entity).filter(templateId::equals).isPresent());
	}

	public static int script(MinecraftServer server, String scriptId) {
		return purge(server, entity -> entity.entityTags().contains(MobForgeEntityData.MARKER)
				&& MobForgeEntityData.script(entity).filter(scriptId::equals).isPresent());
	}

	private static int purge(MinecraftServer server, Predicate<Entity> predicate) {
		int killed = 0;
		for (ServerLevel level : server.getAllLevels()) {
			List<Entity> matches = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) if (predicate.test(entity)) matches.add(entity);
			for (Entity entity : matches) {
				if (!entity.isRemoved()) { entity.kill(level); killed++; }
			}
		}
		return killed;
	}
}
