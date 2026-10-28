package ekuzo.crazyworldprogression.mobforge.script;

import com.google.gson.Gson;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately limited Python-like source format. No Python code is executed. */
public final class MobScriptCode {
    private static final Pattern CALL = Pattern.compile("([a-z][a-z0-9_]*)\\((.*)\\)");
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");
    private static final Gson JSON = new Gson();
    private final Load load = new Load(LoadSettings.builder().setLabel("MobForge code").setCodePointLimit(1_000_000).build());
    private final Dump dump = new Dump(DumpSettings.builder().build());
    private final MobScriptYaml yaml = new MobScriptYaml();

    public MobScript decode(String source) {
        if (source == null || source.length() > 1_000_000) throw new IllegalArgumentException("Script source is too large");
        String id = null;
        List<Object> steps = new ArrayList<>();
        ArrayDeque<Block> blocks = new ArrayDeque<>();
        String[] lines = source.split("\\R", -1);
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String raw = stripComment(lines[lineNumber]);
            if (raw.isBlank()) continue;
            int spaces = 0;
            while (spaces < raw.length() && raw.charAt(spaces) == ' ') spaces++;
            if (spaces % 4 != 0 || raw.substring(spaces).indexOf('\t') >= 0)
                throw error(lineNumber, "Use four spaces per indentation level and no tabs");
            int depth = spaces / 4;
            String text = raw.substring(spaces).trim();
            if (id == null) {
                if (depth != 0 || !text.startsWith("script ")) throw error(lineNumber, "First line must be script <id>");
                id = text.substring(7).trim();
                if (!ID.matcher(id).matches()) throw error(lineNumber, "Script ID must use lowercase letters, numbers, _ or -");
                continue;
            }
            if (text.startsWith("else")) {
                while (blocks.size() > depth + 1) close(blocks, steps, lineNumber);
                if (!text.equals("else:") || blocks.size() != depth + 1 || blocks.isEmpty()
                        || !blocks.peek().kind.equals("if") || blocks.peek().elseSeen)
                    throw error(lineNumber, "else: must match the preceding if block");
                if (steps.size() == blocks.peek().bodyStart) throw error(lineNumber, "if block is empty");
                Block previous = blocks.pop();
                steps.add(Map.of("else", true));
                blocks.push(new Block("if", true, steps.size()));
                continue;
            }
            if (depth > blocks.size()) throw error(lineNumber, "Unexpected indentation");
            while (blocks.size() > depth) close(blocks, steps, lineNumber);
            if (text.startsWith("if ") && text.endsWith(":")) {
                String condition = text.substring(3, text.length() - 1).trim();
                ScriptCondition.validate(condition);
                steps.add(Map.of("if", condition));
                blocks.push(new Block("if", false, steps.size()));
            } else if (text.startsWith("while ") && text.endsWith(":")) {
                String header = text.substring(6, text.length() - 1).trim();
                int comma = topLevelLastComma(header);
                int limit = 1000;
                if (comma >= 0) {
                    String option = header.substring(comma + 1).trim();
                    if (!option.startsWith("max_iterations=")) throw error(lineNumber, "Expected max_iterations=<number>");
                    limit = Integer.parseInt(option.substring(15).trim());
                    header = header.substring(0, comma).trim();
                }
                ScriptCondition.validate(header);
                steps.add(Map.of("loop", Map.of("condition", header, "max_iterations", limit)));
                blocks.push(new Block("while", false, steps.size()));
            } else {
                Matcher call = CALL.matcher(text);
                if (!call.matches()) throw error(lineNumber, "Expected a script command, if, or while");
                String name = call.group(1);
                if (List.of("if", "else", "end_if", "loop", "end_loop").contains(name))
                    throw error(lineNumber, "Control flow uses indentation");
                Object body = arguments(call.group(2), lineNumber);
				if ((name.equals("finish_instance") || name.equals("encounter_start") || name.equals("encounter_end")
						|| name.equals("wipe")) && body instanceof Map<?, ?> map && map.isEmpty()) body = true;
                steps.add(Map.of(name, body));
            }
        }
        if (id == null) throw new IllegalArgumentException("First line must be script <id>");
        while (!blocks.isEmpty()) close(blocks, steps, lines.length);
        return yaml.decode(dump.dumpToString(Map.of("id", id, "steps", steps)));
    }

    public String encode(MobScript script) {
        Object parsed = load.loadFromString(yaml.encode(script));
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("steps") instanceof List<?> steps))
            throw new IllegalStateException("Could not encode script");
        StringBuilder out = new StringBuilder("script ").append(script.id()).append("\n\n");
        int depth = 0;
        for (Object raw : steps) {
            if (!(raw instanceof Map<?, ?> step) || step.size() != 1) throw new IllegalStateException("Invalid step data");
            String name = String.valueOf(step.keySet().iterator().next());
            Object body = step.get(name);
            if (name.equals("end_if") || name.equals("end_loop")) { depth--; continue; }
            if (name.equals("else")) { depth--; out.append("    ".repeat(depth)).append("else:\n"); depth++; continue; }
            out.append("    ".repeat(depth));
            if (name.equals("if")) { out.append("if ").append(body).append(":\n"); depth++; }
            else if (name.equals("loop")) {
                Map<?, ?> loop = (Map<?, ?>) body;
                out.append("while ").append(loop.get("condition")).append(", max_iterations=")
                        .append(loop.get("max_iterations")).append(":\n"); depth++;
			} else if ((name.equals("finish_instance") || name.equals("encounter_start") || name.equals("encounter_end")
                    || name.equals("wipe")) && !(body instanceof Map<?, ?>))
                out.append(name).append("()\n");
            else if (body instanceof Map<?, ?> fields) {
                List<String> args = new ArrayList<>();
                fields.forEach((key, value) -> args.add(key + "=" + JSON.toJson(value)));
                out.append(name).append('(').append(String.join(", ", args)).append(")\n");
            } else throw new IllegalStateException("Unsupported step " + name);
        }
        return out.toString();
    }

    private Object arguments(String content, int line) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (content.isBlank()) return fields;
        for (String argument : splitTopLevel(content, ',')) {
            int equals = topLevelIndex(argument, '=');
            if (equals < 0) throw error(line, "Use named arguments such as count=3");
            String key = argument.substring(0, equals).trim();
            if (!key.matches("[a-z][a-z0-9_]*") || fields.containsKey(key)) throw error(line, "Invalid or repeated argument " + key);
            Object value = load.loadFromString(argument.substring(equals + 1).trim());
            if (value == null) throw error(line, "Empty argument " + key);
            fields.put(key, value);
        }
        return fields;
    }

    private static void close(ArrayDeque<Block> blocks, List<Object> steps, int line) {
        Block block = blocks.pop();
        if (steps.size() == block.bodyStart) throw error(line, block.kind + " block is empty");
        steps.add(Map.of(block.kind.equals("if") ? "end_if" : "end_loop", true));
    }

    private static String stripComment(String line) {
        boolean quoted = false, escaped = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (quoted && c == quote) { quoted = false; continue; }
            if (!quoted && (c == '"' || c == '\'')) { quoted = true; quote = c; continue; }
            if (!quoted && c == '#') return line.substring(0, i);
        }
        return line;
    }

    private static int topLevelLastComma(String value) {
        int last = -1, start = 0;
        while (start < value.length()) {
            int next = topLevelIndex(value.substring(start), ',');
            if (next < 0) break;
            last = start + next; start = last + 1;
        }
        return last;
    }

    private static int topLevelIndex(String value, char target) {
        int nesting = 0; char quote = 0; boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) { if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '(' || c == '{' || c == '[') nesting++;
            else if (c == ')' || c == '}' || c == ']') nesting--;
            else if (nesting == 0 && c == target) return i;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value, char delimiter) {
        List<String> parts = new ArrayList<>(); int start = 0;
        while (start < value.length()) {
            int index = topLevelIndex(value.substring(start), delimiter);
            if (index < 0) { parts.add(value.substring(start).trim()); break; }
            parts.add(value.substring(start, start + index).trim()); start += index + 1;
        }
        return parts;
    }

    private static IllegalArgumentException error(int line, String message) {
        return new IllegalArgumentException("Line " + (line + 1) + ": " + message);
    }

    private record Block(String kind, boolean elseSeen, int bodyStart) { }
}
