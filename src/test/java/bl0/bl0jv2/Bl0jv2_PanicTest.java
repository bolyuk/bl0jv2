package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.exceptions.Bl0j_VM_Panic;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// panic(msg): unrecoverable by design - see Bl0j_VM_Panic's javadoc. These
// tests exist specifically to prove the one thing that distinguishes it
// from an ordinary runtime error: an enclosing try/catch never catches it.
class Bl0jv2_PanicTest {

    @Test
    void panicThrows() {
        Bl0j_VM_Panic ex = assertThrows(Bl0j_VM_Panic.class, () -> run("panic('unreachable state');"));
        assertTrue(ex.getMessage().contains("unreachable state"));
    }

    // the whole point: a try/catch that would catch a division-by-zero
    // right next to it does NOT catch a panic
    @Test
    void panicIsNotCaughtByAnEnclosingTryCatch() {
        assertThrows(Bl0j_VM_Panic.class, () ->
                run("try { panic('fatal'); } catch (e) { print 'recovered: ' + e; }"));
    }

    @Test
    void panicInANestedFunctionCallStillEscapesAnOuterTryCatch() {
        assertThrows(Bl0j_VM_Panic.class, () ->
                run("def crash() { panic('deep failure'); } " +
                    "try { crash(); } catch (e) { print 'recovered'; }"));
    }

    // err(msg) is the recoverable counterpart - it produces an ordinary
    // Bl0jError value and never throws at all, unlike panic()
    @Test
    void errDoesNotThrowUnlikePanic() {
        assertEquals("not fatal", run("e = err('not fatal'); print e;"));
    }

    @Test
    void panicMessageIsPrefixed() {
        Bl0j_VM_Panic ex = assertThrows(Bl0j_VM_Panic.class, () -> run("panic('oops');"));
        assertTrue(ex.getMessage().startsWith("panic:"));
    }

    // panic() halts the WHOLE shared machine (see Bl0jv2_jVM's own
    // panicked-flag doc), so it must not be reachable from user mode - a
    // userspace bug should never be able to take the kernel down with it.
    // User-mode code gets an ordinary, catchable Bl0j_VM_Exception instead
    // of an actual panic, the same as any other privileged native it isn't
    // allowed to call (requirePrivileged's own doc).
    @Test
    void panicFromUserModeThrowsAnOrdinaryExceptionInsteadOfPanicking() {
        assertThrows(Bl0j_VM_Exception.class, () ->
                run("dropToUserMode(); panic('should not halt the machine');"));
    }

    @Test
    void panicFromUserModeIsCatchable() {
        assertEquals("recovered", run(
                "dropToUserMode(); " +
                "try { panic('blocked'); } catch (e) { print 'recovered'; }"));
    }
}
