package bl0.bl0jv2.runtime;

import bl0.bl0jv2.exceptions.Bl0j_VM_Exception;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class OperatorTable {
    private final List<Operator> operators = new ArrayList<>();

    public void add(Class<?> l, Class<?> r, BiFunction<Object, Object, Object> fun) {
        operators.add(new Operator(l, r, fun));
    }

    public Object calculate(Object left, Object right) {
        for (var op : operators) {
            if (op.matches(left, right))
                return op.apply(left, right);
        }
        throw new Bl0j_VM_Exception("no operator for " + left.getClass().getSimpleName() + " and " + right.getClass().getSimpleName());
    }

    // matches/applies strictly in the operands' actual left-to-right order:
    // silently swapping mismatched operands to fit the registered (l, r)
    // pair breaks order-sensitive operators like string concatenation
    // ('apples' + 5 must not become the same result as 5 + 'apples').
    // Operators where either order is legitimate (e.g. int * string repeat)
    // simply register both (l, r) and (r, l) explicitly.
    private record Operator(Class<?> l, Class<?> r, BiFunction<Object, Object, Object> fun) {
        boolean matches(Object left, Object right) {
            return l.isInstance(left) && r.isInstance(right);
        }

        Object apply(Object left, Object right) {
            return fun.apply(left, right);
        }
    }
}
