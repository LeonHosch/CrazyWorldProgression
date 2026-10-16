package ekuzo.crazyworldprogression.mobforge.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class VanillaAiController implements MobAiController {
	@Override public String id() { return "vanilla"; }
	@Override public void tick(ServerLevel level, Mob mob) { }
}
