package bl0.bl0jv2.data;

/**
 * A reference to a function or class that lives in a shared library: the compiler knows its
 * name and signature (from the library's source) but not where it is. The loader replaces
 * the constant with the library's own function or class, looked up by name among the exports
 * of the libraries loaded before this program. A method is named "Class.method", as in its
 * FunDef.
 */
public record ExternDef(String name) {
}
