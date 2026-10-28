package ekuzo.crazyworldprogression.mobforge;

import ekuzo.crazyworldprogression.api.mobforge.MobAbilities;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Lets mobs use their abilities ({@link MobAbilities#of}) on their own: while a mob fights a living target, it tries
 * one of its abilities every {@link #INTERVAL} ticks, at most one cast per {@link #GAP} ticks. Each ability's own
 * cooldown and {@code canCast} decide the rest. Template mobs with combat switched off never cast.
 */
final class MobAbilityRuntime {
    static final int INTERVAL = 10;
    static final int GAP = 40;
    private static final Map<Entity, Long> LAST_CAST = new WeakHashMap<>();

    private MobAbilityRuntime() { }

    static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> server.getAllLevels().forEach(MobAbilityRuntime::tick));
    }

    private static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % INTERVAL != 0) return;
        List<Mob> casters = new ArrayList<>();
        for (Entity entity : level.getAllEntities())
            if (entity instanceof Mob mob && mob.isAlive() && !mob.isNoAi() && mob.getTarget() != null) casters.add(mob);
        for (Mob mob : casters) {
            LivingEntity target = mob.getTarget();
            if (target == null || !target.isAlive() || target.level() != level) continue;
            if (MobForgeEntityData.template(mob).isPresent() && !MobForgeEntityData.combat(mob)) continue;
            Long last = LAST_CAST.get(mob);
            if (last != null && now - last < GAP) continue;
            Set<Identifier> abilities = MobAbilities.of(mob);
            if (abilities.isEmpty()) continue;
            List<Identifier> order = new ArrayList<>(abilities);
            Collections.shuffle(order);
            for (Identifier ability : order) {
                try {
                    if (MobAbilities.cast(MobAbilities.handle(mob), ability, target)) {
                        LAST_CAST.put(mob, now);
                        break;
                    }
                } catch (RuntimeException exception) {
                    MobForge.LOGGER.warn("Mob {} failed to cast {}: {}", mob.getType(), ability, exception.toString());
                    LAST_CAST.put(mob, now);
                    break;
                }
            }
        }
    }
}
