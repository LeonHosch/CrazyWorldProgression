package ekuzo.crazyworldprogression.admin;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the most recent server log lines for the admin panel's Logs page: warnings and errors from everything,
 * plus normal messages from CWP, MobForge and Echelon. Lines live in memory only.
 */
public final class AdminLog {
    private static final int CAPACITY = 500;
    private static final Deque<Line> LINES = new ArrayDeque<>();
    private static boolean installed;

    private AdminLog() { }

    /** One captured log line. */
    public record Line(Instant time, String level, String source, String message) {
        public boolean problem() { return level.equals("WARN") || level.equals("ERROR") || level.equals("FATAL"); }
    }

    /** Attaches the capture to the root logger once. */
    public static synchronized void install() {
        if (installed) return;
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            AbstractAppender appender = new AbstractAppender("CwpAdminLog", null, null, true, Property.EMPTY_ARRAY) {
                @Override public void append(LogEvent event) { record(event); }
            };
            appender.start();
            context.getConfiguration().getRootLogger().addAppender(appender, Level.INFO, null);
            context.updateLoggers();
            installed = true;
        } catch (RuntimeException | LinkageError exception) {
            // Another logging setup; the Logs page then stays empty instead of breaking startup.
        }
    }

    private static void record(LogEvent event) {
        Level level = event.getLevel();
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        String lower = logger.toLowerCase(Locale.ROOT);
        boolean ours = lower.contains("crazy-world-progression") || lower.contains("crazyworldprogression")
                || lower.contains("mobforge") || lower.contains("echelon");
        if (!ours && level.isLessSpecificThan(Level.WARN)) return;
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        Throwable thrown = event.getThrown();
        if (thrown != null) message += " — " + thrown.getClass().getSimpleName()
                + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
        Line line = new Line(Instant.ofEpochMilli(event.getTimeMillis()), level.name(), shortName(logger), message);
        synchronized (LINES) {
            LINES.addFirst(line);
            while (LINES.size() > CAPACITY) LINES.removeLast();
        }
    }

    private static String shortName(String logger) {
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    /** Newest first. */
    public static List<Line> lines(boolean problemsOnly, int limit) {
        List<Line> result = new ArrayList<>();
        synchronized (LINES) {
            for (Line line : LINES) {
                if (problemsOnly && !line.problem()) continue;
                result.add(line);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    public static void clear() {
        synchronized (LINES) { LINES.clear(); }
    }
}
