package ekuzo.crazyworldprogression.cleanup;

import org.junit.jupiter.api.Test;

import static ekuzo.crazyworldprogression.cleanup.TickBudget.ms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickBudgetTest {
    private static final long TICK = ms(50);

    @Test void budgetIsTickLengthMinusLoadMinusBufferWithinLimits() {
        assertEquals(ms(25), TickBudget.budgetNanos(TICK, ms(15)), "50 - 15 load - 10 buffer");
        assertEquals(ms(2), TickBudget.budgetNanos(TICK, ms(45)), "busy server: the minimum");
        assertEquals(ms(30), TickBudget.budgetNanos(TICK, ms(1)), "idle server: the maximum");
    }

    @Test void scriptsAlwaysGetTheirCapNoMatterHowBusyTheServerIs() {
        long[] spent = new long[3];
        spent[TickBudget.Priority.CREATION.ordinal()] = ms(25);
        long scripts = TickBudget.allowed(ms(2), spent, 0, TickBudget.Priority.SCRIPT);
        assertEquals(ms(30), scripts);
    }

    @Test void creationComesBeforeCleanupAndKeepsRoomForScripts() {
        long[] spent = new long[3];
        long creation = TickBudget.allowed(ms(25), spent, ms(5), TickBudget.Priority.CREATION);
        assertEquals(ms(20), creation, "25 ms budget minus 5 ms scripts usually need");
        spent[TickBudget.Priority.CREATION.ordinal()] = ms(20);
        long cleanup = TickBudget.allowed(ms(25), spent, ms(5), TickBudget.Priority.CLEANUP);
        assertEquals(ms(1), cleanup, "creation used it up: cleanup only gets its floor");
        assertTrue(TickBudget.allowed(ms(25), new long[3], 0, TickBudget.Priority.CLEANUP) == ms(25),
                "with nothing else running, cleanup may use the whole budget");
    }

    @Test void creationAlwaysGetsItsMinimum() {
        long[] spent = new long[3];
        spent[TickBudget.Priority.SCRIPT.ordinal()] = ms(30);
        assertEquals(ms(2), TickBudget.allowed(ms(25), spent, ms(30), TickBudget.Priority.CREATION));
    }
}
