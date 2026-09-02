package ekuzo.crazyworldprogression.progression.skilltrees;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.progression.ProgressionService;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.StatValueFormat;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkillStatRegistry {
    private static final Pattern EXPRESSION = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*)\\((-?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\)$");
    private static final Map<String, StatTranslation> TRANSLATIONS = new LinkedHashMap<>();
    private static final Map<String, StatArgumentValidator> VALIDATORS = new LinkedHashMap<>();
    private static final Map<String, StatDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<MinecraftServer, Map<UUID, Map<String, Double>>> TOTAL_CACHE = new WeakHashMap<>();

    // Prevent this static stat registry from being instantiated.
    private SkillStatRegistry() {
    }

    // Register built-in readable stats and restore their effects after joins and respawns.
    public static void initialize() {
        registerAttribute("addHealth", "stat.crazy-world-progression.max_health", Attributes.MAX_HEALTH,
                AttributeModifier.Operation.ADD_VALUE, 1.0, StatValueFormat.NUMBER);
        registerAttribute("addMiningSpeed", "stat.crazy-world-progression.mining_speed", Attributes.BLOCK_BREAK_SPEED,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0.01, StatValueFormat.PERCENT);
        registerAttribute("addMovementSpeed", "stat.crazy-world-progression.movement_speed", Attributes.MOVEMENT_SPEED,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0.01, StatValueFormat.PERCENT);
        registerAttribute("addAttackSpeed", "stat.crazy-world-progression.attack_speed", Attributes.ATTACK_SPEED,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0.01, StatValueFormat.PERCENT);
        registerAttribute("addJumpStrength", "stat.crazy-world-progression.jump_strength", Attributes.JUMP_STRENGTH,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0.01, StatValueFormat.PERCENT);
        registerAttribute("addBlockInteractionRange", "stat.crazy-world-progression.block_range",
                Attributes.BLOCK_INTERACTION_RANGE, AttributeModifier.Operation.ADD_VALUE, 1.0, StatValueFormat.NUMBER);
        registerAttribute("addEntityInteractionRange", "stat.crazy-world-progression.entity_range",
                Attributes.ENTITY_INTERACTION_RANGE, AttributeModifier.Operation.ADD_VALUE, 1.0, StatValueFormat.NUMBER);
        registerMultiplier("addMobDropMultiplier", "stat.crazy-world-progression.mob_drops");
        registerMultiplier("addExperienceMultiplier", "stat.crazy-world-progression.experience");
        registerMultiplier("addFarmingMultiplier", "stat.crazy-world-progression.farming");

        ServerPlayerEvents.JOIN.register(SkillStatRegistry::refresh);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refresh(newPlayer));
    }

    // Register one YAML function name and the code that applies its cumulative value.
    public static void register(String functionName, StatTranslation translation) {
        register(functionName, value -> { }, translation);
    }

    // Register one YAML function with argument validation performed while every tree is loaded.
    public static void register(String functionName, StatArgumentValidator validator, StatTranslation translation) {
        String key = functionName.toLowerCase(Locale.ROOT);
        if (TRANSLATIONS.putIfAbsent(key, translation) != null) {
            throw new IllegalArgumentException("Duplicate skill stat translation: " + functionName);
        }
        VALIDATORS.put(key, validator);
    }

    // Register an additive non-attribute number for application-specific progression such as a world level.
    public static void registerNumberStat(String functionName, String translationKey, double startingValue,
                                          boolean nonNegative, StatArgumentValidator validator,
                                          StatTranslation translation) {
        if (!Double.isFinite(startingValue)) throw new IllegalArgumentException("Starting stat value must be finite");
        registerDefinition(new StatDefinition(functionName, translationKey, null,
                AttributeModifier.Operation.ADD_VALUE, 1.0, StatValueFormat.NUMBER, startingValue, nonNegative));
        register(functionName, validator, translation);
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
        VALIDATORS.get(functionName.toLowerCase(Locale.ROOT)).validate(value);
        return new ParsedStat(functionName.toLowerCase(Locale.ROOT), value, translation);
    }

    // Return one registered stat's cumulative value for a connected player and all applicable unlocks.
    public static double getTotal(ServerPlayer player, String functionName) {
        String key = requireFunction(functionName);
        MinecraftServer server = player.level().getServer();
        Map<String, Double> totals;
        synchronized (TOTAL_CACHE) {
            totals = TOTAL_CACHE.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).get(player.getUUID());
        }
        if (totals == null) {
            totals = calculateTotals(server, player.getUUID());
            cacheTotals(server, player.getUUID(), totals);
        }
        return totals.get(key);
    }

    // Return one registered stat's cumulative value without requiring the target player to be online.
    public static double getTotal(MinecraftServer server, UUID playerUuid, String functionName) {
        String key = requireFunction(functionName);
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(playerUuid);
        if (onlinePlayer != null) return getTotal(onlinePlayer, key);
        return calculateTotals(server, playerUuid).get(key);
    }

    // Return one registered stat's global-only cumulative value without requiring a player profile.
    public static double getGlobalTotal(MinecraftServer server, String functionName) {
        String key = requireFunction(functionName);
        return calculateTotals(server, new UUID(0L, 0L), SkillTreeDefinition.SkillTreeType.GLOBAL).get(key);
    }

    // Return immutable raw YAML totals for only global or only personal unlocks.
    public static Map<String, Double> getTotals(MinecraftServer server, UUID playerUuid,
                                                SkillTreeDefinition.SkillTreeType type) {
        return calculateTotals(server, playerUuid, type);
    }

    // Recalculate every registered skill modifier for one player from persisted unlocks.
    public static void refresh(ServerPlayer player) {
        BaselineStatRegistry.apply(player);
        Map<String, Double> totals = calculateTotals(player.level().getServer(), player.getUUID());
        cacheTotals(player.level().getServer(), player.getUUID(), totals);
        totals.forEach((name, value) -> TRANSLATIONS.get(name).apply(player, value));
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    // Recalculate global skill modifiers for every currently connected player.
    public static void refreshAll(Iterable<ServerPlayer> players) {
        players.forEach(SkillStatRegistry::refresh);
    }

    // Discard cached totals after tree definitions are replaced so future event multipliers use the new content.
    static void clearCachedTotals() {
        synchronized (TOTAL_CACHE) {
            TOTAL_CACHE.clear();
        }
    }

    // Validate and normalize a public function lookup before consulting accumulated values.
    private static String requireFunction(String functionName) {
        String key = functionName.toLowerCase(Locale.ROOT);
        if (!TRANSLATIONS.containsKey(key)) {
            throw new IllegalArgumentException("Unknown skill stat function '" + functionName + "'");
        }
        return key;
    }

    // Store one immutable total map for fast repeated loot, experience, and other event lookups.
    private static void cacheTotals(MinecraftServer server, UUID playerUuid, Map<String, Double> totals) {
        synchronized (TOTAL_CACHE) {
            TOTAL_CACHE.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).put(playerUuid, totals);
        }
    }

    // Accumulate every personal and global YAML stat that applies to the supplied player UUID.
    private static Map<String, Double> calculateTotals(MinecraftServer server, UUID playerUuid) {
        Map<String, Double> totals = emptyTotals();
        calculateTotals(server, playerUuid, SkillTreeDefinition.SkillTreeType.GLOBAL)
                .forEach((name, value) -> totals.merge(name, value, Double::sum));
        calculateTotals(server, playerUuid, SkillTreeDefinition.SkillTreeType.PERSONAL)
                .forEach((name, value) -> totals.merge(name, value, Double::sum));
        return Map.copyOf(totals);
    }

    // Accumulate registered expressions from unlocked nodes in exactly one progression scope.
    private static Map<String, Double> calculateTotals(MinecraftServer server, UUID playerUuid,
                                                        SkillTreeDefinition.SkillTreeType type) {
        Map<String, Double> totals = emptyTotals();
        Set<String> unlocks = ProgressionService.getUnlocks(server, playerUuid, type);
        for (SkillTreeDefinition tree : SkillTreeManager.getSkillTrees()) {
            if (tree.type() != type) continue;
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
        return Map.copyOf(totals);
    }

    // Create a deterministic zero-filled accumulator in stat registration order.
    private static Map<String, Double> emptyTotals() {
        Map<String, Double> totals = new LinkedHashMap<>();
        TRANSLATIONS.keySet().forEach(key -> totals.put(key, 0.0));
        return totals;
    }

    // Return the stable transient modifier ID shared by application and stat-sheet calculations.
    static Identifier modifierId(String functionName) {
        return CrazyWorldProgression.id("skill_" + functionName.toLowerCase(Locale.ROOT));
    }

    // Return built-in stat definitions in their stable GUI and registration order.
    static List<StatDefinition> definitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    // Return every registered YAML function name, including extension-provided custom handlers.
    public static List<String> registeredFunctions() {
        return List.copyOf(TRANSLATIONS.keySet());
    }

    // Resolve metadata needed to reuse one registered built-in expression as a baseline.
    static StatDefinition definition(String functionName) {
        return DEFINITIONS.get(functionName.toLowerCase(Locale.ROOT));
    }

    // Register an attribute-backed stat with a consistent percentage or flat-value scale.
    private static void registerAttribute(
            String functionName,
            String translationKey,
            Holder<Attribute> attribute,
            AttributeModifier.Operation operation,
            double scale,
            StatValueFormat format
    ) {
        StatDefinition definition = new StatDefinition(
                functionName, translationKey, attribute, operation, scale, format, 0.0, false);
        registerDefinition(definition);
        Identifier modifierId = modifierId(definition.key());
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

    // Register an event-driven percentage stat whose value is consumed by SkillMultiplierService.
    private static void registerMultiplier(String functionName, String translationKey) {
        registerDefinition(new StatDefinition(functionName, translationKey, null,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0.01,
                StatValueFormat.MULTIPLIER, 1.0, true));
        register(functionName, (player, value) -> { });
    }

    // Publish one baseline-capable built-in definition before attaching its runtime handler.
    private static void registerDefinition(StatDefinition definition) {
        if (DEFINITIONS.putIfAbsent(definition.key(), definition) != null) {
            throw new IllegalArgumentException("Duplicate skill stat definition: " + definition.functionName());
        }
    }

    @FunctionalInterface
    public interface StatTranslation {
        // Apply the cumulative raw YAML value for this stat to one player.
        void apply(ServerPlayer player, double value);
    }

    @FunctionalInterface
    public interface StatArgumentValidator {
        // Reject one parsed YAML argument by throwing an actionable IllegalArgumentException.
        void validate(double value);
    }

    public record ParsedStat(String functionName, double value, StatTranslation translation) {
    }

    record StatDefinition(String functionName, String translationKey, Holder<Attribute> attribute,
                          AttributeModifier.Operation operation, double scale, StatValueFormat format,
                          double startingValue, boolean nonNegative) {
        // Return the lowercase expression function used by totals, baselines, and stable modifier IDs.
        String key() {
            return functionName.toLowerCase(Locale.ROOT);
        }
    }
}
