package bl0.bl0jv2;

import bl0.bl0jv2.exceptions.Bl0j_CompilerException;
import bl0.bl0jv2.exceptions.Bl0j_ParserException;
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

    // a name no class declares at all is a compile error; a name some OTHER
    // class declares can't be ruled out at compile time (the receiver's
    // class isn't known), so that one is still a runtime error
    private static final String OTHER = "def class Other { field bogus; def bogus() { return 1; } } ";

    @Test
    void accessingAFieldNoClassDeclaresIsACompileError() {
        assertThrows(Bl0j_CompilerException.class, () -> run(POINT + "p = new Point(1, 2); print p.bogus;"));
    }

    @Test
    void callingAMethodNoClassDeclaresIsACompileError() {
        assertThrows(Bl0j_CompilerException.class, () -> run(POINT + "p = new Point(1, 2); p.bogus();"));
    }

    @Test
    void accessingAnotherClassesFieldThrowsClearError() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(POINT + OTHER + "p = new Point(1, 2); print p.bogus;"));
    }

    @Test
    void callingAnotherClassesMethodThrowsClearError() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(POINT + OTHER + "p = new Point(1, 2); p.bogus();"));
    }

    @Test
    void unknownFieldErrorIsCatchable() {
        assertEquals("true", run(POINT + OTHER +
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

    @Test
    void staticMethodCalledViaClassName() {
        assertEquals("7", run(
                "def class MathUtil { static def max(a, b) { if (a > b) { return a; } return b; } } " +
                "print MathUtil.max(3, 7);"));
    }

    @Test
    void staticFactoryMethodReturnsNewInstance() {
        assertEquals("Point{x: 0, y: 0}", run(
                POINT.replace("def moveBy(dx, dy) { this.x = this.x + dx; this.y = this.y + dy; }",
                        "static def origin() { return new Point(0, 0); }") +
                "print Point.origin();"));
    }

    @Test
    void staticAndInstanceMethodsCoexistOnOneClass() {
        assertEquals("7|3", run(
                POINT.replace("def moveBy(dx, dy) { this.x = this.x + dx; this.y = this.y + dy; }",
                        "static def max(a, b) { if (a > b) { return a; } return b; }") +
                "p = new Point(3, 4); " +
                "print p.sum() + '|' + Point.max(3, 1);"));
    }

    @Test
    void callingUnknownStaticMethodIsACompileTimeError() {
        assertThrows(Bl0j_CompilerException.class, () -> run(
                "def class MathUtil { static def max(a, b) { return a; } } " +
                "print MathUtil.bogus(1, 2);"));
    }

    @Test
    void staticFieldDefaultsToNil() {
        assertEquals("nil", run(
                "def class Counter { static field total; } print Counter.total;"));
    }

    @Test
    void staticFieldReadAfterAssignment() {
        assertEquals("42", run(
                "def class Counter { static field total; } Counter.total = 42; print Counter.total;"));
    }

    @Test
    void staticFieldIsSharedAcrossInstances() {
        assertEquals("0|1|2", run(
                "def class Counter { static field total; field id; " +
                "  def init() { this.id = Counter.total; Counter.total = Counter.total + 1; } } " +
                "Counter.total = 0; " +
                "a = new Counter(); b = new Counter(); " +
                "print a.id + '|' + b.id + '|' + Counter.total;"));
    }

    @Test
    void staticFieldIsSharedBetweenStaticAndInstanceMethods() {
        assertEquals("3", run(POINT +
                "def class Registry { static field count; " +
                "  static def inc() { Registry.count = Registry.count + 1; return Registry.count; } } " +
                "Registry.count = 0; Registry.inc(); Registry.inc(); print Registry.inc();"));
    }

    @Test
    void accessingUnknownStaticFieldIsACompileTimeError() {
        assertThrows(Bl0j_CompilerException.class, () -> run(
                "def class Counter { static field total; } print Counter.bogus;"));
    }

    @Test
    void assigningUnknownStaticFieldIsACompileTimeError() {
        assertThrows(Bl0j_CompilerException.class, () -> run(
                "def class Counter { static field total; } Counter.bogus = 1;"));
    }

    @Test
    void staticAndInstanceFieldsOfTheSameNameAreIndependent() {
        assertEquals("100|7", run(POINT.replace("field x; field y;", "field x; field y; static field x;") +
                "Point.x = 100; " +
                "p = new Point(3, 4); " +
                "print Point.x + '|' + p.sum();"));
    }

    // --- toString() override ---

    private static final String POINT_WITH_TOSTRING =
            "def class Point { field x; field y; " +
            "  def init(x, y) { this.x = x; this.y = y; } " +
            "  def toString() { return '(' + this.x + ', ' + this.y + ')'; } " +
            "} ";

    @Test
    void toStringOverrideIsUsedByPrint() {
        assertEquals("(3, 4)", run(POINT_WITH_TOSTRING + "print new Point(3, 4);"));
    }

    @Test
    void toStringOverrideIsUsedByStringConcatenation() {
        assertEquals("p=(3, 4)", run(POINT_WITH_TOSTRING + "print 'p=' + new Point(3, 4);"));
    }

    @Test
    void toStringOverrideIsUsedByStrConversion() {
        assertEquals("(3, 4)", run(POINT_WITH_TOSTRING + "print str(new Point(3, 4));"));
    }

    @Test
    void toStringOverrideIsUsedForInstancesNestedInAnArray() {
        assertEquals("[(1, 2), (3, 4)]", run(POINT_WITH_TOSTRING +
                "print [new Point(1, 2), new Point(3, 4)];"));
    }

    @Test
    void classesWithoutToStringStillUseTheDefaultFieldDump() {
        assertEquals("Point{x: 1, y: 2}", run(POINT + "print new Point(1, 2);"));
    }

    @Test
    void toStringOwnTryCatchStillWorksCorrectly() {
        assertEquals("safe|still alive", run(
                "def class Bomb { def toString() { try { x = 1 / 0; return 'unreachable'; } catch (e) { return 'safe'; } } } " +
                "print str(new Bomb()) + '|still alive';"));
    }

    // an error thrown inside toString(), called from deep inside native
    // code (str()), must still be catchable by a try/catch that encloses
    // the str() call - and must not leave any handler behind afterward
    @Test
    void errorInsideToStringIsCatchableByTheCallingContext() {
        assertEquals("caught|after", run(
                "def class Bomb { def toString() { return 1 / 0; } } " +
                "b = new Bomb(); " +
                "try { x = str(b); } catch (e) { print 'caught'; } " +
                "print '|after';"));
    }

    @Test
    void errorInsideToStringDoesNotLeaveAStaleHandler() {
        assertThrows(Bl0j_VM_Exception.class, () -> run(
                "def class Bomb { def toString() { return 1 / 0; } } " +
                "b = new Bomb(); " +
                "try { x = str(b); } catch (e) { } " +
                "y = 1 / 0;"));
    }

    // --- equals() override ---

    private static final String POINT_WITH_EQUALS =
            "def class Point { field x; field y; " +
            "  def init(x, y) { this.x = x; this.y = y; } " +
            "  def equals(other) { " +
            "    if (typeOf(other) != 'Point') { return false; } " +
            "    return this.x == other.x && this.y == other.y; " +
            "  } " +
            "} ";

    @Test
    void equalsOverrideComparesByContent() {
        assertEquals("true", run(POINT_WITH_EQUALS + "print new Point(1, 2) == new Point(1, 2);"));
    }

    @Test
    void equalsOverrideDetectsDifferentContent() {
        assertEquals("false", run(POINT_WITH_EQUALS + "print new Point(1, 2) == new Point(3, 4);"));
    }

    @Test
    void equalsOverrideCanGuardAgainstAMismatchedType() {
        assertEquals("false", run(POINT_WITH_EQUALS + "print new Point(1, 2) == 5;"));
    }

    @Test
    void classesWithoutEqualsCompareByReference() {
        assertEquals("false|true", run(POINT +
                "a = new Point(1, 2); b = new Point(1, 2); " +
                "print (a == b) + '|' + (a == a);"));
    }

    // --- field initializers (field x = <literal>;) ---

    @Test
    void fieldDefaultsToItsLiteralInitializerOnNew() {
        assertEquals("42|hi|true|3.5", run(
                "def class Config { field count = 42; field name = 'hi'; field active = true; field ratio = 3.5; } " +
                "c = new Config(); " +
                "print c.count + '|' + c.name + '|' + c.active + '|' + c.ratio;"));
    }

    @Test
    void initCanStillOverrideALiteralDefault() {
        assertEquals("99", run(
                "def class Config { field count = 42; def init(n) { this.count = n; } } " +
                "print new Config(99).count;"));
    }

    @Test
    void explicitNilInitializerBehavesLikeNoInitializer() {
        assertEquals("nil", run("def class Empty { field value = nil; } print new Empty().value;"));
    }

    @Test
    void fieldsWithoutAnInitializerStillDefaultToNil() {
        assertEquals("42|nil", run(
                "def class Config { field count = 42; field name; } " +
                "c = new Config(); print c.count + '|' + c.name;"));
    }

    @Test
    void fieldInitializerRejectsAVariableReference() {
        assertThrows(Bl0j_ParserException.class, () -> run(
                "x = 5; def class Config { field count = x; }"));
    }

    @Test
    void fieldInitializerRejectsANewExpression() {
        assertThrows(Bl0j_ParserException.class, () -> run(
                "def class Config { field inner = new Config(); }"));
    }

    // --- const field ---

    @Test
    void constFieldCanBeSetInsideInit() {
        assertEquals("0x1000", run(
                "def class Register { const field base; def init(base) { this.base = base; } } " +
                "print new Register('0x1000').base;"));
    }

    @Test
    void reassigningAConstFieldFromAnotherMethodIsACompileTimeError() {
        assertThrows(Bl0j_CompilerException.class, () -> run(
                "def class Register { const field base; " +
                "  def init(base) { this.base = base; } " +
                "  def reset() { this.base = 0; } " +
                "}"));
    }

    // documented limitation, not a bug: only 'this.field = ...' is checked
    // (the compiler statically knows which class 'this' belongs to inside
    // one of its own methods). 'obj.field = ...' from outside isn't caught
    // at compile time - the language has no field privacy at all today, so
    // const is a same-class self-discipline check, not access control.
    @Test
    void reassigningAConstFieldFromOutsideTheClassIsNotCaught() {
        assertEquals("2", run(
                "def class Register { const field base; def init(base) { this.base = base; } } " +
                "r = new Register(1); r.base = 2; print r.base;"));
    }

    @Test
    void assigningAConstFieldASecondTimeWithinInitIsAllowed() {
        // no control-flow tracking by design - only WHICH method is
        // assigning is checked, not how many times
        assertEquals("2", run(
                "def class Counter { const field n; " +
                "  def init() { this.n = 1; this.n = 2; } } " +
                "print new Counter().n;"));
    }

    @Test
    void constFieldCanAlsoHaveALiteralDefault() {
        assertEquals("42|99", run(
                "def class Config { const field version = 42; } " +
                "def class Register { const field base; def init(base) { this.base = base; } } " +
                "print new Config().version + '|' + new Register(99).base;"));
    }
}
