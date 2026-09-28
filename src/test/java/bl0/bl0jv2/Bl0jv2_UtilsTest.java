package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

// dump_file() disassembles a compiled program back to a readable listing -
// used by the CLI's -d flag, never exercised by Bl0jv2_TestRunner (which
// only ever runs bytecode, never dumps it), so bugs here go unnoticed
// unless tested directly
class Bl0jv2_UtilsTest {

    private static String dump(String source) throws Exception {
        byte[] bytecode = Bl0jv2_TestRunner.compile(source);
        StringWriter sw = new StringWriter();
        Bl0jv2_Utils.dump_file(bytecode, new PrintWriter(sw));
        return sw.toString();
    }

    @Test
    void dumpsAPlainProgramWithoutThrowing() throws Exception {
        String output = dump("x = 1 + 2; print x;");
        assertTrue(output.contains("HALT"));
    }

    // the constant pool's CLASS entry used to have no case at all in
    // dump_file's switch, so any program with a class definition crashed
    // dump_file with "Unknown const type: 7" instead of disassembling
    @Test
    void dumpsAProgramWithAClassWithoutThrowing() throws Exception {
        String output = dump(
                "def class Point { field x; field y; " +
                "  static field count; " +
                "  def init(x, y) { this.x = x; this.y = y; } " +
                "  static def origin() { return new Point(0, 0); } " +
                "} " +
                "p = new Point(1, 2); print p.x;");

        assertTrue(output.contains("CLASS"));
        assertTrue(output.contains("Point"));
    }

    // GET_STATIC_FIELD/SET_STATIC_FIELD used to fall through to the
    // switch's default branch and print as "UNKNOWN"
    @Test
    void dumpsStaticFieldOpcodesByName() throws Exception {
        String output = dump(
                "def class Counter { static field total; } " +
                "Counter.total = 1; print Counter.total;");

        assertTrue(output.contains("GET_STATIC_FIELD"));
        assertTrue(output.contains("SET_STATIC_FIELD"));
        assertTrue(!output.contains("UNKNOWN"));
    }

    @Test
    void dumpsClosureOpcodesByName() throws Exception {
        String output = dump(
                "def makeCounter() { count = 0; return () -> { count = count + 1; return count; }; } " +
                "next = makeCounter(); print next();");

        assertTrue(output.contains("MAKE_CELL"));
        assertTrue(output.contains("CELL_GET"));
        assertTrue(output.contains("CELL_SET"));
        assertTrue(output.contains("MAKE_CLOSURE"));
        assertTrue(!output.contains("UNKNOWN"));
    }

    // read() used to be its own dedicated READ opcode; it now goes through
    // CALL_NATIVE like every other native method, so its own opcode name
    // should never appear in a dump again
    @Test
    void readCompilesToCallNativeNotItsOwnOpcode() throws Exception {
        String output = dump("x = read(); print x;");

        assertTrue(output.contains("CALL_NATIVE"));
        assertTrue(!output.contains("READ "));
        assertTrue(!output.contains("UNKNOWN"));
    }
}
