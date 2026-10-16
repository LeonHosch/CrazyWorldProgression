package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.api.mobforge.MobForgeHandle;
import ekuzo.crazyworldprogression.api.mobforge.ScriptEntityHandle;

import java.util.Map;
import java.util.regex.Pattern;

/** Deliberately small, capability-based condition grammar; no expression evaluation or reflection. */
public final class ScriptCondition {
    private static final Pattern CONDITION = Pattern.compile("\\$\\{([a-z][a-z0-9_]*)\\.(dead|health_percent|phase)(?:\\s*(<=|>=|==|!=|<|>)\\s*([a-z0-9_./-]+))?}");
    private ScriptCondition() { }
    public static String reference(String expression) {
        if (expression == null || !expression.matches("\\$\\{[a-z][a-z0-9_]*}"))
            throw new IllegalArgumentException("Expected script handle like ${guardian}: " + expression);
        return expression.substring(2, expression.length() - 1);
    }
    public static String referencedHandle(String expression) { return parse(expression).group(1); }
    public static void validate(String expression) { parse(expression); }
    public static boolean evaluate(String expression, Map<String, ScriptEntityHandle> handles) {
        var match = parse(expression);
        ScriptEntityHandle handle = handles.get(match.group(1));
        if (handle == null) throw new IllegalArgumentException("Unknown script handle " + match.group(1));
        String field = match.group(2), op = match.group(3), expected = match.group(4);
        if (field.equals("dead")) return !handle.isAlive();
        if (field.equals("phase")) {
            if (!(handle instanceof MobForgeHandle mob)) throw new IllegalArgumentException("Handle " + match.group(1) + " is not a registered MobForge mob");
            String actual = mob.phase().orElse("");
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
        if (expression == null) throw new IllegalArgumentException("wait_until condition is required");
        var match = CONDITION.matcher(expression.trim());
        if (!match.matches()) throw new IllegalArgumentException("Unsupported condition: " + expression);
        String field = match.group(2), op = match.group(3);
        if (field.equals("dead") && op != null) throw new IllegalArgumentException("dead condition takes no comparison");
        if (!field.equals("dead") && op == null) throw new IllegalArgumentException(field + " condition needs a comparison");
        if (field.equals("phase") && !op.equals("==") && !op.equals("!=")) throw new IllegalArgumentException("phase supports == and != only");
        if (field.equals("health_percent")) {
            try { double value = Double.parseDouble(match.group(4)); if (!Double.isFinite(value) || value < 0 || value > 1) throw new NumberFormatException(); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("health_percent threshold must be between 0 and 1"); }
        }
        return match;
    }
}
