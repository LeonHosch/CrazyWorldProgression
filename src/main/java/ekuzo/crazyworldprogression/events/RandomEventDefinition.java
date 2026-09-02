package ekuzo.crazyworldprogression.events;

import net.minecraft.resources.Identifier;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** Immutable scheduling rules loaded from a dependent mod's random-event YAML file. */
public record RandomEventDefinition(Identifier id, Trigger trigger, EventDuration duration,
                                    TimeRestriction availability) {
    // Reject incomplete definitions before the scheduler can observe them.
    public RandomEventDefinition {
        if (id == null || trigger == null || duration == null || availability == null) {
            throw new IllegalArgumentException("Random event definition values must not be null");
        }
    }

    /** Marker shared by every supported random-roll clock. */
    public sealed interface Trigger permits InGamePhaseTrigger, RealTimeTrigger {
        // Return the independent probability used for one eligible scheduling roll.
        double chance();
    }

    /** Performs one roll when a new Minecraft day or night period begins. */
    public record InGamePhaseTrigger(InGamePhase phase, double chance) implements Trigger {
        // Validate phase and probability at YAML load time.
        public InGamePhaseTrigger {
            if (phase == null) throw new IllegalArgumentException("In-game event phase must not be null");
            validateChance(chance);
        }
    }

    /** Performs one roll after each configured number of real-world seconds while the server is running. */
    public record RealTimeTrigger(long intervalSeconds, double chance) implements Trigger {
        // Reject intervals that could roll repeatedly in the same scheduler tick.
        public RealTimeTrigger {
            if (intervalSeconds < 1L) throw new IllegalArgumentException("Real-time roll interval must be at least one second");
            validateChance(chance);
        }
    }

    /** Selects the half of the 24,000-tick Minecraft day that opens a roll. */
    public enum InGamePhase {
        DAY,
        NIGHT
    }

    /** Describes how long an active event remains active and which clock advances it. */
    public record EventDuration(DurationClock clock, long amount) {
        // Require a positive duration so every started event has a deterministic end.
        public EventDuration {
            if (clock == null) throw new IllegalArgumentException("Event duration clock must not be null");
            if (amount < 1L) throw new IllegalArgumentException("Event duration must be positive");
        }
    }

    /** Chooses between wall-clock seconds and monotonic server game ticks for event expiry. */
    public enum DurationClock {
        REAL_TIME,
        GAME_TIME
    }

    /** Restricts random starts to recurring real-world weekdays and a local time window. */
    public record TimeRestriction(Set<DayOfWeek> weekdays, LocalTime start, LocalTime end, ZoneId timezone) {
        // Copy mutable inputs and require a meaningful schedule.
        public TimeRestriction {
            weekdays = Set.copyOf(weekdays);
            if (weekdays.isEmpty()) throw new IllegalArgumentException("Time restriction weekdays must not be empty");
            if (start == null || end == null || timezone == null) {
                throw new IllegalArgumentException("Time restriction values must not be null");
            }
        }

        // Create an always-available restriction while retaining the source's default time zone.
        public static TimeRestriction unrestricted(ZoneId timezone) {
            return new TimeRestriction(Set.of(DayOfWeek.values()), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, timezone);
        }

        // Test an instant against an ordinary, overnight, or full-day recurring local window.
        public boolean allows(Instant instant) {
            ZonedDateTime local = instant.atZone(timezone);
            LocalTime time = local.toLocalTime();
            if (start.equals(end)) return weekdays.contains(local.getDayOfWeek());
            if (start.isBefore(end)) {
                return weekdays.contains(local.getDayOfWeek()) && !time.isBefore(start) && time.isBefore(end);
            }
            if (weekdays.contains(local.getDayOfWeek()) && !time.isBefore(start)) return true;
            return weekdays.contains(local.minusDays(1L).getDayOfWeek()) && time.isBefore(end);
        }
    }

    // Validate one YAML chance as a finite probability from zero through one.
    private static void validateChance(double chance) {
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
            throw new IllegalArgumentException("Random event chance must be between 0.0 and 1.0");
        }
    }
}
