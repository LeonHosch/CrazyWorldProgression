package ekuzo.crazyworldprogression.progression.equipment;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Effects shown as stacks on the left side of the screen (with Ascension, Downfall and Blood Lust) instead of the
 * vanilla effect icons: the effect's level is the stack count. A content mod registers its effect in its main
 * initializer; script auras then give it without the vanilla icon and particles.
 */
public final class StackedEffects {
    private static final Set<Identifier> EFFECTS = new CopyOnWriteArraySet<>();

    private StackedEffects() { }

    public static void register(Identifier effectId) { EFFECTS.add(effectId); }

    public static boolean contains(Identifier effectId) { return EFFECTS.contains(effectId); }

    public static boolean contains(Holder<MobEffect> effect) {
        return effect.unwrapKey().map(key -> EFFECTS.contains(key.identifier())).orElse(false);
    }
}
