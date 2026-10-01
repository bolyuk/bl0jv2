# bl0jv2

A small register-based scripting language with its own compiler and virtual
machine, written in Java 21. It was built to write an operating system in
(`aeon-os/`, its own module: see aeon-os/README.md), so besides the usual language features it has a VM with
multiple cores, interrupts, timers, ports, raw memory, privilege rings and a
toy network stack (`stdlib/net/`) written in the language itself.

**Status: proof of concept.** Everything described here is covered by the test
suite, but it is a young language: see [Known limitations](#known-limitations).

## Build

```
mvn package
```

produces `target/bl0jv2-1.0-SNAPSHOT-fat.jar` (runnable) and, if `native-image`
is on the PATH, a GraalVM native binary `target/bl0jv2`.

Modules: `bl0jv2-common` (opcodes, constant formats, exceptions),
`bl0jv2-compiler` (lexer, parser, compiler, linker, `stdlib/`),
`bl0jv2-runtime` (the VM), `bl0jv2-cli` (command line, host network bridges),
`aeon-os/` (the OS and demo programs).

## Command line

```
bl0jv2 [-cdekVh] [-n <cores>] <source> [<dest>]

  -c, --compile      compile <source> to bytecode (<source>.bl0c, or <dest>)
  -d, --dump         disassemble the bytecode
  -e, --execute      run it
  -t, --terminal     interactive REPL, one line at a time (!exit quits)
  -k, --keyboard     bridge real stdin to aeon-os's simulated keyboard
  -n, --cores N      N-1 worker cores besides core 0 (default 1)
  -b, --bridge-udp PORT          relay a real UDP socket into the VM's NIC
      --bridge-tcp HOSTPORT:VMPORT  relay a real TCP socket to a Tcp listener
      --bridge-outbound          let VM code open real sockets (see below)
      --disk FILE [--disk-sectors N]  present FILE as a block device (created if missing)
      --bridge-fs DIR    show the host folder DIR to the program (stdlib/fs/hostfs.bl0)
      --disk-put HOSTFILE[:NAME]  copy a host file or folder onto the --disk image first
                         (a .bl0 is compiled and stored as .bl0c); repeatable
  -I, --include DIR  look an import up in DIR when it is not next to the importing file
```

```
java -jar bl0jv2-1.0-SNAPSHOT-fat.jar -c -e hello.bl0   # compile and run
```

`-c -e` together compile and run; `-e` alone expects an already compiled
`.bl0c`. `--bridge-outbound` lets a program's `TcpConn.connect()` /
`Udp.send()` reach real hosts: it refuses the wildcard, multicast and
link-local (169.254.0.0/16, cloud metadata) addresses and allows at most 64
connections at a time, but loopback and private ranges are reachable - only use
it with programs you trust.

## The language

Statements are separated by newlines or `;`. A `;` is required after `field`
declarations and `import`. `//` starts a line comment. `println` prints a
newline *before* its text (so output has no trailing newline).

### Values

`int` (32-bit, wraps on overflow), `float` (double), `string`, `bool`, `nil`,
`char` (what `s[i]` gives; equal to a one-character string), arrays `[1, 2]`,
tuples `(1, 2)` (immutable), functions, class instances, `err`, mutexes, events.

```
a = 5                 // variables need no declaration
b = 'text'            // 'single' or "double" quotes, same escapes:
println "say \"hi\"\n\t\x41"     // \n \t \r \0 \' \" \\ \xNN
println 1.5e3         // float literals may have an exponent
println 0xFF + 0b101  // hex and binary integer literals
```

A literal that does not fit in 32 bits is a parse error; reading a variable that
is never assigned anywhere in its function is a compile error.

### Operators

```
println 7 / 2         // 3     int / int is integer division
println 7 / 2.0       // 3.5   any float operand makes the operation float
println 2 ** 10       // 1024  right-associative; -2 ** 2 is -4
println 'ab' * 3      // ababab
println 'x=' + 5      // x=5   + concatenates when either side is a string
```

`+ - * / % **`, comparison `== != < > <= >=` (numbers; strings and chars by
character order), `&& || !` (operands must be `bool`: `if (5)` is an error,
there is no truthiness), bitwise `& | ^ ~ << >> >>>`, ternary `c ? a : b`,
`++`/`--`, and compound assignment `+= -= *= /= %= **= &= |= ^= <<= >>= >>>=`
(`a[f()] += 1` is rejected: the target would be evaluated twice).
`==` on numbers compares `1 == 1.0` as true; on arrays it is identity; an
instance with an `equals` method uses it.

### Control flow

```
if (x > 5) { println 'big' } else { println 'small' }

sum = 0
for (i = 1; i <= 10; i += 1) { sum += i }
while (sum > 0) { sum -= 20; if (sum < 10) { break } }

switch (x) {                     // no fallthrough; each case is a block
    case 1 { println 'one' }
    case 2 { println 'two' }
    default { println 'many' }
}

try { x = 1 / 0 } catch (e) { println 'caught: ' + e }
try { throw('custom') } catch (e) { println e }
```

Runtime failures and `throw(message)` are caught with `try/catch`; the catch
variable is an `err` value that prints as its message. Errors that nothing
catches end the program.

### Functions, lambdas, closures

```
def fact(n) { if (n <= 1) { return 1 } return n * fact(n - 1) }
def log(msg) { println msg; return }      // bare return gives nil

add = (a, b) -> a + b                     // lambda
k = 10
addk = (x) -> x + k                       // captures k (by reference)
fib = (n) -> n < 2 ? n : fib(n - 1) + fib(n - 2)   // may call itself

def counter() {
    n = 0
    def next() { n += 1; return n }       // nested def = closure
    return next
}
```

Top-level `def` functions are global and callable before their definition. A
`def` inside a function or block is a closure over the enclosing variables,
exists from its statement on, and can call itself. A parameter, or a local variable assigned in the function, shadows a global
function of the same name inside that function; assigning to a class's name
is a compile error. Calls are checked: a wrong argument count is a compile error
when the function is known and `function f expects 2 arguments, got 1`
otherwise. Recursion deeper than 100000 calls is a catchable
`stack overflow` error.

### Classes and enums

```
def class Point {
    field x;
    field y = 0;                 // default: a literal
    const field id;              // assignable only inside init()
    static field count;
    def init(x, y) { this.x = x; this.y = y }
    def length2() { return this.x * this.x + this.y * this.y }
    static def origin() { return new Point(0, 0) }
    def toString() { return 'P(' + this.x + ',' + this.y + ')' }
}
p = new Point(3, 4)
println p.length2()          // 25
println Point.origin()       // P(0,0)
Point.count = 1              // static fields and methods are reached through the class

enum Color { RED, GREEN, BLUE }
println Color.GREEN          // GREEN
println Color.BLUE.ordinal   // 2
```

Classes are closed: fields are fixed at declaration. The compiler rejects a
field or method no class declares, `this.field` typos, wrong argument counts to
`new`, static methods and `this.method()`. For a receiver of unknown class the
VM reports `class A has no field 'x'` or `cannot read field 'x' on nil`. There is
no inheritance. Instances with their own `toString`/`equals` are used by
`print`, `str()` and `==`.

### Arrays, tuples, destructuring

```
a = [10, 20, 30]
push(a, 40)
println a[0] + a[-1]     // 50   negative indexes count from the end
println pop(a)           // 40
t = (1, 'two')
x, y = (1, 2)            // destructuring
```

### Imports

`import 'path.bl0';` splices another file's top-level definitions in once
(paths are relative to the importing file; then each `-I` directory in order;
`stdlib/...` falls back to the copy bundled in the jar). The linker follows
imports by itself, so the CLI needs no list of files; `-I` only adds places to
look. It works at the `-t` prompt too.

## Builtins

| Area | Builtins |
|---|---|
| Values | `len(x)` `push(a, v)` `pop(a)` `int(x)` `float(x)` `str(x)` `typeOf(x)` `err(msg)` `isInt` `isFloat` `isString` `isBool` `isArray` `isNil` `isChar` `isTuple` `isErr` |
| Strings | `strSub(s, from, to)` `strFind(s, sub, from)` `strUpper(s)` `strLower(s)` `strJoin(array, sep)` `strChar(codePoint)` (used by the stdlib wrappers below) |
| Errors | `throw(message)` `panic(message)` (halts every core, cannot be caught) |
| Time | `ticks()` (ms since start) `wait(ms)` `setTimer(ms, vector)` `setInterval(ms, vector)` `cancelTimer(id)` |
| Memory | `free(x)` `reserve(addr, size)` `peek8/16/32(addr)` `poke8/16/32(addr, v)` `in8/16/32(port)` `out8/16/32(port, v)` |
| Cores | `coreCount()` `currentCore()` `dispatch(fn, core, arg)` `newMutex()` `lock(m)` `unlock(m)` `atomicAdd(addr, d)` `atomicCas(addr, expected, new)` |
| Events | `newEvent()` `eventGen(e)` `signalEvent(e)` `waitEvent(e, gen, timeoutMs)` |
| Interrupts | `registerHandler(fn, vector, priority)` `raiseInterrupt(v)` `raiseInterruptOn(core, v)` `disableInterrupts()` `enableInterrupts()` `haltCore()` |
| Privilege | `dropToUserMode()` (one-way) `isPrivileged()` `syscall(vector, arg)` |
| Other | `read()` (a line from stdin) `execMem(addr, size, mode)` (run a compiled program that sits in raw memory; mode 0 = user program, unloaded afterwards, 1 = kernel program) |

A user function with the same name as a builtin takes precedence. Privileged
(ring 0) only: `registerHandler`, `dispatch`, `execMem`, `haltCore`, `reserve`,
`disableInterrupts`, `enableInterrupts`, `in*`, `out*`.

**Events** are broadcast latches: read `gen = eventGen(e)`, check your
condition, then `waitEvent(e, gen, ms)` returns as soon as anything signalled
the event after `gen` was read (so a signal between your check and your wait is
not lost), on timeout (`false`), or when an interrupt this core can take is
pending. `ms < 0` waits forever. Always re-check in a loop.

**Timers** raise an interrupt vector after a delay (`setInterval` repeatedly);
`cancelTimer` returns whether it was still pending. **`raiseInterruptOn`** is an
inter-processor interrupt: only that core ever takes it.

## Standard library

Namespaced static methods; `import 'stdlib/<file>';` first.

| File | Contents |
|---|---|
| `arrlib.bl0` | `Arr.contains indexOf reverse slice join map filter reduce removeAt concat` |
| `map.bl0` | `new Map(buckets)`: `set get has remove keys values`, fields `size`, `bucketCount`; grows automatically |
| `mathlib.bl0` | `Math.abs min max floor ceil round sqrt clamp toHex` |
| `str/substr find case split trim affix toArr char fmt` | `Substr.substr`, `Find.find/findFrom`, `Case.upper/lower`, `Split.split` (any separator length), `Trim.trim`, `Affix.startsWith/endsWith`, `ToArr.toArr`, `Char.char`, `Fmt.format` |
| `str/utf8` | `Utf8.encode(text)` to an array of bytes, `Utf8.decode(bytes)` back (bad input becomes U+FFFD) |
| `fs/disk fs` | block-device driver and a filesystem: see below |
| `net/nic ip udp tcp http dns` | a toy network stack over a virtual NIC: see below |

`Fmt.format('{} + {} = {}', [3, 4, 7])` fills `{}` in order, `{2}` by index,
`{{`/`}}` for braces, and `{:08x}` `{:>6}` `{:<6}` `{:04}` for hex, alignment,
zero padding and width.

### Files

The VM's only file primitive is a **block device**, the thing every machine
already has: `--disk image` presents a host file as sectors of 512 bytes behind
five ports (`0x0F00` sector count, `0x0F04` sector, `0x0F08` buffer address,
`0x0F0C` command 1 = read / 2 = write, `0x0F0D` status). The controller moves a
sector by DMA between the device and a buffer in raw memory and the command has
finished when `out8` returns. Everything above that is bl0:

```
import 'stdlib/fs/fs.bl0';
Disk.init(kalloc(512));      // privileged, once: reserves the sector buffer
Fs.mount() || Fs.format();   // mount() is false on a blank disk
Fs.write('notes/a.txt', 'привет');  Fs.append('notes/a.txt', '!');
print Fs.read('notes/a.txt');       // nil if there is no such file
Fs.list('notes/')  // [[name, size], ...] sorted;  Fs.rename Fs.remove Fs.size Fs.exists Fs.info
```

Layout: superblock, a FAT, a flat directory (64-byte entries, up to 128 files,
names up to 47 bytes - `/` is just a character, `Fs.list(prefix)` makes it
look like folders), data. Text is stored as UTF-8. A write goes to fresh
sectors first and only then switches the directory entry, so a failure keeps the
old contents. Errors are `try/catch`-able messages starting `fs: `. After
`dropToUserMode()` the same calls work: the privileged port writes go through
a syscall (vector 6), while the sector buffer is read with `peek`/`poke`.

`--bridge-fs DIR` additionally shows ONE host folder through a second device
(share ports `0x0F10`-`0x0F28`, DMA like the disk): `stdlib/fs/hostfs.bl0` offers
`Hfs.list/size/read/write/remove/mkdir`. The host resolves every path against
that folder and refuses `..`, absolute paths and symlinks that lead out; nothing
else of the host is reachable, and without the flag there is no device at all.

To port to another machine, replace `fs/disk.bl0` (`Disk.read/write/sectors`)
and nothing else. The aeon-os shell has `ls cat write append rm mv cp df format` and more (aeon-os/README.md).

### Network stack

`Nic.init()` (or `Nic.initWithHostBridge()`), then `Udp.send/receive`,
`TcpConn.listen/accept/connect/send/receive/waitData/close`, `Http.get/serve`,
`Dns.resolve`. TCP has a real handshake and close, sequence numbers and
checksums, and timeout-based retransmission with backoff (`TcpConn.rtoMs`,
`TcpConn.maxRetries`; `TcpConn.connectTimeoutMs` bounds `connect()`, which
returns `nil` on timeout). Waiting is event-driven (`Nic.recvWait`,
`TcpConn.waitData`), not polling. `Nic.dropNext = N` drops the next N frames,
for testing a lossy link. There is no congestion control and no out-of-order
reassembly; HTTP is GET-only HTTP/1.0.

## Memory and limits

Values that do not fit in a register (strings, arrays, instances, closures)
live in a heap and are released **manually with `free(x)`** (a second free is
`double free`, using a freed value is `use after free`). The host can cap the
heap with `vm.set_max_heap_entries(n)`: allocating past it is the catchable
error `out of memory: heap entry limit (n) reached`. Operations that
allocate without you asking: string concatenation and `str()` of non-strings,
array/tuple literals, `new`, lambdas, and a cell for every variable of a
function that contains a lambda. `typeOf()` and `str()` of a string/bool/nil
allocate nothing.

An optional mark-and-sweep collector exists for hosts that want a safety net
(`vm.set_gc_enabled(true)`, single-core only); it is off by default.

Other host-configurable limits: `set_max_call_depth` (100000),
`set_max_string_length` (64M chars - longer results are an error),
`set_core_count`, `set_interrupt_poll_interval`.

## Known limitations

- No inheritance, no interfaces, no modules beyond textual `import`.
- Closures created in a loop share the loop's variable (`() -> i` all see the
  final `i`); a lambda body that reads a variable assigned only *after* the
  lambda was written is a compile error.
- A nested `def` is not hoisted: it can call itself and what is defined above
  it, not a nested `def` below it.
- Top-level `def` functions cannot see top-level variables (they are separate
  scopes); pass values as parameters or keep them in static fields.
- A `catch` variable cannot be captured by a lambda inside the catch body.
- `int` is 32-bit and wraps silently; `int(1e300)` saturates.
- Memory is not collected unless the host opts in (see above), and the interpreter
  loop is not fast: roughly 60 million simple instructions per second.
- The network stack is a teaching toy: one accepted connection per `listen()`,
  fake 32-bit addresses, no IPv6.
