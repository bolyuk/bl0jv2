# bl0jv2

A small register-based scripting language, compiler, and VM written in Java 21.
**Status: proof of concept.** The pipeline (lexer → parser → compiler → VM) works
end to end for the features listed below, but there are sharp edges — see
[Known limitations](#known-limitations). Every example in this file has been
run against the actual jar.

## Build

```
mvn package
```

This produces two artifacts:

- `target/bl0jv2-1.0-SNAPSHOT-fat.jar` — runnable fat jar
- `target/bl0jv2` (or `bl0jv2.exe` on Windows) — a GraalVM native image, if `native-image` is on your PATH

## CLI usage

```
bl0jv2 [-cdehtV] <source> [<dest>]

  <source>       source file
  [<dest>]       destination file (optional, only used with -c)

  -c, --compile  compile <source> to bytecode (writes <source>.bl0c, or <dest> if given)
  -d, --dump     print the bytecode disassembly (header, constants, instructions)
  -e, --execute  execute the bytecode
  -t, --terminal start an interactive REPL
  -h, --help     show help and exit
  -V, --version  print version and exit
```

Examples:

```
java -jar bl0jv2-1.0-SNAPSHOT-fat.jar -c hello.bl0        # compile -> hello.bl0.bl0c
java -jar bl0jv2-1.0-SNAPSHOT-fat.jar -e hello.bl0.bl0c    # run compiled bytecode
java -jar bl0jv2-1.0-SNAPSHOT-fat.jar -c -e hello.bl0      # compile and immediately run
java -jar bl0jv2-1.0-SNAPSHOT-fat.jar -c -d hello.bl0      # compile and print the disassembly
java -jar bl0jv2-1.0-SNAPSHOT-fat.jar -t                   # interactive REPL, one line at a time
```

In the REPL, type `!exit` to quit. Each line you enter is compiled and executed
immediately, and its disassembly is printed before it runs.

## Language structure

Source files have no extension requirement. There is no comment syntax yet —
everything you write is parsed as code.

**Statement separators:** don't rely on trailing `;` — see
[Known limitations](#known-limitations). Just put one statement per line.

### Literals

```
5           // integer
'hello'     // string (single quotes only, no escapes)
true
false
nil
```

### Variables & assignment

Variables don't need declaring — assigning to an identifier creates it.

```
a = 5
b = 'text'
c = a
```

### Arithmetic operators

`+  -  *  /  %` are supported, with limited operator overloading via a
type-based dispatch table:

```
x = 3 + 4          // 7
y = 10 % 3          // 1
s = 'foo' + 'bar'   // 'foobar'
s2 = '' + 1          // '1'   (string + int -> string concat; see note below)
r = 'ab' * 3         // 'ababab' (string * int -> repeat)
```

**Note:** mixed string/int addition ignores which side the string is
literally written on and always concatenates as if the string came first
(a quirk of the operator dispatch table — see
[Known limitations](#known-limitations)). If you want `int + string` to read
naturally, put an empty string first: `'' + i + ' items'`.

Division and remainder by zero throw a VM exception.

### Comparison & equality

```
a == b
a != b
a < b
a > b
```

`<=` and `>=` are **not implemented** in the compiler yet — avoid them (see
[Known limitations](#known-limitations)).

### Unary & postfix operators

```
neg = -a
notb = !true

i = 0
i++
i--
```

### Ternary

```
age = 20
label = age == 20 ? 'twenty' : 'not twenty'
println label
```

### if / else

The condition may optionally be wrapped in parentheses; the body may be a
single statement or a `{ }` block.

```
x = 7
if (x > 5) {
    println 'big'
} else {
    println 'small'
}
```

### while

```
i = 0
while (i < 5) {
    println i
    i = i + 1
}
```

### Functions

Functions must be defined at the top level of the program (not nested inside
a block), and support recursion.

```
def add(a, b) {
    return a + b
}
println add(3, 4)

def factorial(n) {
    if (n == 0) {
        return 1
    }
    return n * factorial(n - 1)
}
println factorial(5)   // 120
```

### Native calls

`print`, `println`, and `wait` are built-in statements (not regular function
calls — they take one expression directly, no parentheses):

```
print 'no newline'
println 'with newline'
wait 1000   // sleep 1000 ms
```

### Grouping

Parentheses can be used to group any expression:

```
r = (2 + 3) * 4   // 20
```

## Full example

```
def is_even(n) {
    return n % 2 == 0
}

i = 0
while (i < 10) {
    label = is_even(i) ? 'even' : 'odd'
    line = '' + i + ' is ' + label
    println line
    i = i + 1
}
```

Output:

```
0 is even
1 is odd
2 is even
3 is odd
...
9 is odd
```

## Known limitations

This is a PoC — the following are worth knowing before writing anything
non-trivial:

- **Semicolons are unreliable as statement separators.** `print`/`println`/
  `wait` statements consume a trailing `;` themselves, but plain assignments,
  expression statements, and `return` do not. A `;` left over from one of
  those gets fed into the *next* statement, which breaks as soon as that next
  statement is anything other than another bare assignment (an `if`,
  `while`, `println`, or the closing `}` of a block all fail to parse).
  Safest approach: don't write `;` at all except optionally after the very
  last native call in the file.
- **Mixed string/int `+` ignores operand order.** `i + ' items'` actually
  evaluates as `' items' + i`, because the operator dispatch table
  (`OperatorTable`) always normalizes to its registered `(String, Integer)`
  order regardless of which side each operand was written on. Put a string
  literal first (e.g. `'' + i`) to get natural left-to-right concatenation.
- `<=` and `>=` — tokenized, but the compiler has no case for them; using
  them throws at compile time.
- `**` (exponent) — tokenized, but never consumed by the parser's expression
  grammar, so it can't actually be written in a program.
- Bare tuple expressions like `(1, 2, 3)` — parsed into a tuple node, but the
  compiler doesn't handle it; using one throws at compile time.
- `class` — no keyword mapping exists in the lexer yet, and class-definition
  parsing is stubbed out (`TODO`). Not usable.
- Lambdas, closures, and `new` — not implemented (marked `TODO` in the
  parser's grammar comments).
- Comments — there is no comment syntax in the language.
- Instruction addresses, register indices, and register counts are encoded
  as single bytes, so a compiled function/program is limited to 255
  instructions and 255 registers.
- Native calls (`print`/`println`/`wait`) accept exactly one argument.
