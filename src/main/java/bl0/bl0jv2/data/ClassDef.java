package bl0.bl0jv2.data;

import java.util.List;

// methodNames[i] pairs with methodConstIndices[i]: the constant-pool index
// of that method's own FunDef entry. The compiler always registers a
// class's method FunDefs before the ClassDef itself, so by the time the VM
// loads this entry, consts[methodConstIndices[i]] is already populated.
public record ClassDef(String name, List<String> fieldNames, List<String> methodNames, List<Integer> methodConstIndices) {
}
