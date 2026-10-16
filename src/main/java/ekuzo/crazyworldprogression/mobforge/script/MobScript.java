package ekuzo.crazyworldprogression.mobforge.script;

import ekuzo.crazyworldprogression.mobforge.template.MobTemplate;
import net.minecraft.resources.Identifier;

import java.util.List;

public record MobScript(String id, List<Step> steps) {
	public MobScript { steps = steps == null ? List.of() : List.copyOf(steps); }

	public sealed interface Step permits SpawnStep, SleepStep, SpawnEntityStep, WaitUntilStep, MobCastStep, SetPhaseStep, TriggerStep { }
	public record Position(double x, double y, double z) { }
	public record Offset(double x, double y, double z) {
		public static final Offset NONE = new Offset(0, 0, 0);
	}
	public record SpawnStep(String template, Position location, Offset offset, int count, MobTemplate.Ai aiOverride) implements Step {
		public SpawnStep { offset = offset == null ? Offset.NONE : offset; }
	}
	public record SleepStep(long ticks) implements Step { }
	public record SpawnEntityStep(Identifier id, String at, String saveAs) implements Step { }
	public record WaitUntilStep(String condition, long timeoutTicks) implements Step { }
	public record MobCastStep(String mob, Identifier ability) implements Step { }
	public record SetPhaseStep(String mob, String phase) implements Step { }
	public record TriggerStep(Identifier event) implements Step { }
}
