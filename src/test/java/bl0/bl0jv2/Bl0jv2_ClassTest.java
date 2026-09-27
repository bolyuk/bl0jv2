package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bl0jv2_ClassTest {

    private static final String POINT =
            "def class Point { " +
            "  field x; field y; " +
            "  def init(x, y) { this.x = x; this.y = y; } " +
            "  def sum() { return this.x + this.y; } " +
            "  def moveBy(dx, dy) { this.x = this.x + dx; this.y = this.y + dy; } " +
            "} ";

    @Test
    void constructorSetsFields() {
        assertEquals("3|4", run(POINT + "p = new Point(3, 4); print p.x + '|' + p.y;"));
    }

    @Test
    void methodReadsFieldsViaThis() {
        assertEquals("7", run(POINT + "p = new Point(3, 4); print p.sum();"));
    }

    @Test
    void methodMutatesFieldsViaThis() {
        assertEquals("4|5", run(POINT + "p = new Point(3, 4); p.moveBy(1, 1); print p.x + '|' + p.y;"));
    }

    @Test
    void fieldAssignmentFromOutsideAMethod() {
        assertEquals("100", run(POINT + "p = new Point(3, 4); p.x = 100; print p.x;"));
    }

    @Test
    void instancesAreIndependent() {
        assertEquals("2|1", run(
                "def class Counter { field count; def init() { this.count = 0; } " +
                "  def inc() { this.count = this.count + 1; return this.count; } } " +
                "a = new Counter(); b = new Counter(); " +
                "a.inc(); a.inc(); b.inc(); " +
                "print a.count + '|' + b.count;"));
    }

    @Test
    void classWithoutInitDefaultsFieldsToNil() {
        assertEquals("nil", run("def class Empty { field value; } e = new Empty(); print e.value;"));
    }

    @Test
    void typeOfReportsTheClassName() {
        assertEquals("Point", run(POINT + "p = new Point(1, 2); print typeOf(p);"));
    }

    @Test
    void printingShowsClassNameAndFields() {
        assertEquals("Point{x: 1, y: 2}", run(POINT + "p = new Point(1, 2); print p;"));
    }

    @Test
    void instanceCanBePassedToARegularFunction() {
        assertEquals("7", run(POINT + "def total(p) { return p.sum(); } print total(new Point(3, 4));"));
    }

    @Test
    void methodCanConstructAndReturnAnotherInstanceOfItsOwnClass() {
        assertEquals("Point{x: 11, y: 22}", run(
                POINT.replace("def moveBy(dx, dy) { this.x = this.x + dx; this.y = this.y + dy; }",
                        "def add(other) { return new Point(this.x + other.x, this.y + other.y); }") +
                "p3 = new Point(1, 2).add(new Point(10, 20)); print p3;"));
    }

    @Test
    void accessingUnknownFieldThrowsClearError() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(POINT + "p = new Point(1, 2); print p.bogus;"));
    }

    @Test
    void callingUnknownMethodThrowsClearError() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(POINT + "p = new Point(1, 2); p.bogus();"));
    }

    @Test
    void unknownFieldErrorIsCatchable() {
        assertEquals("true", run(POINT +
                "p = new Point(1, 2); " +
                "try { x = p.bogus; } catch (e) { print isErr(e); }"));
    }

    @Test
    void twoClassesCanHaveMethodsWithTheSameName() {
        assertEquals("A|B", run(
                "def class Foo { def name() { return 'A'; } } " +
                "def class Bar { def name() { return 'B'; } } " +
                "print new Foo().name() + '|' + new Bar().name();"));
    }
}
