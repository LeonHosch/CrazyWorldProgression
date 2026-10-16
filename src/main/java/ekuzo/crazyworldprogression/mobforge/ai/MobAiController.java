package ekuzo.crazyworldprogression.mobforge.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public interface MobAiController {
	String id();
	void tick(ServerLevel level, Mob mob);
}
