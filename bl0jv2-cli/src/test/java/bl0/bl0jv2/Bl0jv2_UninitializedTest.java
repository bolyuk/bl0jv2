package bl0.bl0jv2;

import org.junit.jupiter.api.Test;

import static bl0.bl0jv2.Bl0jv2_TestRunner.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

// registers start as nil, not as the double 0.0 an all-zero bit pattern
// happens to decode to (a missing call argument is now an arity error instead
// - see Bl0jv2_ArityTest)
class Bl0jv2_UninitializedTest {

    @Test
    void readingAnUnassignedVariableYieldsNilNotZero() {
        assertEquals("nil", run("print typeOf(neverAssigned);"));
    }
}
