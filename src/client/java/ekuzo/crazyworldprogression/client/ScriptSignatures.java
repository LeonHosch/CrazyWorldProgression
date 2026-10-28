package ekuzo.crazyworldprogression.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inputs of every .mfs command and condition, for the in-game source editor's completion and hint panel.
 * Kept in step with MobScriptYaml (what is required) and docs/mobforge-scripting.md (what it means).
 */
final class ScriptSignatures {
    private static final String POSITION = "{\"x\":0,\"y\":64,\"z\":0}";
    private static final String OFFSET = "{\"x\":0,\"y\":0,\"z\":0}";

    /** Value lists the client reads from its own registries; every other list comes from the server. */
    static final Set<String> CLIENT_VALUES = Set.of("sound", "effect", "particle", "item", "entity");
    /** Value lists read from the script being edited. */
    static final Set<String> SCRIPT_VALUES = Set.of("script_tag", "script_box", "script_var");

    /**
     * One input. {@code template} is inserted after {@code name=}; a "" template puts the cursor between the quotes.
     * {@code values} names the list of IDs suggested while typing the value (null for none). Required inputs that
     * share a {@code oneOf} group are alternatives: exactly one of them is needed.
     */
    record Arg(String name, String template, String about, String values, String oneOf) {
        Arg(String name, String template, String about) { this(name, template, about, null, null); }

        Arg values(String list) { return new Arg(name, template, about, list, oneOf); }

        Arg oneOf(String group) { return new Arg(name, template, about, values, group); }
    }

    /**
     * A command (named inputs) or condition (positional inputs). {@code block} marks if/while/script, which are
     * completed as keywords instead of calls.
     */
    record Signature(String name, String about, List<Arg> required, List<Arg> optional, String example,
                     boolean condition, boolean block) {
        Arg arg(String argName) {
            for (Arg arg : required) if (arg.name().equals(argName)) return arg;
            for (Arg arg : optional) if (arg.name().equals(argName)) return arg;
            return null;
        }

        /** Required inputs to insert with the command: every input, but only the first of each "one of" group. */
        List<Arg> inserted() {
            List<Arg> result = new ArrayList<>();
            Set<String> groups = new java.util.HashSet<>();
            for (Arg arg : required) if (arg.oneOf() == null || groups.add(arg.oneOf())) result.add(arg);
            return result;
        }

        /** True when this required input, or another of its "one of" group, is written in the call. */
        boolean satisfied(Arg arg, List<String> used) {
            if (used.contains(arg.name())) return true;
            if (arg.oneOf() == null) return false;
            for (Arg other : required) if (arg.oneOf().equals(other.oneOf()) && used.contains(other.name())) return true;
            return false;
        }
    }

    static final Map<String, Signature> COMMANDS = new LinkedHashMap<>();
    static final Map<String, Signature> CONDITIONS = new LinkedHashMap<>();

    private ScriptSignatures() { }

    /**
     * What the cursor is working on. {@code callOpen} is the source index of the call's "(" (-1 outside a call);
     * {@code argument} the named input the cursor is on, {@code argIndex} the positional one for conditions;
     * {@code prefix} the input name being typed directly inside the call (null while editing a value);
     * {@code stringStart} the index after the opening quote when the cursor is inside that input's quoted value.
     */
    record Context(Signature signature, int callOpen, String argument, int argIndex, String prefix,
                   List<String> usedArgs, int stringStart) {
        static final Context NONE = new Context(null, -1, null, -1, null, List.of(), -1);

        /** The input the cursor is on, named or positional. */
        Arg currentArg() {
            if (signature == null) return null;
            if (signature.condition())
                return argIndex >= 0 && argIndex < signature.required().size() ? signature.required().get(argIndex) : null;
            return argument == null ? null : signature.arg(argument);
        }
    }

    /** Analyses the cursor's line: the innermost call around the cursor, else the command the line starts with. */
    static Context at(String source, int cursor) {
        int lineStart = source.lastIndexOf('\n', cursor - 1) + 1;
        int lineEnd = source.indexOf('\n', cursor);
        if (lineEnd < 0) lineEnd = source.length();
        java.util.ArrayDeque<Frame> frames = new java.util.ArrayDeque<>();
        char quote = 0;
        int quoteStart = -1;
        boolean escaped = false;
        for (int index = lineStart; index < cursor; index++) {
            char c = source.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) {
                if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '#') break;
            if (c == '"' || c == '\'') { quote = c; quoteStart = index; }
            else if (c == '(') frames.push(new Frame(identifierBefore(source, lineStart, index), index));
            else if (c == '{' || c == '[') frames.push(new Frame(null, index));
            else if (c == ')' || c == '}' || c == ']') { if (!frames.isEmpty()) frames.pop(); }
            else if (!frames.isEmpty() && c == ',') { frames.peek().lastComma = index; frames.peek().commas++; frames.peek().equals = -1; }
            else if (!frames.isEmpty() && c == '=' && frames.peek().equals < 0) frames.peek().equals = index;
        }
        Frame call = null;
        for (Frame frame : frames) if (frame.name != null) { call = frame; break; }
        if (call != null) {
            Signature signature = CONDITIONS.get(call.name);
            if (signature == null) signature = COMMANDS.get(call.name);
            if (signature == null || signature.block()) return Context.NONE;
            List<String> used = usedArgs(source, call.open, lineEnd);
            // Only a string that is the input's whole value counts, not one inside a position or other nesting.
            int stringStart = quote != 0 && frames.peek() == call ? quoteStart + 1 : -1;
            if (signature.condition()) return new Context(signature, call.open, null, call.commas, null, used, stringStart);
            int segmentStart = (call.lastComma >= 0 ? call.lastComma : call.open) + 1;
            if (call.equals >= 0)
                return new Context(signature, call.open, source.substring(segmentStart, call.equals).trim(), -1, null,
                        used, stringStart);
            String typed = source.substring(segmentStart, cursor).trim();
            boolean direct = frames.peek() == call && quote == 0 && typed.matches("[a-z_]*");
            Arg exact = signature.arg(typed);
            return new Context(signature, call.open, exact == null ? null : typed, -1, direct ? typed : null, used, -1);
        }
        String line = source.substring(lineStart, lineEnd).trim();
        Matcher word = Pattern.compile("^([a-z_]+)").matcher(line);
        if (!word.find()) return Context.NONE;
        String name = word.group(1);
        Signature signature = COMMANDS.get(name);
        if (signature == null) {
            for (Signature candidate : COMMANDS.values())
                if (candidate.name().startsWith(name)) { signature = candidate; break; }
        }
        return signature == null ? Context.NONE : new Context(signature, -1, null, -1, null, List.of(), -1);
    }

    /** Index of the quote that closes the string starting at {@code start}, or the line end when it is unclosed. */
    static int stringEnd(String source, int start) {
        int lineEnd = source.indexOf('\n', start);
        if (lineEnd < 0) lineEnd = source.length();
        char quote = start > 0 ? source.charAt(start - 1) : '"';
        boolean escaped = false;
        for (int index = start; index < lineEnd; index++) {
            char c = source.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') escaped = true;
            else if (c == quote) return index;
        }
        return lineEnd;
    }

    private static final Pattern TAG = Pattern.compile("\\btag\\s*=\\s*\"([a-z0-9_]+)\"");
    private static final Pattern BOX = Pattern.compile("\\b(?:eventbox|redstone_sensor)\\([^)\\n]*?\\bname\\s*=\\s*\"([a-z0-9_]+)\"");
    private static final Pattern VARIABLE = Pattern.compile(
            "\\b(?:variable\\(\\s*name|player_variable|count_variable|variable)\\s*=\\s*\"([a-z0-9_]+)\"");

    /** Tags, event box names or variable names the script already defines. */
    static List<String> scriptValues(String list, String source) {
        Pattern pattern = switch (list) {
            case "script_tag" -> TAG;
            case "script_box" -> BOX;
            default -> VARIABLE;
        };
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) values.add(matcher.group(1));
        return List.copyOf(values);
    }

    /**
     * Candidates for a typed value, best first: ID path starts with it, a word of the ID starts with it, the ID
     * contains it, every typed word is in the ID, then a shortened form of it is in the ID ("explosion" finds
     * "explode"). Ties go to the shorter ID.
     */
    static List<String> rank(Collection<String> candidates, String typed, int limit) {
        String query = typed.trim().toLowerCase(Locale.ROOT);
        record Match(String candidate, int score) { }
        List<Match> matches = new ArrayList<>();
        String[] words = query.split("\\s+");
        for (String candidate : candidates) {
            String id = candidate.toLowerCase(Locale.ROOT);
            if (query.isEmpty()) { matches.add(new Match(candidate, 0)); continue; }
            if (id.equals(query)) return List.of();
            String path = id.substring(id.indexOf(':') + 1);
            int score = -1;
            if (path.startsWith(query) || id.startsWith(query)) score = 0;
            else if (Pattern.compile("[.:_/]" + Pattern.quote(query)).matcher(id).find()) score = 1;
            else if (id.contains(query)) score = 2;
            else if (words.length > 1 && java.util.Arrays.stream(words).allMatch(id::contains)) score = 3;
            else if (words.length == 1)
                for (int length = query.length() - 1; length >= Math.min(4, query.length()) && score < 0; length--)
                    if (id.contains(query.substring(0, length))) score = 4 + query.length() - length;
            if (score >= 0) matches.add(new Match(candidate, score));
        }
        matches.sort(Comparator.comparingInt(Match::score).thenComparingInt(match -> match.candidate().length())
                .thenComparing(Match::candidate));
        return matches.stream().limit(limit).map(Match::candidate).toList();
    }

    /** Input names written as name= anywhere in the call that opens at {@code open} on this line. */
    private static List<String> usedArgs(String source, int open, int lineEnd) {
        List<String> used = new ArrayList<>();
        Matcher matcher = Pattern.compile("[(,]\\s*([a-z_]+)\\s*=").matcher(source.substring(open, lineEnd));
        while (matcher.find()) used.add(matcher.group(1));
        return used;
    }

    private static String identifierBefore(String source, int lineStart, int index) {
        int start = index;
        while (start > lineStart && (Character.isLetterOrDigit(source.charAt(start - 1)) || source.charAt(start - 1) == '_'))
            start--;
        return start == index ? null : source.substring(start, index);
    }

    private static final class Frame {
        private final String name;
        private final int open;
        private int lastComma = -1;
        private int commas;
        private int equals = -1;

        private Frame(String name, int open) {
            this.name = name;
            this.open = open;
        }
    }

    private static Arg text(String name, String about) { return new Arg(name, "\"\"", about); }
    private static Arg value(String name, String template, String about) { return new Arg(name, template, about); }
    private static Arg position(String name, String about) { return new Arg(name, POSITION, about); }

    private static void command(String name, String about, List<Arg> required, List<Arg> optional, String example) {
        COMMANDS.put(name, new Signature(name, about, required, optional, example, false, false));
    }

    private static void block(String name, String about, String example) {
        COMMANDS.put(name, new Signature(name, about, List.of(), List.of(), example, false, true));
    }

    private static void condition(String name, String about, List<Arg> inputs, String example) {
        CONDITIONS.put(name, new Signature(name, about, inputs, List.of(), example, true, false));
    }

    static {
        block("script", "First line of every file. The ID must match the file name.", "script plate_encounter");
        block("if", "Runs the indented block when the condition is true. Conditions combine with and, or, not.",
                "if players_in(\"plate\") >= 2:");
        block("while", "Repeats the indented block while the condition is true, one iteration per tick. "
                + "Optional max_iterations=<1..100000> after the condition, default 1000.",
                "while kills(\"adds\") < 6, max_iterations=1000:");
        command("achievement", "Completes an advancement for the players in a list variable, or everyone in the world.",
                List.of(text("achievement_id", "Advancement ID, e.g. mymod:story/root").values("advancement")),
                List.of(text("player", "Player list variable or UUID; omit for everyone").values("script_var")),
                "achievement(achievement_id=\"mymod:story/root\", player=\"entered\")");
        command("building", "Pastes a saved building with its minimum corner here, or clears the pasted range.",
                List.of(text("build_id", "Saved building ID, e.g. cwp:bridge").values("building"),
                        value("action", "\"add\"", "add or delete"),
                        position("location", "Minimum corner")),
                List.of(), "building(build_id=\"cwp:bridge\", action=\"add\", location={\"x\":5,\"y\":64,\"z\":5})");
        command("distances", "Changes this instance's view and simulation distance in chunks (2-32). 0 returns to "
                        + "the portal's setting.", List.of(),
                List.of(value("view", "8", "View distance in chunks"),
                        value("simulation", "4", "Simulation distance in chunks")),
                "distances(view=8, simulation=4)");
        command("effect", "Applies an infinite effect, or removes it.",
                List.of(text("effect_id", "Effect ID, e.g. minecraft:speed").values("effect")),
                List.of(text("player_id", "Player list variable or UUID; omit for everyone").values("script_var"),
                        value("action", "\"apply\"", "apply or remove"),
                        value("amplifier", "0", "0 is level I")),
                "effect(effect_id=\"minecraft:speed\", player_id=\"entered\", action=\"apply\", amplifier=0)");
        command("encounter_end", "Finishes the encounter and saves a checkpoint. Clears what the script manages first.",
                List.of(), List.of(position("spawn", "Rally, respawn and rejoin point")),
                "encounter_end(spawn={\"x\":-15,\"y\":64,\"z\":-10})");
        command("encounter_start", "Begins raid encounter restrictions. Clears what the script manages first.",
                List.of(), List.of(), "encounter_start()");
        command("event", "Registers a watch that changes a variable whenever the condition fires.",
                List.of(text("condition", "Condition, e.g. kills(\\\"adds\\\")"),
                        text("variable", "Variable to change").values("script_var")),
                List.of(value("operation", "\"increment\"", "set or increment (default increment)"),
                        value("value", "\"1\"", "Quoted whole number or \"true\"/\"false\" (default 1)")),
                "event(condition=\"kills(\\\"adds\\\")\", variable=\"charge\", operation=\"increment\", value=\"1\")");
        command("eventbox", "Tracks players or mobs inside a box. The maximum block is included.",
                List.of(text("name", "Box name for players_in and box_triggers"),
                        position("min", "Minimum corner"), position("max", "Maximum corner")),
                List.of(value("mode", "\"players\"", "players or mobs"),
                        text("mob_id", "Entity ID to count; needed with mode=\"mobs\"").values("entity"),
                        text("player_variable", "Saves every entering player's ID here"),
                        text("count_variable", "Live occupancy count")),
                "eventbox(name=\"plate\", min={\"x\":0,\"y\":64,\"z\":0}, max={\"x\":2,\"y\":66,\"z\":2})");
        command("explosion", "Explosion that damages entities but leaves blocks intact.",
                List.of(position("location", "Center")), List.of(value("power", "2", "Explosion power (default 2)")),
                "explosion(location={\"x\":0,\"y\":65,\"z\":0}, power=2)");
        command("loot", "Rolls a saved CWP loot pool for the players in a list variable, or each player inside.",
                List.of(text("lootpool_id", "Loot pool ID, e.g. cwp:raid_rewards").values("loot_pool")),
                List.of(text("player", "Player list variable or UUID; omit for everyone").values("script_var")),
                "loot(lootpool_id=\"cwp:raid_rewards\", player=\"entered\")");
        command("skill_xp", "Gives skill XP to the players in a list variable, or everyone inside. Dungeoning unless "
                        + "another skill is named (cooking, fishing, farming, mining, fighting).",
                List.of(value("amount", "10", "XP to give, 1 to 100000")),
                List.of(value("skill", "\"dungeoning\"", "Skill, default dungeoning"),
                        text("player", "Player list variable or UUID; omit for everyone").values("script_var")),
                "skill_xp(amount=50, player=\"entered\")");
        command("mob_cast", "Every living MobForge mob with this tag casts the ability.",
                List.of(text("mob", "mob_spawn tag").values("script_tag"),
                        text("ability", "Ability ID, e.g. cwp:slam").values("ability")), List.of(),
                "mob_cast(mob=\"boss\", ability=\"cwp:slam\")");
        command("mob_phase", "Every living MobForge mob with this tag changes phase.",
                List.of(text("mob", "mob_spawn tag").values("script_tag"), text("phase", "Phase name")), List.of(),
                "mob_phase(mob=\"boss\", phase=\"enraged\")");
        command("mob_spawn", "Spawns mobs from a MobForge template or an entity ID.",
                List.of(text("template", "MobForge template").values("template").oneOf("mob"),
                        text("mob_id", "Entity or MobForge ID, e.g. minecraft:zombie").values("mob").oneOf("mob"),
                        position("location", "Spawn position")),
                List.of(value("count", "1", "How many (default 1)"),
                        value("offset", OFFSET, "Random spread around the location"),
                        text("tag", "Name for kills, dead, health_percent, phase, mob_cast"),
                        value("ai", "{\"mode\":\"nearest_player\",\"combat\":true,\"range\":32}",
                                "AI override; template only")),
                "mob_spawn(template=\"raid_zombie\", location={\"x\":0,\"y\":64,\"z\":0}, count=3, tag=\"adds\")");
        command("particles", "Shows a particle effect.",
                List.of(text("particle_id", "Particle ID, e.g. minecraft:flame").values("particle"),
                        position("location", "Center")),
                List.of(value("offset", OFFSET, "Spread"), value("count", "1", "Particles (default 1)"),
                        value("speed", "0", "Speed (default 0)")),
                "particles(particle_id=\"minecraft:flame\", location={\"x\":0,\"y\":65,\"z\":0}, count=20)");
        command("portal", "Places or removes a named copy of a saved portal preset.",
                List.of(text("portal", "Portal preset ID, e.g. cwp:gate").values("portal"),
                        value("action", "\"add\"", "add or delete"),
                        value("location", "\"main\"", "Name of this copy (default main)"),
                        position("position", "Where the copy goes; add only")),
                List.of(position("target", "Destination for Coordinates portals; add only")),
                "portal(portal=\"cwp:gate\", action=\"add\", location=\"arena\", position={\"x\":20,\"y\":64,\"z\":10})");
		command("finish_instance", "Clears participants' raid checkpoints. The instance closes when empty, even if scripts are still running.",
				List.of(), List.of(position("spawn", "Optional new instance spawn and rejoin point")),
				"finish_instance(spawn={\"x\":-15,\"y\":64,\"z\":-10})");
        command("redstone_sensor", "Legacy named redstone check. Use powered(x, y, z) instead.",
                List.of(text("name", "Sensor name"), position("position", "Block to check")), List.of(),
                "redstone_sensor(name=\"lever\", position={\"x\":10,\"y\":64,\"z\":5})");
        command("reward_currency", "Credits each player in the instance; the currency must be player scoped.",
                List.of(text("currency", "Currency ID, e.g. cwp:coins").values("currency"),
                        value("amount", "1", "Positive whole number")),
                List.of(), "reward_currency(currency=\"cwp:coins\", amount=100)");
        command("reward_item", "Gives each player in the instance an item.",
                List.of(text("item", "Item ID, e.g. minecraft:diamond").values("item"), value("count", "1", "1 to 2304")),
                List.of(), "reward_item(item=\"minecraft:diamond\", count=1)");
        command("reward_loot", "Rolls a chest loot table separately for each player in the instance.",
                List.of(text("loot_table", "Loot table ID").values("loot_table")),
                List.of(value("rolls", "1", "1 to 32 (default 1)")),
                "reward_loot(loot_table=\"minecraft:chests/simple_dungeon\", rolls=1)");
        command("sound", "Plays a sound at the location, or to every player in the world when there is no location "
                        + "(for music).",
                List.of(text("sound_id", "Sound ID").values("sound")),
                List.of(position("location", "Where it plays; omit to play it to everyone")),
                "sound(sound_id=\"minecraft:entity.experience_orb.pickup\", location={\"x\":0,\"y\":65,\"z\":0})");
        command("sound_stop", "Stops a sound the scripts played in this world, or all of them. Wipes and "
                        + "encounter_end() stop them too.", List.of(),
                List.of(text("sound_id", "Sound to stop; omit to stop every script sound").values("sound")),
                "sound_stop(sound_id=\"minecraft:music_disc.pigstep\")");
        command("trigger", "Emits a script event.", List.of(text("event", "Event ID, e.g. cwp:plate_ready")),
                List.of(), "trigger(event=\"cwp:plate_ready\")");
        command("variable", "Sets or increments a run variable.",
                List.of(text("name", "Variable name").values("script_var")),
                List.of(value("operation", "\"set\"", "set or increment (default set)"),
                        value("value", "\"0\"", "Quoted whole number or \"true\"/\"false\" (default 0)")),
                "variable(name=\"charge\", operation=\"increment\", value=\"1\")");
        command("wait", "Pauses for a duration, or until a condition is true.",
                List.of(text("duration", "40t, 5s, 500ms or 2m").oneOf("wait"),
                        text("condition", "Waits until it is true").oneOf("wait")),
                List.of(value("timeout", "\"5m\"", "With condition: cancels the run when reached")),
                "wait(condition=\"dead(\\\"boss\\\")\", timeout=\"5m\")");
        command("wipe", "Fails the encounter; it resets after 5 seconds unless encounter_end() runs first.",
                List.of(), List.of(), "wipe()");

        condition("box_triggers", "Times an event box went from empty to occupied.",
                List.of(text("box", "Event box name").values("script_box")), "box_triggers(\"plate\") >= 2");
        condition("dead", "True once every mob spawned with this tag is dead.",
                List.of(text("tag", "mob_spawn tag").values("script_tag")), "dead(\"boss\")");
        condition("health_percent", "Combined health of living mobs with this tag, from 0 to 1.",
                List.of(text("tag", "mob_spawn tag").values("script_tag")), "health_percent(\"boss\") <= 0.5");
        condition("kills", "Mob deaths by tag, template ID, entity ID or MobForge tag.",
                List.of(text("id", "Tag or ID").values("script_tag")), "kills(\"adds\") >= 6");
        condition("phase", "Phase of the first living MobForge mob with this tag.",
                List.of(text("tag", "mob_spawn tag").values("script_tag")), "phase(\"boss\") == \"enraged\"");
        condition("players_in", "Players or matching mobs inside an event box.",
                List.of(text("box", "Event box name").values("script_box")), "players_in(\"plate\") >= 2");
        condition("portal_used", "Successful uses of a portal in this run.",
                List.of(text("portal", "Portal ID").values("portal")), "portal_used(\"cwp:gate\") >= 1");
        condition("powered", "Redstone power at block coordinates.",
                List.of(value("x", "0", "Block X"), value("y", "64", "Block Y"), value("z", "0", "Block Z")),
                "powered(10, 64, 5)");
        condition("var", "A run variable; unset reads as zero.",
                List.of(text("name", "Variable name").values("script_var")), "var(\"charge\") >= 3");
    }
}
