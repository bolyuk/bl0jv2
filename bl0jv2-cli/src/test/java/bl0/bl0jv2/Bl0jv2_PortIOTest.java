package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// in8/16/32, out8/16/32 - a second, port-addressed bus (0-65535),
// deliberately separate from peek/poke's raw-memory arena. See PortIO's own
// doc for why: real x86 has two independent address spaces, and code that
// means "port 0" should never alias "byte 0 of RAM".
class Bl0jv2_PortIOTest {

    @Test
    void out8AndIn8RoundTrip() {
        assertEquals("200", run("out8(0, 200); print in8(0);"));
    }

    @Test
    void out16AndIn16RoundTrip() {
        assertEquals("40000", run("out16(0, 40000); print in16(0);"));
    }

    @Test
    void out32AndIn32RoundTrip() {
        assertEquals("300000000", run("out32(0, 300000000); print in32(0);"));
    }

    @Test
    void differentWidthsAtAdjacentPortsDoNotOverlap() {
        assertEquals("200|40000|300000000", run(
                "out8(0, 200); out16(1, 40000); out32(4, 300000000); " +
                "print in8(0) + '|' + in16(1) + '|' + in32(4);"));
    }

    // every port is "live" (backed by a flat array) - there is no
    // reservation/registration step, unlike RawMemory's reserve()
    @Test
    void anUnwrittenPortReadsAsZero() {
        assertEquals("0", run("print in8(12345);"));
    }

    @Test
    void portsAndRawMemoryAreGenuinelySeparateAddressSpaces() {
        assertEquals("1|0", run("poke8(0, 1); print peek8(0) + '|' + in8(0);"));
    }

    @Test
    void readingPastThePortSpaceThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("print in8(70000);"));
    }

    @Test
    void writingPastThePortSpaceThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("out8(70000, 1);"));
    }

    // port I/O is a privileged instruction, same as reserve()/
    // registerHandler()/dispatch() - see Bl0jv2_PrivilegeTest for the fuller
    // ring-gating picture
    @Test
    void portIOFromUserModeThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("dropToUserMode(); out8(0, 1);"));
        assertThrows(Bl0j_VM_Exception.class, () -> run("dropToUserMode(); in8(0);"));
    }

    // hostPortWrite(): the host-side (Java) counterpart to raiseInterrupt() -
    // simulates a real device (e.g. a keyboard controller) placing a byte on
    // its own data port from OUTSIDE the running program, for in8() to read.
    // See Bl0jv2_KeyboardTest for the fuller "device write + raiseInterrupt()"
    // pairing this is meant to support.
    @Test
    void hostPortWriteIsVisibleToIn8() {
        assertEquals("42", run("print in8(5);", vm -> vm.hostPortWrite(5, 1, 42)));
    }

    @Test
    void hostPortWriteRespectsWidth() {
        assertEquals("40000", run("print in16(5);", vm -> vm.hostPortWrite(5, 2, 40000)));
    }
}
