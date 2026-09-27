package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.exceptions.Bl0j_LexerException;
import bl0.bl0jv2.exceptions.Bl0j_ParserException;
import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bl0jv2_EndToEndTest {

    // --- arithmetic ---

    @Test
    void addition() {
        assertEquals("3", run("print 1 + 2;"));
    }

    @Test
    void operatorPrecedence() {
        assertEquals("7", run("print 1 + 2 * 3;"));
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertEquals("9", run("print (1 + 2) * 3;"));
    }

    @Test
    void subtraction() {
        assertEquals("-1", run("print 4 - 5;"));
    }

    @Test
    void integerDivisionTruncates() {
        assertEquals("3", run("print 10 / 3;"));
    }

    @Test
    void remainder() {
        assertEquals("1", run("print 10 % 3;"));
    }

    @Test
    void divisionByZeroThrows() {
        Bl0j_VM_Exception ex = assertThrows(Bl0j_VM_Exception.class, () -> run("print 10 / 0;"));
        assertTrue(ex.getMessage().contains("division by zero"));
    }

    @Test
    void remainderByZeroThrows() {
        Bl0j_VM_Exception ex = assertThrows(Bl0j_VM_Exception.class, () -> run("print 10 % 0;"));
        assertTrue(ex.getMessage().contains("division by zero"));
    }

    @Test
    void unaryMinus() {
        assertEquals("-5", run("print -5;"));
    }

    // --- strings ---

    @Test
    void stringConcatenation() {
        assertEquals("x=5", run("print 'x=' + 5;"));
    }

    @Test
    void stringConcatenatedWithBoolean() {
        assertEquals("ok=true", run("print 'ok=' + true;"));
    }

    @Test
    void stringRepetition() {
        assertEquals("ababab", run("print 'ab' * 3;"));
    }

    // --- booleans / comparisons ---

    @Test
    void equalityTrue() {
        assertEquals("true", run("print 5 == 5;"));
    }

    @Test
    void equalityFalse() {
        assertEquals("false", run("print 5 == 6;"));
    }

    @Test
    void inequality() {
        assertEquals("true", run("print 5 != 6;"));
    }

    @Test
    void lessThan() {
        assertEquals("true", run("print 3 < 5;"));
    }

    @Test
    void greaterThan() {
        assertEquals("false", run("print 3 > 5;"));
    }

    @Test
    void logicalNot() {
        assertEquals("false", run("print !true;"));
    }

    @Test
    void ternaryTakesTrueBranch() {
        assertEquals("yes", run("print 5 > 3 ? 'yes' : 'no';"));
    }

    @Test
    void ternaryTakesFalseBranch() {
        assertEquals("no", run("print 5 < 3 ? 'yes' : 'no';"));
    }

    // --- nil ---

    @Test
    void nilPrintsAsNil() {
        assertEquals("nil", run("x = nil; print x;"));
    }

    // --- variables ---

    @Test
    void variableAssignmentAndReuse() {
        assertEquals("15", run("x = 10; x = x + 5; print x;"));
    }

    // --- postfix increment/decrement ---

    @Test
    void postfixIncrement() {
        assertEquals("6", run("i = 5; i++; print i;"));
    }

    @Test
    void postfixDecrement() {
        assertEquals("4", run("i = 5; i--; print i;"));
    }

    // --- if / else ---

    @Test
    void ifTakesThenBranch() {
        assertEquals("big", run("x = 10; if (x > 5) { print 'big'; } else { print 'small'; }"));
    }

    @Test
    void ifTakesElseBranch() {
        assertEquals("small", run("x = 3; if (x > 5) { print 'big'; } else { print 'small'; }"));
    }

    @Test
    void ifWithoutElseSkipsWhenFalse() {
        assertEquals("", run("x = 3; if (x > 5) { print 'big'; }"));
    }

    // --- while ---

    @Test
    void whileLoopAccumulates() {
        assertEquals("10", run(
                "i = 0; sum = 0;" +
                "while (i < 5) { sum = sum + i; i = i + 1; }" +
                "print sum;"));
    }

    // --- functions ---

    @Test
    void simpleFunctionCall() {
        assertEquals("5", run(
                "def add(a, b) { return a + b; }" +
                "print add(2, 3);"));
    }

    @Test
    void recursiveFunctionCall() {
        assertEquals("55", run(
                "def fib(n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); }" +
                "print fib(10);"));
    }

    @Test
    void functionWithNoArgs() {
        assertEquals("42", run(
                "def answer() { return 42; }" +
                "print answer();"));
    }

    // --- println (prepends newline, per native println implementation) ---

    @Test
    void printlnPrependsNewlineBeforeEachValue() {
        assertEquals("\na\nb", run("println 'a'; println 'b';"));
    }

    // --- regression: a ';'-terminated assignment directly followed by a
    // print/if/while statement used to fail to parse, since the leftover
    // ';' was checked against before being consumed (see Bl0jv2_Parser) ---

    @Test
    void semicolonTerminatedAssignmentFollowedByPrintParsesCorrectly() {
        assertEquals("10", run("x = 10; print x;"));
    }

    @Test
    void semicolonTerminatedAssignmentFollowedByIfParsesCorrectly() {
        assertEquals("big", run("x = 10; if (x > 5) { print 'big'; } else { print 'small'; }"));
    }

    @Test
    void semicolonTerminatedAssignmentFollowedByWhileParsesCorrectly() {
        assertEquals("10", run(
                "i = 0; sum = 0; " +
                "while (i < 5) { sum = sum + i; i = i + 1; } " +
                "print sum;"));
    }

    @Test
    void fullyTerminatedFunctionBodyParsesCorrectly() {
        assertEquals("5", run(
                "def add(a, b) { return a + b; } " +
                "print add(2, 3);"));
    }

    // --- regression: identifiers could not contain digits after the first
    // character (Bl0jv2_Lexer only allowed letters/underscore to continue) ---

    @Test
    void identifierMayContainDigits() {
        assertEquals("5", run("x1 = 5; print x1;"));
    }

    // --- regression: <= and >= lexed/parsed fine but crashed the compiler
    // with an unhandled "Unknown op" RuntimeException ---

    @Test
    void lessEqualsTrueAtBoundary() {
        assertEquals("true", run("print 5 <= 5;"));
    }

    @Test
    void lessEqualsFalseAboveBoundary() {
        assertEquals("false", run("print 6 <= 5;"));
    }

    @Test
    void greaterEqualsTrueAtBoundary() {
        assertEquals("true", run("print 5 >= 5;"));
    }

    @Test
    void greaterEqualsFalseBelowBoundary() {
        assertEquals("false", run("print 4 >= 5;"));
    }

    // --- regression: OperatorTable silently swapped mismatched operands to
    // fit however the operator happened to be registered, so '+' produced
    // the same (wrong) result regardless of source-code operand order ---

    @Test
    void concatenationIsOrderSensitive() {
        assertEquals("5 apples", run("print 5 + ' apples';"));
        assertEquals("apples 5", run("print 'apples ' + 5;"));
    }

    @Test
    void stringRepetitionWorksInEitherOperandOrder() {
        assertEquals("ababab", run("print 'ab' * 3;"));
        assertEquals("ababab", run("print 3 * 'ab';"));
    }

    // --- regression: an unterminated string literal was silently truncated
    // instead of being reported as a lexer error ---

    @Test
    void unterminatedStringThrowsLexerException() {
        assertThrows(Bl0j_LexerException.class, () -> run("print 'unterminated;"));
    }

    // --- '**' was already lexed (Operator.STAR_STAR) and had a reserved
    // opcode slot (OpCodes.RESERVED_0E) set aside for it, but the parser
    // never consumed the token and nothing compiled/executed it ---

    @Test
    void powerOperator() {
        assertEquals("8", run("print 2 ** 3;"));
    }

    @Test
    void powerOperatorWithZeroExponent() {
        assertEquals("1", run("print 5 ** 0;"));
    }

    @Test
    void powerOperatorNegativeExponentThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("print 2 ** -1;"));
    }

    // --- regression: byte-sized instruction/register/address operands used
    // to silently wrap around past 255 (corrupting jump targets) instead of
    // failing to compile ---

    @Test
    void programExceedingByteAddressLimitFailsToCompileInsteadOfCorrupting() {
        StringBuilder sb = new StringBuilder("i = 0; a = 0; while (i < 1) { ");
        for (int i = 0; i < 70; i++)
            sb.append("a = a + 1; ");
        sb.append("i = i + 1; } print a;");

        assertThrows(Bl0j_CompilerException.class, () -> run(sb.toString()));
    }

    // --- regression: an empty-bodied function immediately after another
    // function that itself ended in 'return' would read that other
    // function's trailing RETURN byte and skip emitting its own, falling
    // through into unrelated bytecode when called ---

    @Test
    void emptyFunctionBodyAfterReturningFunctionStillReturnsCleanly() {
        assertEquals("nil", run(
                "def first() { return 1; } " +
                "def second() { } " +
                "print second();"));
    }

    // --- float literals: registers were switched to NaN-boxed longs
    // specifically to make this possible without a second value
    // representation - a raw double just passes through unboxed ---

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

    // --- arrays ---

    @Test
    void arrayLiteralPrints() {
        assertEquals("[1, 2, 3]", run("print [1, 2, 3];"));
    }

    @Test
    void emptyArrayLiteral() {
        assertEquals("[]", run("print [];"));
        assertEquals("0", run("print len([]);"));
    }

    @Test
    void arrayIndexRead() {
        assertEquals("20", run("arr = [10, 20, 30]; print arr[1];"));
    }

    @Test
    void arrayIndexWrite() {
        assertEquals("[10, 99, 30]", run("arr = [10, 20, 30]; arr[1] = 99; print arr;"));
    }

    @Test
    void arrayIndexWriteReturnsAssignedValue() {
        assertEquals("99", run("arr = [1, 2, 3]; print arr[0] = 99;"));
    }

    @Test
    void arrayOutOfBoundsReadThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("arr = [1, 2, 3]; print arr[5];"));
    }

    @Test
    void arrayOutOfBoundsWriteThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("arr = [1, 2, 3]; arr[5] = 1;"));
    }

    @Test
    void nestedArrays() {
        assertEquals("3", run("nested = [[1, 2], [3, 4]]; print nested[1][0];"));
    }

    @Test
    void mixedTypeArrayLiteral() {
        assertEquals("[1, two, 3.0, true, nil]", run("print [1, 'two', 3.0, true, nil];"));
    }

    @Test
    void lenWorksOnStringsToo() {
        assertEquals("5", run("print len('hello');"));
    }

    @Test
    void arrayAsFunctionArgumentAndIterationViaLen() {
        assertEquals("60", run(
                "def sum(a) { " +
                "  total = 0; i = 0; " +
                "  while (i < len(a)) { total = total + a[i]; i = i + 1; } " +
                "  return total; " +
                "} " +
                "print sum([10, 20, 30]);"));
    }

    @Test
    void arrayReturnedFromFunctionAndMutatedByCaller() {
        assertEquals("[1, 1, 1]", run(
                "def make() { return [1, 1, 1]; } " +
                "arr = make(); " +
                "print arr;"));
    }

    @Test
    void lenOnNonArrayNonStringThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("print len(5);"));
    }

    // --- regression: unary '-'/'!' used to mutate the operand's register
    // in place, so if the operand was a bare variable, negating it also
    // silently corrupted the variable itself (y = -x; also changed x) ---

    @Test
    void unaryMinusDoesNotMutateTheSourceVariable() {
        assertEquals("true", run("x = 5; y = -x; print x == 5;"));
    }

    @Test
    void unaryMinusProducesCorrectNegatedValue() {
        assertEquals("-5", run("x = 5; y = -x; print y;"));
    }

    @Test
    void unaryNotLeavesVariableUnchanged() {
        assertEquals("true", run("x = true; y = !x; print x == true;"));
    }

    @Test
    void lenDoesNotMutateTheSourceVariable() {
        assertEquals("[1, 2, 3]", run("arr = [1, 2, 3]; l = len(arr); print arr;"));
    }

    // --- comments ---

    @Test
    void lineCommentIsIgnored() {
        assertEquals("5", run("// this is a comment\nprint 5;"));
    }

    @Test
    void trailingLineCommentIsIgnored() {
        assertEquals("5", run("print 5; // trailing comment"));
    }

    @Test
    void commentedOutStatementDoesNotRun() {
        assertEquals("a", run("// print 'b';\nprint 'a';"));
    }

    @Test
    void commentAtEndOfFileWithNoTrailingNewline() {
        assertEquals("5", run("print 5; // no newline after this"));
    }

    // --- string escape sequences ---

    @Test
    void newlineEscape() {
        assertEquals("a\nb", run("print 'a\\nb';"));
    }

    @Test
    void tabEscape() {
        assertEquals("a\tb", run("print 'a\\tb';"));
    }

    @Test
    void escapedQuoteInsideString() {
        assertEquals("it's", run("print 'it\\'s';"));
    }

    @Test
    void escapedBackslash() {
        assertEquals("a\\b", run("print 'a\\\\b';"));
    }

    @Test
    void unknownEscapeSequenceThrows() {
        assertThrows(Bl0j_LexerException.class, () -> run("print 'a\\qb';"));
    }

    // --- char / toArr ---

    @Test
    void toArrConvertsStringToCharArray() {
        assertEquals("[h, e, l, l, o]", run("print toArr('hello');"));
    }

    @Test
    void toArrElementIsIndexable() {
        assertEquals("h", run("print toArr('hello')[0];"));
    }

    @Test
    void directStringIndexingReturnsChar() {
        assertEquals("e", run("print 'hello'[1];"));
    }

    @Test
    void lenWorksOnCharArray() {
        assertEquals("5", run("print len(toArr('hello'));"));
    }

    @Test
    void toArrDoesNotMutateTheSourceVariable() {
        assertEquals("hello", run("s = 'hello'; c = toArr(s); print s;"));
    }

    @Test
    void charWorksAsFunctionReturnValue() {
        assertEquals("w", run(
                "def firstChar(s) { return toArr(s)[0]; } " +
                "print firstChar('world');"));
    }

    // --- bitwise operators on ints ---

    @Test
    void bitwiseAnd() {
        assertEquals("8", run("print 12 & 10;"));
    }

    @Test
    void bitwiseOr() {
        assertEquals("14", run("print 12 | 10;"));
    }

    @Test
    void bitwiseXor() {
        assertEquals("6", run("print 12 ^ 10;"));
    }

    @Test
    void bitwiseNot() {
        assertEquals("-13", run("print ~12;"));
    }

    @Test
    void bitwiseNotDoesNotMutateTheSourceVariable() {
        assertEquals("5", run("a = 5; b = ~a; print a;"));
    }

    @Test
    void shiftLeft() {
        assertEquals("48", run("print 12 << 2;"));
    }

    @Test
    void shiftRight() {
        assertEquals("3", run("print 12 >> 2;"));
    }

    @Test
    void bitwiseOperatorsBindLooserThanComparison() {
        // matches the classic C precedence gotcha: '|' binds looser than
        // '==', so this needs its own parens around the bitwise part
        assertEquals("true", run("print (1 | 2) == 3;"));
    }

    @Test
    void shiftBindsTighterThanComparisonButLooserThanAddition() {
        assertEquals("true", run("print 1 << 2 + 1 == 8;"));
    }

    // --- short-circuit && / || ---

    @Test
    void andTruthTable() {
        assertEquals("true", run("print true && true;"));
        assertEquals("false", run("print true && false;"));
        assertEquals("false", run("print false && true;"));
        assertEquals("false", run("print false && false;"));
    }

    @Test
    void orTruthTable() {
        assertEquals("true", run("print true || true;"));
        assertEquals("true", run("print true || false;"));
        assertEquals("true", run("print false || true;"));
        assertEquals("false", run("print false || false;"));
    }

    @Test
    void andShortCircuitsAndSkipsRightSide() {
        assertEquals("", run(
                "def sideEffect() { println 'ran'; return true; } " +
                "r = false && sideEffect();"));
    }

    @Test
    void orShortCircuitsAndSkipsRightSide() {
        assertEquals("", run(
                "def sideEffect() { println 'ran'; return true; } " +
                "r = true || sideEffect();"));
    }

    @Test
    void andEvaluatesRightSideWhenLeftIsTrue() {
        assertEquals("\nran", run(
                "def sideEffect() { println 'ran'; return true; } " +
                "r = true && sideEffect();"));
    }

    @Test
    void shortCircuitAndPreventsOutOfBoundsArrayAccess() {
        // without short-circuit this would evaluate arr[i] regardless of
        // the left side and throw, even though i is out of range
        assertEquals("false", run("arr = [1, 2, 3]; i = 10; print i < len(arr) && arr[i] == 99;"));
    }

    @Test
    void bitwiseOrBindsTighterThanAnd() {
        // 'false && se1() | se2()' must parse as 'false && (se1() | se2())'
        // - if '&&' bound tighter than '|', se2() would run regardless of
        // the short-circuit (since '|' itself never short-circuits)
        assertEquals("", run(
                "def se1() { println 'se1'; return true; } " +
                "def se2() { println 'se2'; return true; } " +
                "r = false && se1() | se2();"));
    }

    @Test
    void andBindsTighterThanOr() {
        // 'true || se1() && se2()' must parse as 'true || (se1() && se2())'
        assertEquals("", run(
                "def se1() { println 'se1'; return true; } " +
                "def se2() { println 'se2'; return true; } " +
                "r = true || se1() && se2();"));
    }

    // --- push / pop ---

    @Test
    void pushAppendsElement() {
        assertEquals("[1, 2, 3]", run("arr = [1, 2]; push(arr, 3); print arr;"));
    }

    @Test
    void pushReturnsNil() {
        assertEquals("nil", run("arr = []; print push(arr, 1);"));
    }

    @Test
    void pushGrowsPastInitialCapacity() {
        assertEquals("20", run(
                "arr = []; i = 0; " +
                "while (i < 20) { push(arr, i); i = i + 1; } " +
                "print len(arr);"));
    }

    @Test
    void popRemovesAndReturnsLastElement() {
        assertEquals("3", run("arr = [1, 2, 3]; print pop(arr);"));
    }

    @Test
    void popShrinksTheArray() {
        assertEquals("[1, 2]", run("arr = [1, 2, 3]; pop(arr); print arr;"));
    }

    @Test
    void popFromEmptyArrayThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("arr = []; pop(arr);"));
    }

    @Test
    void pushDoesNotCorruptTheArrayVariable() {
        assertEquals("[1, 2]", run("a = [1]; b = a; push(a, 2); print b;"));
    }

    @Test
    void popDoesNotCorruptTheArrayVariable() {
        assertEquals("[1, 2]", run("arr = [1, 2, 3]; x = pop(arr); print arr;"));
    }

    @Test
    void toArrCanBeWrittenInBl0jv2UsingPushAndLen() {
        // the whole point of push/pop: this needs no compiler intrinsic
        assertEquals("[h, i]", run(
                "def myToArr(s) { " +
                "  result = []; i = 0; " +
                "  while (i < len(s)) { push(result, s[i]); i = i + 1; } " +
                "  return result; " +
                "} " +
                "print myToArr('hi');"));
    }

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
        assertEquals("char", run("print typeOf(toArr('a')[0]);"));
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

    // --- negative indexing ---

    @Test
    void negativeArrayIndexReadsFromTheEnd() {
        assertEquals("40", run("arr = [10, 20, 30, 40]; print arr[-1];"));
        assertEquals("30", run("arr = [10, 20, 30, 40]; print arr[-2];"));
    }

    @Test
    void negativeArrayIndexWrites() {
        assertEquals("[10, 20, 30, 99]", run("arr = [10, 20, 30, 40]; arr[-1] = 99; print arr;"));
    }

    @Test
    void negativeStringIndexReadsFromTheEnd() {
        assertEquals("o", run("print 'hello'[-1];"));
    }

    @Test
    void negativeIndexBeyondStartThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("arr = [1, 2, 3]; print arr[-10];"));
    }

    // --- for loop ---

    @Test
    void forLoopAccumulates() {
        assertEquals("55", run("sum = 0; for (i = 1; i <= 10; i = i + 1) { sum = sum + i; } print sum;"));
    }

    @Test
    void forLoopWithZeroIterationsSkipsBody() {
        assertEquals("0", run("n = 0; for (i = 0; i < 0; i = i + 1) { n = n + 1; } print n;"));
    }

    @Test
    void forLoopVariableIsVisibleAfterTheLoop() {
        // this language has no block scoping, so this matches every other
        // construct (if/while bodies) rather than being for-loop-specific
        assertEquals("3", run("for (i = 0; i < 3; i = i + 1) {} print i;"));
    }

    @Test
    void nestedForLoops() {
        assertEquals("4", run(
                "count = 0; " +
                "for (a = 0; a < 2; a = a + 1) { " +
                "  for (b = 0; b < 2; b = b + 1) { count = count + 1; } " +
                "} " +
                "print count;"));
    }

    @Test
    void forLoopOverArrayUsingLen() {
        assertEquals("60", run(
                "arr = [10, 20, 30]; total = 0; " +
                "for (i = 0; i < len(arr); i = i + 1) { total = total + arr[i]; } " +
                "print total;"));
    }

    @Test
    void forLoopParensAreMandatory() {
        assertThrows(Bl0j_ParserException.class, () -> run("for i = 0; i < 3; i = i + 1 {}"));
    }

    // --- tuples ---

    @Test
    void tupleLiteralPrintsWithParens() {
        assertEquals("(1, 2, 3)", run("print (1, 2, 3);"));
    }

    @Test
    void tupleIndexingAndNegativeIndex() {
        assertEquals("1", run("t = (1, 2, 3); print t[0];"));
        assertEquals("3", run("t = (1, 2, 3); print t[-1];"));
    }

    @Test
    void tupleLen() {
        assertEquals("3", run("print len((1, 2, 3));"));
    }

    @Test
    void typeOfAndIsTupleDistinguishFromArray() {
        assertEquals("tuple", run("print typeOf((1, 2));"));
        assertEquals("true", run("print isTuple((1, 2));"));
        assertEquals("false", run("print isArray((1, 2));"));
    }

    @Test
    void tuplesCompareByContentNotReference() {
        assertEquals("true", run("print (1, 2) == (1, 2);"));
        assertEquals("false", run("print (1, 2) == (1, 3);"));
    }

    @Test
    void tupleIndexAssignmentThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("t = (1, 2, 3); t[0] = 99;"));
    }

    @Test
    void pushOnTupleThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("t = (1, 2, 3); push(t, 4);"));
    }

    @Test
    void functionCanReturnATuple() {
        assertEquals("(2, 5)", run(
                "def minMax(a, b) { if (a < b) { return (a, b); } return (b, a); } " +
                "print minMax(5, 2);"));
    }

    // --- destructuring assignment ---

    @Test
    void destructureFromFunctionReturningTuple() {
        assertEquals("lo=2 hi=5", run(
                "def minMax(a, b) { if (a < b) { return (a, b); } return (b, a); } " +
                "lo, hi = minMax(5, 2); " +
                "print 'lo=' + lo + ' hi=' + hi;"));
    }

    @Test
    void destructureSwapWithoutTempVariable() {
        assertEquals("x=2 y=1", run("x = 1; y = 2; x, y = y, x; print 'x=' + x + ' y=' + y;"));
    }

    @Test
    void destructureFromArrayLiteral() {
        assertEquals("p=10 q=20 r=30", run(
                "p, q, r = [10, 20, 30]; print 'p=' + p + ' q=' + q + ' r=' + r;"));
    }

    @Test
    void destructureFromBareCommaValues() {
        assertEquals("a=100 b=200", run("a, b = 100, 200; print 'a=' + a + ' b=' + b;"));
    }

    @Test
    void destructureArityMismatchThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("m, n = (1, 2, 3);"));
    }

    @Test
    void plainFunctionCallArgumentsAreNotMisreadAsDestructuring() {
        // f(a, b) must still work normally - the destructuring lookahead
        // must not misfire just because a comma-separated identifier list
        // appears before a ')'
        assertEquals("3", run("def add(a, b) { return a + b; } x = 1; y = 2; print add(x, y);"));
    }

    @Test
    void plainArrayLiteralWithIdentifiersIsNotMisreadAsDestructuring() {
        assertEquals("[1, 2]", run("x = 1; y = 2; print [x, y];"));
    }

    @Test
    void singleIdentifierAssignmentStillWorks() {
        assertEquals("5", run("x = 5; print x;"));
    }
}
