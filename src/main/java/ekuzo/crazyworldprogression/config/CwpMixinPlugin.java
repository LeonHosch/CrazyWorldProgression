package ekuzo.crazyworldprogression.config;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Leaves out the mixins of CWP parts that are switched off (see {@link CwpModule#mixins()}). */
public final class CwpMixinPlugin implements IMixinConfigPlugin {
    private final Map<String, CwpModule> owners = new HashMap<>();
    private String mixinPackage;
    /** Every mixin of this config belongs to one part (MobForge's own mixin config). */
    private CwpModule wholeConfig;

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage + ".";
        if (mixinPackage.endsWith(".mobforge.mixin")) wholeConfig = CwpModule.MOBFORGE;
        for (CwpModule module : CwpModule.values())
            for (String mixin : module.mixins()) owners.put(mixin, module);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        CwpModule owner = wholeConfig != null ? wholeConfig : owners.get(mixinClassName.startsWith(mixinPackage)
                ? mixinClassName.substring(mixinPackage.length()) : mixinClassName);
        return owner == null || CwpConfig.get().enabled(owner);
    }

    @Override public String getRefMapperConfig() { return null; }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }

    @Override public List<String> getMixins() { return null; }

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
