package ekuzo.crazyworldprogression.mobforge.script;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Validated control-flow jumps for a flat, editor-friendly timeline. */
final class MobScriptFlow {
    final Map<Integer, Integer> falseJump = new HashMap<>();
    final Map<Integer, Integer> endJump = new HashMap<>();
    final Map<Integer, Integer> loopBack = new HashMap<>();

    static MobScriptFlow compile(List<MobScript.Step> steps) {
        MobScriptFlow flow = new MobScriptFlow();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        for (int i = 0; i < steps.size(); i++) {
            MobScript.Step step = steps.get(i);
            if (step instanceof MobScript.IfStep test) {
                ScriptCondition.validate(test.condition());
                stack.push(new Frame(i, false, -1));
            } else if (step instanceof MobScript.LoopStep loop) {
                ScriptCondition.validate(loop.condition());
                if (loop.maxIterations() < 1 || loop.maxIterations() > 100_000)
                    throw new IllegalArgumentException("Loop max iterations must be 1 to 100000");
                stack.push(new Frame(i, true, -1));
            } else if (step instanceof MobScript.ElseStep) {
                if (stack.isEmpty() || stack.peek().loop || stack.peek().elseIndex >= 0)
                    throw new IllegalArgumentException("Else needs an unmatched If at step " + (i + 1));
                Frame frame = stack.pop();
                flow.falseJump.put(frame.start, i + 1);
                stack.push(new Frame(frame.start, false, i));
            } else if (step instanceof MobScript.EndIfStep) {
                if (stack.isEmpty() || stack.peek().loop)
                    throw new IllegalArgumentException("End if needs an If at step " + (i + 1));
                Frame frame = stack.pop();
                if (frame.elseIndex < 0) flow.falseJump.put(frame.start, i + 1);
                else flow.endJump.put(frame.elseIndex, i + 1);
            } else if (step instanceof MobScript.EndLoopStep) {
                if (stack.isEmpty() || !stack.peek().loop)
                    throw new IllegalArgumentException("End loop needs a Loop at step " + (i + 1));
                Frame frame = stack.pop();
                flow.falseJump.put(frame.start, i + 1);
                flow.loopBack.put(i, frame.start);
            }
        }
        if (!stack.isEmpty()) throw new IllegalArgumentException("Unclosed If or Loop at step " + (stack.peek().start + 1));
        return flow;
    }

    private record Frame(int start, boolean loop, int elseIndex) { }
}
