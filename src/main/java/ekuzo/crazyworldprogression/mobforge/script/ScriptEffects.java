package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.equipment.StackedEffects;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Plain marker effects for script mechanics, shown as stacks on the left side of the screen: Charge (for charging
 * platform stacks, see stack_effect) and Overcharge (for their on_max effect). They do nothing by themselves.
 */
public final class ScriptEffects {
    public static final Holder<MobEffect> CHARGE = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
            CrazyWorldProgression.id("charge"), new Marker(MobEffectCategory.BENEFICIAL, 0x4FC3F7));
    public static final Holder<MobEffect> OVERCHARGE = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
            CrazyWorldProgression.id("overcharge"), new Marker(MobEffectCategory.HARMFUL, 0xFF8A1F));

    private ScriptEffects() { }

    public static void initialize() {
        StackedEffects.register(CrazyWorldProgression.id("charge"));
        StackedEffects.register(CrazyWorldProgression.id("overcharge"));
    }

    private static final class Marker extends MobEffect {
        Marker(MobEffectCategory category, int color) { super(category, color); }
    }
}
