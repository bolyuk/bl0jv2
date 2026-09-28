package bl0.bl0jv2.data;

import java.util.List;

// methodNames[i] pairs with methodConstIndices[i]: the constant-pool index
// of that method's own FunDef entry. The compiler always registers a
// class's method FunDefs before the ClassDef itself, so by the time the VM
// loads this entry, consts[methodConstIndices[i]] is already populated.
//
// staticFieldCount only carries the count, not names: 'ClassName.field'
// access is always a literal class name known at compile time (like static
// methods), so the compiler resolves each access straight to an index and
// the VM never needs to look a static field up by name at runtime.
//
// fieldDefaultConstIndices is parallel to fieldNames: -1 means "no
// initializer, defaults to nil"; otherwise the constant-pool index of that
// field's literal default value.
public record ClassDef(String name, List<String> fieldNames, List<Integer> fieldDefaultConstIndices, List<String> methodNames, List<Integer> methodConstIndices, int staticFieldCount) {
}
