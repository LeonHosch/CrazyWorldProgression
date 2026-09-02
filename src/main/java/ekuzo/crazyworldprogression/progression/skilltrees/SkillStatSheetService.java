package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.StatSnapshot;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.StatValueFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Converts authoritative CWP unlock totals and live player attributes into stat-sheet rows. */
public final class SkillStatSheetService {
    // Prevent instantiation of the server-side stat-sheet calculator.
    private SkillStatSheetService() {
    }

    // Build either the global-only or complete personal stat sheet for the viewing player.
    public static List<StatSnapshot> create(ServerPlayer player, SkillTreeDefinition.SkillTreeType sheetType) {
        MinecraftServer server = player.level().getServer();
        Map<String, Double> globalTotals = SkillStatRegistry.getTotals(
                server, player.getUUID(), SkillTreeDefinition.SkillTreeType.GLOBAL);
        Map<String, Double> personalTotals = SkillStatRegistry.getTotals(
                server, player.getUUID(), SkillTreeDefinition.SkillTreeType.PERSONAL);
        Set<String> usedFunctions = usedFunctions(sheetType);
        List<StatSnapshot> rows = new ArrayList<>();
        for (SkillStatRegistry.StatDefinition definition : SkillStatRegistry.definitions()) {
            if (!usedFunctions.contains(definition.key())) continue;
            double global = globalTotals.getOrDefault(definition.key(), 0.0);
            double personal = sheetType == SkillTreeDefinition.SkillTreeType.PERSONAL
                    ? personalTotals.getOrDefault(definition.key(), 0.0)
                    : 0.0;
            rows.add(definition.attribute() == null
                    ? nonAttributeSnapshot(definition, global, personal)
                    : attributeSnapshot(player, definition, global, personal));
        }
        return List.copyOf(rows);
    }

    // Collect registered stat function names referenced by any tree on the requested menu side.
    private static Set<String> usedFunctions(SkillTreeDefinition.SkillTreeType type) {
        return SkillTreeManager.getSkillTrees().stream()
                .filter(tree -> tree.type() == type)
                .flatMap(tree -> tree.skills().stream())
                .flatMap(skill -> skill.stats().stream())
                .map(SkillStatRegistry::parse)
                .map(SkillStatRegistry.ParsedStat::functionName)
                .collect(Collectors.toUnmodifiableSet());
    }

    // Calculate one attribute row from vanilla base, declared baselines, and scope-specific CWP bonuses.
    private static StatSnapshot attributeSnapshot(ServerPlayer player, SkillStatRegistry.StatDefinition definition,
                                                  double globalRaw, double personalRaw) {
        AttributeInstance instance = player.getAttribute(definition.attribute());
        if (instance == null) {
            return new StatSnapshot(definition.translationKey(), definition.format(), 0.0, 0.0, 0.0, 0.0);
        }
        List<BaselineStatRegistry.BaselineModifier> baseline = BaselineStatRegistry.modifiers(definition.key());
        double base = instance.getBaseValue();
        double starting = calculateAttribute(instance, base, baseline, definition.operation(), 0.0);
        double withGlobal = calculateAttribute(instance, base, baseline,
                definition.operation(), globalRaw * definition.scale());
        double withAll = calculateAttribute(instance, base, baseline,
                definition.operation(), (globalRaw + personalRaw) * definition.scale());
        double divisor = definition.format() == StatValueFormat.PERCENT
                ? Math.abs(instance.getBaseValue()) > 1.0E-9 ? instance.getBaseValue() : 1.0
                : 1.0;
        return new StatSnapshot(
                definition.translationKey(),
                definition.format(),
                starting / divisor,
                (withGlobal - starting) / divisor,
                (withAll - withGlobal) / divisor,
                withAll / divisor
        );
    }

    // Calculate and sanitize one declared progression value without temporary equipment or effect modifiers.
    private static double calculateAttribute(AttributeInstance instance, double base,
                                             List<BaselineStatRegistry.BaselineModifier> baseline,
                                             AttributeModifier.Operation syntheticOperation, double syntheticAmount) {
        return instance.getAttribute().value().sanitizeValue(
                BaselineStatRegistry.calculate(base, baseline, syntheticOperation, syntheticAmount));
    }

    // Calculate one non-attribute row using its declared starting value, baselines, and skill operation.
    private static StatSnapshot nonAttributeSnapshot(SkillStatRegistry.StatDefinition definition,
                                                     double globalRaw, double personalRaw) {
        List<BaselineStatRegistry.BaselineModifier> baseline = BaselineStatRegistry.modifiers(definition.key());
        double starting = nonAttributeValue(definition,
                BaselineStatRegistry.calculate(definition.startingValue(), baseline, null, 0.0));
        double withGlobal = nonAttributeValue(definition,
                BaselineStatRegistry.calculate(definition.startingValue(), baseline,
                        definition.operation(), globalRaw * definition.scale()));
        double withAll = nonAttributeValue(definition,
                BaselineStatRegistry.calculate(definition.startingValue(), baseline,
                        definition.operation(), (globalRaw + personalRaw) * definition.scale()));
        return new StatSnapshot(definition.translationKey(), definition.format(), starting,
                withGlobal - starting, withAll - withGlobal, withAll);
    }

    // Clamp framework stats declared non-negative while leaving signed custom numbers untouched.
    private static double nonAttributeValue(SkillStatRegistry.StatDefinition definition, double value) {
        return definition.nonNegative() ? Math.max(0.0, value) : value;
    }
}
