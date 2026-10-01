package bl0.bl0jv2.runtime.arithmetic;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;
import bl0.bl0jv2.runtime.values.Bl0jArray;
import bl0.bl0jv2.runtime.values.Bl0jError;
import bl0.bl0jv2.runtime.values.Bl0jInstance;
import bl0.bl0jv2.runtime.values.Bl0jTuple;

/**
 * Every arithmetic/bitwise {@link OperatorTable} the VM dispatches through,
 * built once and held here instead of bloating the VM's own constructor.
 */
public final class ArithmeticOperators {
    public final OperatorTable add = new OperatorTable();
    public final OperatorTable sub = new OperatorTable();
    public final OperatorTable div = new OperatorTable();
    public final OperatorTable mul = new OperatorTable();
    public final OperatorTable rem = new OperatorTable();
    public final OperatorTable pow = new OperatorTable();
    public final OperatorTable and = new OperatorTable();
    public final OperatorTable or = new OperatorTable();
    public final OperatorTable xor = new OperatorTable();
    public final OperatorTable shl = new OperatorTable();
    public final OperatorTable shr = new OperatorTable();
    public final OperatorTable ushr = new OperatorTable();

    // nilObject is the VM's own unboxed-nil sentinel (NIL_OBJECT) - needed
    // here only for the two nil-concatenation entries in 'add', since that
    // sentinel's identity must stay private to the VM otherwise
    public ArithmeticOperators(Object nilObject) {
        add.add(Integer.class, Integer.class, (a, b) -> (int)a + (int)b);
        add.add(String.class,  String.class,  (a, b) -> concat(a, b));
        // concatenation is order-sensitive ('x=' + 5 must read "x=5", not
        // "5x="), so both directions are registered explicitly rather than
        // relying on OperatorTable to guess an order
        add.add(String.class,  Integer.class, (a, b) -> concat(a, b));
        add.add(Integer.class, String.class,  (a, b) -> concat(a, b));
        add.add(String.class,  Boolean.class, (a, b) -> concat(a, b));
        add.add(Boolean.class, String.class,  (a, b) -> concat(a, b));
        add.add(String.class,  Double.class,  (a, b) -> concat(a, b));
        add.add(Double.class,  String.class,  (a, b) -> concat(a, b));
        add.add(String.class,  Bl0jArray.class, (a, b) -> concat(a, b));
        add.add(Bl0jArray.class, String.class,  (a, b) -> concat(a, b));
        add.add(String.class,  Bl0jTuple.class, (a, b) -> concat(a, b));
        add.add(Bl0jTuple.class, String.class,  (a, b) -> concat(a, b));
        add.add(String.class,  Bl0jError.class, (a, b) -> concat(a, b));
        add.add(Bl0jError.class, String.class,  (a, b) -> concat(a, b));
        add.add(String.class,  Bl0jInstance.class, (a, b) -> concat(a, b));
        add.add(Bl0jInstance.class, String.class,  (a, b) -> concat(a, b));
        // nilObject's type is an anonymous class, so it's registered via
        // .getClass() here rather than a named Foo.class literal
        add.add(String.class,  nilObject.getClass(), (a, b) -> concat(a, b));
        add.add(nilObject.getClass(), String.class,  (a, b) -> concat(a, b));
        add.add(String.class,  Character.class, (a, b) -> concat(a, b));
        add.add(Character.class, String.class,  (a, b) -> concat(a, b));
        // integer arithmetic stays integer (10/3 truncates); any operand
        // that is already a double promotes the whole operation to double
        add.add(Double.class,  Double.class,  (a, b) -> toDouble(a) + toDouble(b));
        add.add(Integer.class, Double.class,  (a, b) -> toDouble(a) + toDouble(b));
        add.add(Double.class,  Integer.class, (a, b) -> toDouble(a) + toDouble(b));

        sub.add(Integer.class, Integer.class, (a, b) -> (int)a - (int)b);
        sub.add(Double.class,  Double.class,  (a, b) -> toDouble(a) - toDouble(b));
        sub.add(Integer.class, Double.class,  (a, b) -> toDouble(a) - toDouble(b));
        sub.add(Double.class,  Integer.class, (a, b) -> toDouble(a) - toDouble(b));

        mul.add(Integer.class, Integer.class, (a, b) -> (int)a * (int)b);
        // string repetition's count can legitimately appear on either side
        mul.add(String.class,  Integer.class, (a, b) -> repeat(a.toString(), (int) b));
        mul.add(Integer.class, String.class,  (a, b) -> repeat(b.toString(), (int) a));
        mul.add(Double.class,  Double.class,  (a, b) -> toDouble(a) * toDouble(b));
        mul.add(Integer.class, Double.class,  (a, b) -> toDouble(a) * toDouble(b));
        mul.add(Double.class,  Integer.class, (a, b) -> toDouble(a) * toDouble(b));

        div.add(Integer.class, Integer.class, (a, b) -> {
            if ((int)b == 0) throw new Bl0j_VM_Exception("division by zero");
            return (int)a / (int)b;
        });
        // float division follows IEEE754 (1.0 / 0 is Infinity, not an
        // error) - only pure integer division treats zero as a hard error
        div.add(Double.class,  Double.class,  (a, b) -> toDouble(a) / toDouble(b));
        div.add(Integer.class, Double.class,  (a, b) -> toDouble(a) / toDouble(b));
        div.add(Double.class,  Integer.class, (a, b) -> toDouble(a) / toDouble(b));

        rem.add(Integer.class, Integer.class, (a, b) -> {
            if ((int)b == 0) throw new Bl0j_VM_Exception("division by zero");
            return (int)a % (int)b;
        });
        rem.add(Double.class,  Double.class,  (a, b) -> toDouble(a) % toDouble(b));
        rem.add(Integer.class, Double.class,  (a, b) -> toDouble(a) % toDouble(b));
        rem.add(Double.class,  Integer.class, (a, b) -> toDouble(a) % toDouble(b));

        pow.add(Integer.class, Integer.class, (a, b) -> {
            int base = (int) a, exp = (int) b;
            if (exp < 0) throw new Bl0j_VM_Exception("negative exponent");
            // square-and-multiply: O(log exp), and the same result as
            // multiplying one at a time - int multiplication wraps mod 2^32,
            // which is associative. (The loop used to run exp times: 2 ** 2000000000
            // spun for seconds and returned 0.)
            int result = 1;
            while (exp > 0) {
                if ((exp & 1) != 0) result *= base;
                base *= base;
                exp >>= 1;
            }
            return result;
        });
        // unlike the pure-integer case above, a double base/exponent can
        // represent fractional results, so Math.pow handles negative and
        // fractional exponents directly instead of throwing
        pow.add(Double.class,  Double.class,  (a, b) -> Math.pow(toDouble(a), toDouble(b)));
        pow.add(Integer.class, Double.class,  (a, b) -> Math.pow(toDouble(a), toDouble(b)));
        pow.add(Double.class,  Integer.class, (a, b) -> Math.pow(toDouble(a), toDouble(b)));

        // bitwise operators only make sense on integers
        and.add(Integer.class, Integer.class, (a, b) -> (int)a & (int)b);
        or.add(Integer.class, Integer.class, (a, b) -> (int)a | (int)b);
        xor.add(Integer.class, Integer.class, (a, b) -> (int)a ^ (int)b);
        shl.add(Integer.class, Integer.class, (a, b) -> (int)a << (int)b);
        shr.add(Integer.class, Integer.class, (a, b) -> (int)a >> (int)b);
        // logical shift: zero-fills from the left regardless of sign,
        // unlike >> which sign-extends - matters when a value is really a
        // bit pattern (a hardware register, a raw memory word) rather than
        // a signed number
        ushr.add(Integer.class, Integer.class, (a, b) -> (int)a >>> (int)b);
    }

    // longest string an operation may build, in chars. A runaway concatenation
    // or repeat used to end in an OutOfMemoryError that kills the whole VM
    // (and cannot be caught); now it is an ordinary error.
    private volatile int maxStringLength = 1 << 26; // 64M chars

    public void setMaxStringLength(int maxStringLength) {
        this.maxStringLength = maxStringLength;
    }

    private String concat(Object a, Object b) {
        String left = a.toString(), right = b.toString();
        if ((long) left.length() + right.length() > maxStringLength)
            throw new Bl0j_VM_Exception("string too long: concatenation would exceed " + maxStringLength + " characters");
        return left + right;
    }

    private String repeat(String s, int count) {
        if (count < 0)
            throw new Bl0j_VM_Exception("negative repeat count: " + count);
        if ((long) s.length() * count > maxStringLength)
            throw new Bl0j_VM_Exception("string too long: repeating " + s.length() + " characters " + count
                    + " times would exceed " + maxStringLength + " characters");
        return s.repeat(count);
    }

    public static double toDouble(Object numeric) {
        return (numeric instanceof Integer i) ? i.doubleValue() : (Double) numeric;
    }
}
