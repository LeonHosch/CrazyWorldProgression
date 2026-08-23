package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.kingdom.KingdomProgressionService;
import ekuzo.crazyworldprogression.progression.player.PlayerProgressionService;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkillStatRegistry {
    private static final Pattern EXPRESSION = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*)\\((-?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\)$");
    private static final Map<String, StatTranslation> TRANSLATIONS = new LinkedHashMap<>();

    // Prevent this static stat registry from being instantiated.
    private SkillStatRegistry() {
    }

    // Register built-in readable stats and restore their effects after joins and respawns.
    public static void initialize() {
        registerAttribute("addHealth", Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_VALUE, 1.0);
        registerAttribute("addMiningSpeed", Attributes.BLOCK_BREAK_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, 0.01);
        registerAttribute("addMovementSpeed", Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, 0.01);

        ServerPlayerEvents.JOIN.register(SkillStatRegistry::refresh);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refresh(newPlayer));
    }

    // Register one YAML function name and the code that applies its cumulative value.
    public static void register(String functionName, StatTranslation translation) {
        String key = functionName.toLowerCase(Locale.ROOT);
        if (TRANSLATIONS.putIfAbsent(key, translation) != null) {
            throw new IllegalArgumentException("Duplicate skill stat translation: " + functionName);
        }
    }

    // Parse one readable YAML expression into a registered function and numeric argument.
    public static ParsedStat parse(String expression) {
        Matcher matcher = EXPRESSION.matcher(expression.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid skill stat '" + expression + "'; expected function(number)");
        }
        String functionName = matcher.group(1);
        StatTranslation translation = TRANSLATIONS.get(functionName.toLowerCase(Locale.ROOT));
        if (translation == null) {
            throw new IllegalArgumentException("Unknown skill stat function '" + functionName + "'");
        }
        double value = Double.parseDouble(matcher.group(2));
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Skill stat value must be finite: " + expression);
        }
        return new ParsedStat(functionName.toLowerCase(Locale.ROOT), value, translation);
    }

    // Recalculate every registered skill modifier for one player from persisted unlocks.
    public static void refresh(ServerPlayer player) {
        Map<String, Double> totals = new LinkedHashMap<>();
        TRANSLATIONS.keySet().forEach(key -> totals.put(key, 0.0));
        Set<String> globalUnlocks = KingdomProgressionService.getUnlockedTechnologies(player.level().getServer());
        Set<String> personalUnlocks = PlayerProgressionService.getUnlockedSkills(player.level().getServer(), player.getUUID());

        for (SkillTreeDefinition tree : SkillTreeManager.getSkillTrees()) {
            Set<String> unlocks = tree.type() == SkillTreeDefinition.SkillTreeType.GLOBAL
                    ? globalUnlocks
                    : personalUnlocks;
            for (SkillTreeDefinition.SkillNode skill : tree.skills()) {
                if (!unlocks.contains(tree.persistedKey(skill))) {
                    continue;
                }
                for (String expression : skill.stats()) {
                    ParsedStat stat = parse(expression);
                    totals.merge(stat.functionName(), stat.value(), Double::sum);
                }
            }
        }

        totals.forEach((name, value) -> TRANSLATIONS.get(name).apply(player, value));
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    // Recalculate global skill modifiers for every currently connected player.
    public static void refreshAll(Iterable<ServerPlayer> players) {
        players.forEach(SkillStatRegistry::refresh);
    }

    // Register an attribute-backed stat with a consistent percentage or flat-value scale.
    private static void registerAttribute(
            String functionName,
            Holder<Attribute> attribute,
            AttributeModifier.Operation operation,
            double scale
    ) {
        Identifier modifierId = CrazyWorldProgression.id("skill_" + functionName.toLowerCase(Locale.ROOT));
        register(functionName, (player, value) -> {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                return;
            }
            instance.removeModifier(modifierId);
            double scaledValue = value * scale;
            if (scaledValue != 0.0) {
                instance.addTransientModifier(new AttributeModifier(modifierId, scaledValue, operation));
            }
        });
    }

    @FunctionalInterface
    public interface StatTranslation {
        // Apply the cumulative raw YAML value for this stat to one player.
        void apply(ServerPlayer player, double value);
    }

    public record ParsedStat(String functionName, double value, StatTranslation translation) {
    }
}
