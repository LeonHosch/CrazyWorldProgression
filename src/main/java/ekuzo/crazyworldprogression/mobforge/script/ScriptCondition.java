package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.api.mobforge.MobForgeHandle;
import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;

import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import ekuzo.crazyworldprogression.mobforge.entity.MobForgeEntityData;
import ekuzo.crazyworldprogression.mobforge.MobForge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

/** Deliberately small, capability-based condition grammar; no expression evaluation or reflection. */
public final class ScriptCondition {
    private static final Pattern CONDITION = Pattern.compile("\\$\\{([a-z][a-z0-9_]*)\\.(dead|alive|health_percent|phase)(?:\\s*(<=|>=|==|!=|<|>)\\s*([a-z0-9_./-]+))?}");
    private static final Pattern SENSOR = Pattern.compile("\\$\\{(var|box|box_triggers|kills|portal|redstone)\\.([a-z0-9_:/.-]+)(?:\\s*(<=|>=|==|!=|<|>)\\s*(true|false|-?[0-9]+))?}");
    private static final Pattern FUNCTION = Pattern.compile("(kills|portal_used|players_in|box_triggers|powered|var|dead|alive|health_percent|phase)\\(\\s*['\"]([a-z0-9_:/.-]+)['\"]\\s*\\)(?:\\s*(<=|>=|==|!=|<|>)\\s*(['\"]?[a-z0-9_./-]+['\"]?))?");
    /** blocks("core", "minecraft:ancient_debris") counts that block in an event box. */
    private static final Pattern BLOCKS = Pattern.compile("blocks\\(\\s*['\"]([a-z][a-z0-9_]*)['\"]\\s*,\\s*['\"]([a-z0-9_.-]+:[a-z0-9_./-]+)['\"]\\s*\\)(?:\\s*(<=|>=|==|!=|<|>)\\s*(-?[0-9]+))?");
    /** elapsed() is the number of seconds since the script run started. */
    private static final Pattern ELAPSED = Pattern.compile("elapsed\\(\\s*\\)\\s*(<=|>=|==|!=|<|>)\\s*([0-9]+)");
    private static final Pattern POWER_POSITION = Pattern.compile("powered\\(\\s*(-?[0-9]+)\\s*,\\s*(-?[0-9]+)\\s*,\\s*(-?[0-9]+)\\s*\\)(?:\\s*(==|!=)\\s*(true|false))?");
    private ScriptCondition() { }
    /** A mob tag (or legacy handle), written as boss or ${boss}. */
    public static String reference(String expression) {
        if (expression != null && expression.matches("[a-z][a-z0-9_]*")) return expression;
        if (expression == null || !expression.matches("\\$\\{[a-z][a-z0-9_]*}"))
            throw new IllegalArgumentException("Expected a mob tag like boss: " + expression);
        return expression.substring(2, expression.length() - 1);
    }
    public static String referencedHandle(String expression) {
        String normalized = normalize(expression);
        var match = CONDITION.matcher(normalized);
        return match.matches() ? match.group(1) : null;
    }
    public static void validate(String expression) {
        String atom = unwrap(expression == null ? "" : expression.trim());
        int or = operator(atom, " or ");
        if (or >= 0) { validate(atom.substring(0, or)); validate(atom.substring(or + 4)); return; }
        int and = operator(atom, " and ");
        if (and >= 0) { validate(atom.substring(0, and)); validate(atom.substring(and + 5)); return; }
        if (atom.startsWith("not ")) { validate(atom.substring(4)); return; }
        if (POWER_POSITION.matcher(atom).matches() || BLOCKS.matcher(atom).matches() || ELAPSED.matcher(atom).matches()) return;
        atom = normalize(atom);
        if (SENSOR.matcher(atom).matches()) return;
        parse(atom);
    }
    public record Box(MobScript.Position min, MobScript.Position max, String mode, String mobId,
                      String playerVariable, String countVariable) {
        public Box(MobScript.Position min, MobScript.Position max) { this(min, max, "players", "", "", ""); }
        public Box(MobScript.Position min, MobScript.Position max, String mode, String mobId) {
            this(min, max, mode, mobId, "", "");
        }
        public Box(MobScript.Position min, MobScript.Position max, String mode, String mobId, String playerVariable) {
            this(min, max, mode, mobId, playerVariable, "");
        }
        public Box {
            if (min == null || max == null || min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
                throw new IllegalArgumentException("Event box corners must be ordered min to max");
        }
        public boolean contains(net.minecraft.world.phys.Vec3 point) {
            return new AABB(min.x(), min.y(), min.z(), max.x() + 1, max.y() + 1, max.z() + 1).contains(point);
        }
        public long count(ServerLevel level) {
            if (mode.equals("players")) return level.players().stream()
                    .filter(player -> !player.isSpectator() && contains(player.position())).count();
            AABB bounds = new AABB(min.x(), min.y(), min.z(), max.x() + 1, max.y() + 1, max.z() + 1);
            return level.getEntitiesOfClass(Mob.class, bounds, mob ->
                    MobForgeEntityData.template(mob).filter(template -> mobId.equals(template)
                            || mobId.equals(MobForge.id(template).toString())).isPresent()
                            || MobForgeEntityData.definition(mob).map(Identifier::toString)
                                    .filter(mobId::equals).isPresent()
                            || BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString().equals(mobId)).size();
        }
    }
    public record Context(ServerLevel level, Map<String, Long> variables, Map<String, Box> boxes,
                          Map<String, BlockPos> redstone, Map<String, Long> kills,
                          Map<Identifier, Long> portalUses, Map<String, Long> boxTriggers,
                          Map<String, MobScript.AreaStep> areas, long elapsedTicks) {
        public Context(ServerLevel level, Map<String, Long> variables, Map<String, Box> boxes,
                       Map<String, BlockPos> redstone, Map<String, Long> kills,
                       Map<Identifier, Long> portalUses, Map<String, Long> boxTriggers) {
            this(level, variables, boxes, redstone, kills, portalUses, boxTriggers, Map.of(), 0);
        }
        public Context(ServerLevel level, Map<String, Long> variables, Map<String, Box> boxes,
                       Map<String, BlockPos> redstone, Map<String, Long> kills,
                       Map<Identifier, Long> portalUses) {
            this(level, variables, boxes, redstone, kills, portalUses, Map.of());
        }
    }
    /** Bare kill and portal expressions are occurrence streams for event watchers. */
    public static Long eventCounter(String expression, Context context) {
        var sensor = SENSOR.matcher(normalize(expression));
        if (!sensor.matches() || sensor.group(3) != null) return null;
        return switch (sensor.group(1)) {
            case "kills" -> context.kills().getOrDefault(sensor.group(2), 0L);
            case "portal" -> context.portalUses().getOrDefault(Identifier.parse(sensor.group(2)), 0L);
            case "box_triggers" -> context.boxTriggers().getOrDefault(sensor.group(2), 0L);
            default -> null;
        };
    }
    public static boolean evaluate(String expression, Map<String, ScriptEntityHandle> handles, Context context) {
        String atom = unwrap(expression.trim());
        int or = operator(atom, " or ");
        if (or >= 0) return evaluate(atom.substring(0, or), handles, context)
                || evaluate(atom.substring(or + 4), handles, context);
        int and = operator(atom, " and ");
        if (and >= 0) return evaluate(atom.substring(0, and), handles, context)
                && evaluate(atom.substring(and + 5), handles, context);
        if (atom.startsWith("not ")) return !evaluate(atom.substring(4), handles, context);
        var position = POWER_POSITION.matcher(atom);
        if (position.matches()) {
            BlockPos pos = new BlockPos(Integer.parseInt(position.group(1)), Integer.parseInt(position.group(2)), Integer.parseInt(position.group(3)));
            boolean powered = powered(context.level(), pos);
            return position.group(4) == null ? powered : position.group(4).equals("==") == (powered == Boolean.parseBoolean(position.group(5)));
        }
        var blocks = BLOCKS.matcher(atom);
        if (blocks.matches()) {
            long count = countBlocks(context, blocks.group(1), Identifier.parse(blocks.group(2)));
            return blocks.group(3) == null ? count > 0 : compare(count, blocks.group(3), Long.parseLong(blocks.group(4)));
        }
        var elapsed = ELAPSED.matcher(atom);
        if (elapsed.matches()) return compare(context.elapsedTicks() / 20, elapsed.group(1), Long.parseLong(elapsed.group(2)));
        atom = normalize(atom);
        var sensor = SENSOR.matcher(atom);
        if (!sensor.matches()) return evaluate(atom, handles);
        String kind = sensor.group(1), name = sensor.group(2);
        long actual = switch (kind) {
            case "var" -> context.variables().getOrDefault(name, 0L);
            case "kills" -> context.kills().getOrDefault(name, 0L);
            case "portal" -> context.portalUses().getOrDefault(Identifier.parse(name), 0L);
            case "box_triggers" -> context.boxTriggers().getOrDefault(name, 0L);
            case "redstone" -> {
                BlockPos pos = context.redstone().get(name);
                if (pos == null) throw new IllegalArgumentException("Unknown redstone sensor " + name);
                yield powered(context.level(), pos) ? 1L : 0L;
            }
            case "box" -> {
                MobScript.AreaStep area = context.areas().get(name);
                if (area != null) yield context.level().players().stream()
                        .filter(player -> !player.isSpectator() && area.contains(player.position())).count();
                Box box = context.boxes().get(name);
                if (box == null) throw new IllegalArgumentException("Unknown event box " + name);
                yield box.count(context.level());
            }
            default -> throw new IllegalArgumentException("Unknown sensor " + kind);
        };
        if (sensor.group(3) == null) return actual != 0L;
        long expected = switch (sensor.group(4)) {
            case "true" -> 1L;
            case "false" -> 0L;
            default -> Long.parseLong(sensor.group(4));
        };
        return switch (sensor.group(3)) {
            case "<" -> actual < expected;
            case "<=" -> actual <= expected;
            case ">" -> actual > expected;
            case ">=" -> actual >= expected;
            case "==" -> actual == expected;
            case "!=" -> actual != expected;
            default -> throw new IllegalArgumentException("Unknown comparison");
        };
    }
    public static boolean evaluate(String expression, Map<String, ScriptEntityHandle> handles) {
        var match = parse(normalize(expression));
        ScriptEntityHandle handle = handles.get(match.group(1));
        if (handle == null) throw new IllegalArgumentException("Unknown script handle " + match.group(1));
        String field = match.group(2), op = match.group(3), expected = match.group(4);
        if (field.equals("dead")) return !handle.isAlive();
        if (field.equals("alive")) {
            long living = handle instanceof TagGroup group ? group.living().size() : handle.isAlive() ? 1 : 0;
            return compare(living, op, Long.parseLong(expected));
        }
        if (field.equals("phase")) {
            String actual;
            if (handle instanceof TagGroup group) actual = group.phase();
            else if (handle instanceof MobForgeHandle mob) actual = mob.phase().orElse("");
            else throw new IllegalArgumentException("Handle " + match.group(1) + " is not a registered MobForge mob");
            return op.equals("==") ? actual.equals(expected) : !actual.equals(expected);
        }
        double threshold = Double.parseDouble(expected);
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("health_percent threshold must be between 0 and 1");
        double max = handle.maxHealth();
        double actual = max <= 0 ? 0 : handle.health() / max;
        return switch (op) {
            case "<" -> actual < threshold;
            case "<=" -> actual <= threshold;
            case ">" -> actual > threshold;
            case ">=" -> actual >= threshold;
            case "==" -> actual == threshold;
            case "!=" -> actual != threshold;
            default -> throw new IllegalArgumentException("Unknown condition operator " + op);
        };
    }
    private static java.util.regex.Matcher parse(String expression) {
        if (expression == null) throw new IllegalArgumentException("wait condition is required");
        var match = CONDITION.matcher(expression.trim());
        if (!match.matches()) throw new IllegalArgumentException("Unsupported condition: " + expression);
        String field = match.group(2), op = match.group(3);
        if (field.equals("dead") && op != null) throw new IllegalArgumentException("dead condition takes no comparison");
        if (!field.equals("dead") && op == null) throw new IllegalArgumentException(field + " condition needs a comparison");
        if (field.equals("alive") && !match.group(4).matches("[0-9]+")) throw new IllegalArgumentException("alive() compares with a whole number");
        if (field.equals("phase") && !op.equals("==") && !op.equals("!=")) throw new IllegalArgumentException("phase supports == and != only");
        if (field.equals("health_percent")) {
            try { double value = Double.parseDouble(match.group(4)); if (!Double.isFinite(value) || value < 0 || value > 1) throw new NumberFormatException(); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("health_percent threshold must be between 0 and 1"); }
        }
        return match;
    }

    private static boolean compare(long actual, String operator, long expected) {
        return switch (operator) {
            case "<" -> actual < expected;
            case "<=" -> actual <= expected;
            case ">" -> actual > expected;
            case ">=" -> actual >= expected;
            case "==" -> actual == expected;
            case "!=" -> actual != expected;
            default -> throw new IllegalArgumentException("Unknown comparison " + operator);
        };
    }

    /** How many blocks of this type are in the event box (event boxes only: areas are too big to count every tick). */
    private static long countBlocks(Context context, String boxName, Identifier blockId) {
        Box box = context.boxes().get(boxName);
        if (box == null) throw new IllegalArgumentException("blocks() needs an event box, not " + boxName);
        var block = BuiltInRegistries.BLOCK.getValue(blockId);
        long volume = (long) (box.max().x() - box.min().x() + 1) * (long) (box.max().y() - box.min().y() + 1)
                * (long) (box.max().z() - box.min().z() + 1);
        if (volume > 65_536) throw new IllegalArgumentException("blocks() event box " + boxName + " is too big (max 65536 blocks)");
        long count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.min().x(), box.min().y(), box.min().z()),
                BlockPos.containing(box.max().x(), box.max().y(), box.max().z())))
            if (context.level().getBlockState(pos).is(block)) count++;
        return count;
    }

    private static boolean powered(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return level.hasNeighborSignal(pos)
                || state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)
                || state.hasProperty(BlockStateProperties.POWER) && state.getValue(BlockStateProperties.POWER) > 0;
    }

    private static String normalize(String expression) {
        if (expression == null) return "";
        String atom = expression.trim();
        var match = FUNCTION.matcher(atom);
        if (!match.matches()) return atom;
        String prefix = switch (match.group(1)) {
            case "portal_used" -> "portal";
            case "players_in" -> "box";
            case "box_triggers" -> "box_triggers";
            case "powered" -> "redstone";
            case "dead", "alive", "health_percent", "phase" -> null;
            default -> match.group(1);
        };
        String suffix = match.group(3) == null ? "" : " " + match.group(3) + " " + match.group(4).replace("\"", "").replace("'", "");
        return "${" + (prefix == null ? match.group(2) + "." + match.group(1) : prefix + "." + match.group(2)) + suffix + "}";
    }

    private static int operator(String expression, String operator) {
        char quote = 0; int depth = 0;
        for (int i = 0; i <= expression.length() - operator.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0 && expression.startsWith(operator, i)) return i;
        }
        return -1;
    }

    private static String unwrap(String expression) {
        String result = expression;
        while (result.startsWith("(") && result.endsWith(")")) {
            int depth = 0; boolean whole = true; char quote = 0;
            for (int i = 0; i < result.length(); i++) {
                char c = result.charAt(i);
                if (quote != 0) { if (c == quote) quote = 0; continue; }
                if (c == '\'' || c == '"') { quote = c; continue; }
                if (c == '(') depth++;
                else if (c == ')') depth--;
                if (depth == 0 && i < result.length() - 1) { whole = false; break; }
            }
            if (!whole || depth != 0) break;
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }
}
