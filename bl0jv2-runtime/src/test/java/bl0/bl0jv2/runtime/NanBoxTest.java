package bl0.bl0jv2.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NanBoxTest {

    @Test
    void intRoundTripsPositiveAndNegative() {
        for (int v : new int[]{0, 1, -1, 42, -42, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            long boxed = NanBox.ofInt(v);
            assertTrue(NanBox.isBoxed(boxed));
            assertEquals(NanBox.TAG_INT, NanBox.tagOf(boxed));
            assertEquals(v, NanBox.asInt(boxed));
        }
    }

    @Test
    void booleanRoundTrips() {
        assertTrue(NanBox.asBoolean(NanBox.ofBoolean(true)));
        assertFalse(NanBox.asBoolean(NanBox.ofBoolean(false)));
        assertEquals(NanBox.TAG_BOOL, NanBox.tagOf(NanBox.ofBoolean(true)));
    }

    @Test
    void nilIsBoxedWithNilTag() {
        assertTrue(NanBox.isBoxed(NanBox.NIL));
        assertEquals(NanBox.TAG_NIL, NanBox.tagOf(NanBox.NIL));
    }

    @Test
    void refIndexRoundTrips() {
        long boxed = NanBox.ofRef(12345);
        assertTrue(NanBox.isBoxed(boxed));
        assertEquals(NanBox.TAG_REF, NanBox.tagOf(boxed));
        assertEquals(12345, NanBox.asRefIndex(boxed));
    }

    @Test
    void genuineDoubleNaNIsNeverMistakenForABoxedValue() {
        long realNaNBits = Double.doubleToLongBits(0.0 / 0.0);
        assertFalse(NanBox.isBoxed(realNaNBits));
    }

    @Test
    void genuineFiniteDoublesAreNeverMistakenForBoxedValues() {
        for (double d : new double[]{0.0, 1.5, -1.5, Double.MAX_VALUE, Double.MIN_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(NanBox.isBoxed(Double.doubleToLongBits(d)));
        }
    }

    @Test
    void distinctTagsProduceDistinctBitPatternsForSamePayload() {
        assertEquals(NanBox.ofInt(0) == NanBox.ofBoolean(false), false);
    }

    @Test
    void charRoundTrips() {
        for (char c : new char[]{'a', 'Z', '0', ' ', '\n', '\u0000', '￿'}) {
            long boxed = NanBox.ofChar(c);
            assertTrue(NanBox.isBoxed(boxed));
            assertEquals(NanBox.TAG_CHAR, NanBox.tagOf(boxed));
            assertEquals(c, NanBox.asChar(boxed));
        }
    }

    @Test
    void charDoesNotCollideWithIntOfSameNumericValue() {
        assertNotEquals(NanBox.ofInt('a'), NanBox.ofChar('a'));
    }
}
