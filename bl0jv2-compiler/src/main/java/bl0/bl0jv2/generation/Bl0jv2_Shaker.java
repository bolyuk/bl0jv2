package bl0.bl0jv2.generation;

import bl0.bl0jv2.generation.nodes.BinaryNode;
import bl0.bl0jv2.generation.nodes.Node;
import bl0.bl0jv2.generation.nodes.PROGRAM_N;
import bl0.bl0jv2.generation.nodes.RegValueNode;
import bl0.bl0jv2.generation.nodes.data.*;
import bl0.bl0jv2.generation.nodes.statements.*;
import bl0.bl0jv2.generation.nodes.unary.LUnaryNode;
import bl0.bl0jv2.generation.nodes.unary.RUnaryNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drops what a program never uses. Importing a file splices all of its functions and classes
 * in, so a three-line program that imports a library carries the whole library; this removes
 * the imported functions and classes that nothing reachable refers to.
 *
 * <p>Only definitions that came from an <em>import</em> are candidates. The entry file's own
 * definitions stay (a library built as an entry file exports everything it declares), as does
 * every top-level statement anywhere (it runs), and any imported file that marks itself with
 * {@code @library} in a comment near its top (see {@link Bl0jv2_Linker}).
 *
 * <p>Reachability is by name: a function or class is kept when its name occurs anywhere in
 * code that is kept - as a call, a value, {@code new}, or {@code Class.member}. A local variable
 * with the same name keeps it too: the analysis may keep too much, never too little. A kept
 * class keeps all its methods (they are found by name at run time). A node kind this walker
 * does not know is an error, not a guess.
 */
public final class Bl0jv2_Shaker {
    private Bl0jv2_Shaker() {}

    /** a set of nodes compared by identity */
    public static Set<Node> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    // what the analysis has found used so far
    private static final class Use {
        final Set<String> names = new HashSet<>();        // identifiers, class names of 'new'
        final Set<String> members = new HashSet<>();      // x.name for any x
        final Set<String> qualified = new HashSet<>();    // Class.name
    }

    // methods the VM itself calls, by name, on instances
    private static final Set<String> VM_CALLED = Set.of("init", "toString", "equals");

    // static initialisation that does nothing but compute a value: dropped along with its class
    private static final Set<String> PURE_CALLS = Set.of("newMutex", "newEvent", "strChar");

    private static boolean isPure(Node n) {
        return switch (n) {
            case null -> true;
            case IdentityNode i -> true;
            case NumberNode i -> true;
            case FloatNode i -> true;
            case StringNode i -> true;
            case BooleanNode i -> true;
            case NilNode i -> true;
            case ArrayLiteralNode a -> a.elements.stream().allMatch(Bl0jv2_Shaker::isPure);
            case TupleNode t -> t.values.stream().allMatch(Bl0jv2_Shaker::isPure);
            case LUnaryNode u -> isPure(u.left);
            case FunCall f -> f.left instanceof IdentityNode c && PURE_CALLS.contains(c.name)
                    && f.args.stream().allMatch(Bl0jv2_Shaker::isPure);
            case BinaryNode b -> b.op != bl0.bl0jv2.generation.Operator.ASSIGNMENT && isPure(b.left) && isPure(b.right);
            default -> false;
        };
    }

    // 'Class.field = value' at top level, where Class is a thinnable class: the class's own set-up
    private static String initOf(Node node, Map<String, Node> defs) {
        if (node instanceof BinaryNode b && b.op == bl0.bl0jv2.generation.Operator.ASSIGNMENT
                && b.left instanceof FieldAccessNode fa && fa.target instanceof IdentityNode c
                && defs.get(c.name) instanceof ClassNode && isPure(b.right))
            return c.name;
        return null;
    }

    /**
     * 'program' without what is unreachable among 'candidates' (the imported functions and classes),
     * and without the unused methods of the classes that stay: a static method stays when
     * {@code Class.name} occurs in kept code, an instance method when {@code .name} occurs anywhere
     * in it (it is found by name at run time) or the VM calls it itself.
     */
    public static PROGRAM_N shake(PROGRAM_N program, Set<Node> candidates) {
        if (candidates.isEmpty())
            return program;

        Map<String, Node> defs = new HashMap<>();
        for (Node node : program.nodes)
            if (candidates.contains(node))
                defs.put(nameOf(node), node);

        Use use = new Use();
        Set<Node> reached = identitySet();              // defs kept
        Set<Node> keptNodes = identitySet();            // statements kept (set-up of reached classes)
        Map<ClassNode, Set<FunNode>> keptMethods = new IdentityHashMap<>();

        List<Node> pendingInit = new ArrayList<>();
        for (Node node : program.nodes) {
            if (candidates.contains(node)) continue;
            String owner = initOf(node, defs);
            if (owner != null) pendingInit.add(node);
            else walk(node, use);
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : new ArrayList<>(use.names)) {
                Node def = defs.get(name);
                if (def == null || !reached.add(def)) continue;
                changed = true;
                if (def instanceof FunNode f) {
                    walk(f.body, use);
                } else {
                    ClassNode c = (ClassNode) def;
                    keptMethods.put(c, Collections.newSetFromMap(new IdentityHashMap<>()));
                    for (var d : c.fieldDefaultNodes) walk(d, use);
                }
            }
            // an instance is not always made by a visible 'new': a class whose field or method name is
            // used somewhere stays, so the code that uses that name still has a declaration to check against
            for (Node def : defs.values()) {
                if (!(def instanceof ClassNode c) || reached.contains(def)) continue;
                boolean named = c.fieldNames.stream().anyMatch(use.members::contains)
                        || c.methods.stream().anyMatch(m -> use.members.contains(base(c, m)));
                if (named) {
                    reached.add(def);
                    keptMethods.put(c, Collections.newSetFromMap(new IdentityHashMap<>()));
                    for (var d : c.fieldDefaultNodes) walk(d, use);
                    changed = true;
                }
            }
            for (Node init : new ArrayList<>(pendingInit)) {
                if (reached.contains(defs.get(initOf(init, defs)))) {
                    pendingInit.remove(init);
                    walk(init, use);
                    keptNodes.add(init);
                    changed = true;
                }
            }
            for (var entry : keptMethods.entrySet()) {
                ClassNode c = entry.getKey();
                for (FunNode m : c.methods)
                    if (!entry.getValue().contains(m) && (use.members.contains(base(c, m)) || VM_CALLED.contains(base(c, m)))) {
                        entry.getValue().add(m); walk(m.body, use); changed = true;
                    }
                for (FunNode m : c.staticMethods)
                    if (!entry.getValue().contains(m) && use.qualified.contains(c.name + "." + base(c, m))) {
                        entry.getValue().add(m); walk(m.body, use); changed = true;
                    }
            }
        }

        List<Node> out = new ArrayList<>(program.nodes.size());
        for (Node node : program.nodes) {
            if (candidates.contains(node)) {
                if (!reached.contains(node)) continue;
                if (node instanceof ClassNode c) {
                    Set<FunNode> kept = keptMethods.get(c);
                    node = new ClassNode(c.name, c.fieldNames, c.fieldDefaultNodes, c.constFieldNames,
                            c.methods.stream().filter(kept::contains).toList(),
                            c.staticMethods.stream().filter(kept::contains).toList(), c.staticFieldNames);
                }
                out.add(node);
            } else if (initOf(node, defs) == null || keptNodes.contains(node)) {
                out.add(node);
            }
        }
        return new PROGRAM_N(out);
    }

    // a method's own name: the parser may store it as "Class.name"
    private static String base(ClassNode c, FunNode m) {
        return m.name.startsWith(c.name + ".") ? m.name.substring(c.name.length() + 1) : m.name;
    }

    private static String nameOf(Node def) {
        return def instanceof FunNode f ? f.name : ((ClassNode) def).name;
    }

    private static void walk(Node node, Use use) {
        if (node == null) return;
        switch (node) {
            case IdentityNode n -> use.names.add(n.name);
            case NumberNode n -> { }
            case FloatNode n -> { }
            case StringNode n -> { }
            case BooleanNode n -> { }
            case NilNode n -> { }
            case BreakNode n -> { }
            case ContinueNode n -> { }
            case ImportNode n -> { }
            case PARAMS_N n -> { }
            case RegValueNode n -> { }
            case PROGRAM_N n -> { for (var st : n.nodes) walk(st, use); }
            case FunNode n -> walk(n.body, use);
            case ClassNode n -> {
                for (var m : n.methods) walk(m, use);
                for (var m : n.staticMethods) walk(m, use);
                for (var d : n.fieldDefaultNodes) walk(d, use);
            }
            case ReturnNode n -> walk(n.right, use);
            case NativeCallNode n -> walk(n.right, use);
            case FunCall n -> { walk(n.left, use); for (var a : n.args) walk(a, use); }
            case NewNode n -> { use.names.add(n.className); for (var a : n.args) walk(a, use); }
            case ArrayLiteralNode n -> { for (var e : n.elements) walk(e, use); }
            case TupleNode n -> { for (var v : n.values) walk(v, use); }
            case IndexNode n -> { walk(n.left, use); walk(n.index, use); }
            case FieldAccessNode n -> {
                use.members.add(n.fieldName);
                if (n.target instanceof IdentityNode c) use.qualified.add(c.name + "." + n.fieldName);
                walk(n.target, use);
            }
            case DestructuringAssignNode n -> { for (var t : n.targets) walk(t, use); walk(n.right, use); }
            case WhileNode n -> { walk(n.condition, use); walk(n.body, use); }
            case ForNode n -> { walk(n.init, use); walk(n.condition, use); walk(n.body, use); walk(n.update, use); }
            case TryNode n -> { walk(n.tryBody, use); walk(n.catchBody, use); }
            case Ternary_IfNode n -> { walk(n.condition, use); walk(n.body, use); walk(n.elseBody, use); }
            case IfNode n -> { walk(n.condition, use); walk(n.body, use); walk(n.elseBody, use); }
            case BinaryNode n -> { walk(n.left, use); walk(n.right, use); }
            case LUnaryNode n -> walk(n.left, use);
            case RUnaryNode n -> walk(n.right, use);
            case LambdaNode n -> walk(n.body, use);
            default -> throw new IllegalStateException("tree shaking does not know the node kind " + node.getClass().getSimpleName());
        }
    }
}
