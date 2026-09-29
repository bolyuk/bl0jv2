package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// registerHandler()/raiseInterrupt() - cooperative, priority-ordered
// interrupts. set_interrupt_poll_interval(1) makes polling deterministic:
// with it, execute() checks the pending queue before every single
// instruction, so a handler fires on the very next instruction after
// raiseInterrupt() queues it, rather than somewhere within an
// interval-sized window.
class Bl0jv2_InterruptTest {

    @Test
    void registeredHandlerFiresAfterRaiseInterrupt() {
        String out = run(
                "def handler(v) { print 'H' + v; } " +
                "registerHandler(handler, 1, 5); " +
                "raiseInterrupt(1); " +
                "print '|done';",
                vm -> vm.set_interrupt_poll_interval(1));

        assertEquals("H1|done", out);
    }

    // vectors are bound to [0, 256), matching a real x86 IDT's fixed size -
    // this table is also what syscall() reuses (see Bl0jv2_PrivilegeTest),
    // so the bound applies uniformly to registerHandler/raiseInterrupt/syscall
    @Test
    void registeringAVectorOutOfRangeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "def handler(v) { } registerHandler(handler, 256, 1);"));
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "def handler(v) { } registerHandler(handler, -1, 1);"));
    }

    @Test
    void raisingAVectorOutOfRangeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("raiseInterrupt(256);"));
    }

    @Test
    void raisingAnUnregisteredVectorIsSilentlyDropped() {
        String out = run(
                "raiseInterrupt(99); print 'done';",
                vm -> vm.set_interrupt_poll_interval(1));

        assertEquals("done", out);
    }

    // both interrupts are queued before either can fire (two raiseInterrupt
    // calls are a handful of instructions, comfortably under the poll
    // interval used here), so this actually exercises priority ordering
    // rather than FIFO/arrival order
    @Test
    void higherPriorityHandlerFiresBeforeLowerPriorityEvenIfRaisedSecond() {
        String out = run(
                "def low(v) { print 'L'; } " +
                "def high(v) { print 'H'; } " +
                "registerHandler(low, 1, 1); " +
                "registerHandler(high, 2, 100); " +
                "raiseInterrupt(1); " +
                "raiseInterrupt(2); " +
                "i = 0; while (i < 200) { i = i + 1; } " +
                "print '|done';",
                vm -> vm.set_interrupt_poll_interval(50));

        assertEquals("HL|done", out);
    }

    @Test
    void largePollIntervalNeverFiresWithinAShortLoop() {
        String out = run(
                "def handler(v) { print 'H'; } " +
                "registerHandler(handler, 1, 5); " +
                "raiseInterrupt(1); " +
                "i = 0; while (i < 5) { i = i + 1; } " +
                "print 'done';",
                vm -> vm.set_interrupt_poll_interval(1000));

        assertEquals("done", out);
    }

    @Test
    void closureHandlerFiresCorrectly() {
        String out = run(
                "handler = (v) -> { print 'C' + v; }; " +
                "registerHandler(handler, 3, 1); " +
                "raiseInterrupt(3); " +
                "print '|done';",
                vm -> vm.set_interrupt_poll_interval(1));

        assertEquals("C3|done", out);
    }

    // a handler that itself triggers a nested invoke() (a class's own
    // toString()) must not cause reentrant polling - this only needs to
    // complete without throwing/hanging, and the outer program must still
    // run to completion afterward
    @Test
    void handlerTriggeringNestedInvokeDoesNotReenterPolling() {
        String out = run(
                "def class Box { field v; " +
                "  def init(v) { this.v = v; } " +
                "  def toString() { return 'Box(' + this.v + ')'; } " +
                "} " +
                "def handler(v) { b = new Box(v); print str(b); } " +
                "registerHandler(handler, 1, 1); " +
                "raiseInterrupt(1); " +
                "print '|done';",
                vm -> vm.set_interrupt_poll_interval(1));

        assertEquals("Box(1)|done", out);
    }

    // --- disableInterrupts()/enableInterrupts() ---

    @Test
    void disableInterruptsDelaysFiringUntilReEnabled() {
        String out = run(
                "def handler(v) { print 'H'; } " +
                "registerHandler(handler, 1, 1); " +
                "disableInterrupts(); " +
                "raiseInterrupt(1); " +
                "print 'A'; " +
                "enableInterrupts(); " +
                "print 'B';",
                vm -> vm.set_interrupt_poll_interval(1));

        // the interrupt is raised (and stays pending) while masked, so it
        // must not fire until after enableInterrupts() - 'A' unmasked would
        // prove nothing, since a bug here could just as easily fire too
        // early as not at all
        assertEquals("AHB", out);
    }

    // a naive on/off flag (instead of a nesting-safe counter) would let the
    // inner enableInterrupts() re-enable delivery while the outer critical
    // section is still active, producing "HAB" instead - this is the test
    // that actually distinguishes the two designs
    @Test
    void nestedDisableInterruptsStaysMaskedUntilEveryEnableMatches() {
        String out = run(
                "def handler(v) { print 'H'; } " +
                "registerHandler(handler, 1, 1); " +
                "disableInterrupts(); " +
                "disableInterrupts(); " +
                "raiseInterrupt(1); " +
                "enableInterrupts(); " +
                "print 'A'; " +
                "enableInterrupts(); " +
                "print 'B';",
                vm -> vm.set_interrupt_poll_interval(1));

        assertEquals("AHB", out);
    }
}
