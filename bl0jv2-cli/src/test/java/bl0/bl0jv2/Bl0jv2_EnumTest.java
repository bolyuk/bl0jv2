package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.exceptions.Bl0j_ParserException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 'enum Name { A, B, C }' desugars to a plain 'def class Name' with one
// static field + one singleton instance per constant (see
// Bl0jv2_Parser's own enum_statement() doc) - these tests exercise the
// resulting runtime behavior, not the desugaring mechanism itself.
class Bl0jv2_EnumTest {

    private static final String COLOR = "enum Color { RED, GREEN, BLUE } ";

    @Test
    void constantPrintsItsOwnNameNotAnOpaqueValue() {
        assertEquals("RED", run(COLOR + "print Color.RED;"));
    }

    @Test
    void constantsHaveDeclarationOrderOrdinals() {
        assertEquals("0|1|2", run(COLOR + "print Color.RED.ordinal + '|' + Color.GREEN.ordinal + '|' + Color.BLUE.ordinal;"));
    }

    @Test
    void sameConstantComparesEqual() {
        assertEquals("true", run(COLOR + "print Color.RED == Color.RED;"));
    }

    @Test
    void differentConstantsCompareUnequal() {
        assertEquals("false", run(COLOR + "print Color.RED == Color.GREEN;"));
    }

    @Test
    void constantIsNotEqualToAPlainStringWithTheSameName() {
        // type safety is the actual point of enum over a bare string
        // constant like tcp.bl0's own TcpConn.state - a typo'd string
        // compares silently false forever; a typo'd enum reference is a
        // compile-time error instead (see unknownConstantIsACompileTimeError)
        assertEquals("false", run(COLOR + "print Color.RED == 'RED';"));
    }

    @Test
    void constantWorksAsASwitchCaseValue() {
        assertEquals("warm", run(COLOR +
                "def describe(c) { switch (c) { case Color.RED { return 'warm'; } default { return 'other'; } } } " +
                "print describe(Color.RED);"));
    }

    @Test
    void twoDifferentEnumsCanCoexistWithNoNameCollision() {
        assertEquals("RED|SMALL", run(COLOR + "enum Size { SMALL, LARGE } " +
                "print Color.RED + '|' + Size.SMALL;"));
    }

    @Test
    void enumAllowsATrailingCommaAfterTheLastConstant() {
        assertEquals("X", run("enum Letter { X, } print Letter.X;"));
    }

    @Test
    void unknownConstantIsACompileTimeError() {
        assertThrows(Bl0j_CompilerException.class, () -> run(COLOR + "print Color.PURPLE;"));
    }

    @Test
    void enumInsideAFunctionBodyIsAParseTimeError() {
        // top-level only, same restriction 'def class' itself already has
        // - see enum_statement()'s own doc on why
        assertThrows(Bl0j_ParserException.class, () -> run(
                "def f() { enum Color { RED } return 1; } print f();"));
    }
}
