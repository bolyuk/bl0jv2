package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// privilege rings (kernel/user) and syscall() - the trap gate user-mode
// code uses to reach kernel functionality at all. Every core starts
// privileged (kernel mode), matching every pre-existing test/program's
// behavior unchanged; only dropToUserMode() opts a core out, and there is
// deliberately no way back up except through invokeAsTrap() (a fired
// interrupt or a syscall), mirroring real hardware's trap-gate-only ring
// transitions.
class Bl0jv2_PrivilegeTest {

    @Test
    void coresStartPrivileged() {
        assertEquals("true", run("print isPrivileged();"));
    }

    @Test
    void dropToUserModeFlipsIsPrivileged() {
        assertEquals("true|false", run(
                "print isPrivileged() + '|'; dropToUserMode(); print isPrivileged();"));
    }

    @Test
    void droppingPrivilegeTwiceThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "dropToUserMode(); dropToUserMode();"));
    }

    // --- privileged instructions reject user mode ---

    @Test
    void reserveFromUserModeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("dropToUserMode(); reserve(0, 4);"));
    }

    @Test
    void registerHandlerFromUserModeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "def handler(v) { } dropToUserMode(); registerHandler(handler, 1, 1);"));
    }

    @Test
    void dispatchFromUserModeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "def task(v) { } dropToUserMode(); dispatch(task, 1, 0);",
                vm -> vm.set_core_count(2)));
    }

    @Test
    void disableAndEnableInterruptsFromUserModeThrow() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("dropToUserMode(); disableInterrupts();"));
        assertThrows(Bl0j_VM_Exception.class, () -> run("dropToUserMode(); enableInterrupts();"));
    }

    // peek/poke stay available from user mode - see requirePrivileged's own
    // doc for why (no paging yet, so gating raw memory would be theater)
    @Test
    void peekAndPokeStayAvailableFromUserMode() {
        assertEquals("42", run("dropToUserMode(); poke32(0, 42); print peek32(0);"));
    }

    // --- syscall(): the gate itself is never privileged ---

    @Test
    void syscallInvokesTheRegisteredHandlerSynchronouslyAndReturnsItsResult() {
        assertEquals("21", run(
                "def doubleIt(v) { return v * 2; } " +
                "registerHandler(doubleIt, 7, 1); " +
                "print syscall(7, 10) + 1;"));
    }

    @Test
    void syscallToAnUnregisteredVectorThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("syscall(9, 0);"));
    }

    @Test
    void syscallToAVectorOutOfRangeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("syscall(256, 0);"));
    }

    // the whole point of syscall(): user-mode code can still reach it, even
    // though registerHandler()/dispatch()/etc themselves are kernel-only
    @Test
    void syscallIsCallableFromUserMode() {
        assertEquals("5", run(
                "def echo(v) { return v; } " +
                "registerHandler(echo, 1, 1); " +
                "dropToUserMode(); " +
                "print syscall(1, 5);"));
    }

    // real IRET semantics: the handler runs elevated (so it can do
    // privileged work on the caller's behalf), but once it returns, the
    // caller is back to exactly the privilege level it had before -
    // elevation never leaks past the handler
    @Test
    void syscallHandlerRunsPrivilegedButCallerStaysUserModeAfterwards() {
        String out = run(
                "def kernelWork(v) { reserve(0, 4); return isPrivileged(); } " +
                "registerHandler(kernelWork, 1, 1); " +
                "dropToUserMode(); " +
                "print syscall(1, 0); " + // true: elevated for the handler's own body
                "print '|' + isPrivileged(); " + // false: restored after the trap returns
                "try { reserve(4, 4); print '|unreachable'; } catch (e) { print '|blocked'; }");

        assertEquals("true|false|blocked", out);
    }

    // a fired hardware interrupt goes through the same trap gate as
    // syscall() (invokeAsTrap) - it must elevate for the handler's
    // duration and restore afterward too, not just leave the whole machine
    // in kernel mode once triggered
    @Test
    void firedInterruptElevatesForTheHandlersDurationAndRestoresAfter() {
        String out = run(
                "def onTick(v) { print isPrivileged(); } " +
                "registerHandler(onTick, 1, 5); " +
                "dropToUserMode(); " +
                "raiseInterrupt(1); " +
                "print '|' + isPrivileged();",
                vm -> vm.set_interrupt_poll_interval(1));

        assertEquals("true|false", out);
    }
}
