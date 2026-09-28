package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bl0jv2_ConversionTest {

    // --- type conversions ---

    @Test
    void intTruncatesFloatTowardZero() {
        assertEquals("3", run("print int(3.9);"));
        assertEquals("-3", run("print int(-3.9);"));
    }

    @Test
    void intParsesStringAndBool() {
        assertEquals("42", run("print int('42');"));
        assertEquals("1", run("print int(true);"));
    }

    @Test
    void intOnUnparsableStringThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("print int('not a number');"));
    }

    @Test
    void floatWidensIntAndParsesString() {
        assertEquals("5.0", run("print float(5);"));
        assertEquals("3.14", run("print float('3.14');"));
    }

    @Test
    void strConvertsAnyValue() {
        assertEquals("5", run("print str(5);"));
        assertEquals("true", run("print str(true);"));
        assertEquals("[1, 2]", run("print str([1, 2]);"));
    }

    @Test
    void conversionDoesNotMutateSourceVariable() {
        assertEquals("3.9", run("x = 3.9; y = int(x); print x;"));
    }

    // --- typeOf / isXxx ---

    @Test
    void typeOfReportsEachType() {
        assertEquals("int", run("print typeOf(5);"));
        assertEquals("float", run("print typeOf(3.14);"));
        assertEquals("string", run("print typeOf('x');"));
        assertEquals("bool", run("print typeOf(true);"));
        assertEquals("nil", run("print typeOf(nil);"));
        assertEquals("array", run("print typeOf([1]);"));
        // direct string indexing already yields a char - no need for toArr
        // here (it's a stdlib.bl0 function now, not a language builtin)
        assertEquals("char", run("print typeOf('a'[0]);"));
    }

    @Test
    void isIntDistinguishesFromFloat() {
        assertEquals("true", run("print isInt(5);"));
        assertEquals("false", run("print isInt(5.0);"));
    }

    @Test
    void isXxxPredicates() {
        assertEquals("true", run("print isFloat(5.0);"));
        assertEquals("true", run("print isString('x');"));
        assertEquals("true", run("print isBool(true);"));
        assertEquals("true", run("print isArray([1]);"));
        assertEquals("true", run("print isNil(nil);"));
        assertEquals("false", run("print isNil(5);"));
    }
}
