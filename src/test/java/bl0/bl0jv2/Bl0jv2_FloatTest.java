package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// float literals: registers are NaN-boxed longs specifically to make this
// possible without a second value representation - a raw double just
// passes through unboxed
class Bl0jv2_FloatTest {

    @Test
    void floatLiteralPrints() {
        assertEquals("3.14", run("print 3.14;"));
    }

    @Test
    void floatArithmetic() {
        assertEquals("2.5", run("print 5.0 / 2.0;"));
        assertEquals("0.5", run("print 2.0 - 1.5;"));
        assertEquals("7.5", run("print 2.5 * 3.0;"));
        assertEquals("1.5", run("print 3.5 % 2.0;"));
    }

    @Test
    void mixedIntAndFloatArithmeticPromotesToFloat() {
        assertEquals("5.5", run("print 5 + 0.5;"));
        assertEquals("5.5", run("print 0.5 + 5;"));
    }

    @Test
    void pureIntegerArithmeticStaysIntegerAfterFloatSupportWasAdded() {
        assertEquals("3", run("print 10 / 3;"));
    }

    @Test
    void floatUnaryMinus() {
        assertEquals("-3.14", run("print -3.14;"));
    }

    @Test
    void floatComparisons() {
        assertEquals("true", run("print 1.5 < 2.5;"));
        assertEquals("false", run("print 1.5 > 2.5;"));
        assertEquals("true", run("print 1.5 <= 1.5;"));
    }

    @Test
    void numericEqualityCrossesIntAndFloat() {
        assertEquals("true", run("print 5 == 5.0;"));
        assertEquals("false", run("print 5 == 5.1;"));
    }

    @Test
    void floatDivisionByZeroIsInfinityNotAnError() {
        assertEquals("Infinity", run("print 1.0 / 0.0;"));
    }

    @Test
    void floatPowerHandlesNegativeAndFractionalExponents() {
        assertEquals("0.5", run("print 2.0 ** -1.0;"));
    }

    @Test
    void floatConcatenatesWithStringInEitherOrder() {
        assertEquals("pi=3.14", run("print 'pi=' + 3.14;"));
        assertEquals("3.14=pi", run("print 3.14 + '=pi';"));
    }

    @Test
    void floatWorksAsFunctionArgumentAndReturnValue() {
        assertEquals("6.28", run(
                "def doubleIt(x) { return x * 2.0; } " +
                "print doubleIt(3.14);"));
    }

    @Test
    void floatVariableInWhileLoop() {
        assertEquals("2.5", run(
                "x = 0.5; " +
                "while (x < 2.5) { x = x + 0.5; } " +
                "print x;"));
    }
}
