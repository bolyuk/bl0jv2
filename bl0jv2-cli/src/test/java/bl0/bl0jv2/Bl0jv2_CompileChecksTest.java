package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// mistakes the compiler can rule out on its own are compile errors, not
// something a program hits at run time: wrong argument counts to a known
// function / static method / constructor, members no class declares,
// variables that are never assigned
class Bl0jv2_CompileChecksTest {

    private static String compileError(String source) {
        return assertThrows(Bl0j_CompilerException.class, () -> run(source)).getMessage();
    }

    private static final String SHAPE =
            "def class Shape { field w; field h; " +
            "  def init(w, h) { this.w = w; this.h = h; } " +
            "  def area() { return this.w * this.h; } " +
            "  static def unit() { return new Shape(1, 1); } " +
            "  static def scaled(k) { return new Shape(k, k); } } ";

    @Test
    void wrongArgumentCountToAFunctionNamesBothCounts() {
        assertEquals("at top level: function add expects 2 arguments, got 1",
                compileError("def add(a, b) { return a + b; } print add(1);"));
    }

    @Test
    void theErrorSaysWhichFunctionTheCallIsIn() {
        String msg = compileError("def add(a, b) { return a + b; } def g() { return add(1, 2, 3); } print g();");
        assertEquals("in function g: function add expects 2 arguments, got 3", msg);
    }

    @Test
    void wrongArgumentCountToAStaticMethod() {
        assertEquals("at top level: function Shape.scaled expects 1 argument, got 2",
                compileError(SHAPE + "s = Shape.scaled(1, 2);"));
    }

    @Test
    void callingAnInstanceMethodThroughTheClassIsRejected() {
        assertEquals("at top level: Shape.area is an instance method - call it on an instance, not on the class",
                compileError(SHAPE + "x = Shape.area();"));
    }

    @Test
    void callingAMissingStaticMethodIsRejected() {
        assertEquals("at top level: class Shape has no static method 'nope'",
                compileError(SHAPE + "x = Shape.nope();"));
    }

    @Test
    void wrongArgumentCountToAConstructor() {
        assertEquals("at top level: method Shape.init expects 2 arguments, got 1",
                compileError(SHAPE + "s = new Shape(1);"));
    }

    @Test
    void argumentsToAClassWithoutInitAreRejected() {
        assertEquals("at top level: class Box has no init(), so new Box(...) takes no arguments, got 1",
                compileError("def class Box { field v; } b = new Box(5);"));
    }

    @Test
    void thisFieldTypoInsideAMethodIsRejected() {
        assertEquals("in function Shape.area: class Shape has no field 'width'",
                compileError("def class Shape { field w; def area() { return this.width; } }"));
    }

    @Test
    void thisFieldAssignmentTypoInsideAMethodIsRejected() {
        assertEquals("in function Shape.set: class Shape has no field 'ww'",
                compileError("def class Shape { field w; def set(v) { this.ww = v; } }"));
    }

    @Test
    void thisMethodTypoInsideAMethodIsRejected() {
        assertEquals("in function Shape.run: class Shape has no method 'area2'",
                compileError("def class Shape { def area() { return 1; } def run() { return this.area2(); } }"));
    }

    @Test
    void thisMethodWithTheWrongArgumentCountIsRejected() {
        assertEquals("in function Shape.run: method Shape.area expects 0 arguments, got 1",
                compileError("def class Shape { def area() { return 1; } def run() { return this.area(5); } }"));
    }

    @Test
    void aFieldNoClassDeclaresIsRejectedOnAnyReceiver() {
        assertEquals("in function show: no class declares a field 'colour'",
                compileError(SHAPE + "def show(s) { return s.colour; } print show(Shape.unit());"));
    }

    @Test
    void aMethodNoClassDeclaresIsRejectedOnAnyReceiver() {
        assertEquals("in function show: no class declares a method 'paint'",
                compileError(SHAPE + "def show(s) { return s.paint(); } print show(Shape.unit());"));
    }

    @Test
    void aMethodNoClassDeclaresWithThatManyArgumentsIsRejected() {
        String msg = compileError(SHAPE + "def show(s) { return s.area(1, 2); } print show(Shape.unit());");
        assertTrue(msg.contains("no method 'area' takes 2 arguments (declared with 0)"), msg);
    }

    @Test
    void correctCodeStillCompilesAndRuns() {
        assertEquals("6|1|9", run(SHAPE +
                "a = new Shape(2, 3); " +
                "print str(a.area()) + '|' + str(Shape.unit().area()) + '|' + str(Shape.scaled(3).area());"));
    }

    @Test
    void methodsMayCallEachOtherAndReadTheirOwnFields() {
        assertEquals("12", run(
                "def class Rect { field w; field h; def init(w, h) { this.w = w; this.h = h; } " +
                "  def double() { return this.area() * 2; } def area() { return this.w * this.h; } } " +
                "print new Rect(2, 3).double();"));
    }
}
