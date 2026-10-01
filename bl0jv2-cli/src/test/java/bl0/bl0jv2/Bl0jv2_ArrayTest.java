package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bl0jv2_ArrayTest {

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

    @Test
    void lenDoesNotMutateTheSourceVariable() {
        assertEquals("[1, 2, 3]", run("arr = [1, 2, 3]; l = len(arr); print arr;"));
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
    void negativeIndexBeyondStartThrows() {
        assertThrows(Bl0j_VM_Exception.class, () -> run("arr = [1, 2, 3]; print arr[-10];"));
    }
}
