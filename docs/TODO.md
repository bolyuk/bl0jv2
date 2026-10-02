# TODO

What is decided, what is next, and what is known to be missing. Ordered by what builds on what. Status as of 2026-10-02.

## 1. Memory: the object heap in raw memory, under the OS's allocator (decided, not started)

Where we are: the OS decides the policy (accounts, limits in `etc/limits`, when to collect - `lib/mem.bl0`), the VM counts and
collects (`memory()`, the stop-the-world collector in `Bl0jv2_jVM.collectGarbage`). The objects themselves - strings, arrays,
instances, closures - are still Java objects in `Heap.slots`, so the OS cannot place, grow or reclaim them itself. The aim is
that it can, and that the VM has no heap of its own to carry to a C port (see `docs/PORTING.md`, 3.1).

Plan, in steps that each leave the system working and the tests green:

1. **A written object layout.** Header (type, size, mark bit, account), then the body. Strings as UTF-8 plus a code-point
   index decision (PORTING.md 3.2 - decide first, it fixes the string natives), arrays as length + capacity + 8-byte words,
   instances as class id + fields, tuples, cells, closures. Classes, mutexes, events stay host-side behind handles (they hold
   locks and condition variables); their static fields and captured cells are scanned as roots.
2. **A reference is an address (or a handle) into the raw arena**, still NaN-boxed (`NanBox.TAG_REF`). `Heap.get()` becomes a
   bounds-checked read of the header. Do it behind the current `Heap` interface first, one object kind at a time (strings
   first: the biggest, and immutable), with the Java objects and the arena side by side until the last kind has moved.
3. **The allocator is the OS's.** The VM asks for pages: an upcall (`onNeedMemory(account)`) that the OS answers with an
   extent or a refusal; inside an extent the VM bump-allocates and keeps free lists. Limits become "no more pages", not an
   exception from `Heap.add`. The kernel can then take an account's pages back whole when a process ends - if nothing outside
   points into them. Decide the rule: a generation number per extent (a stale reference is an error, not corruption), plus
   the collector for what is shared.
4. **The collector marks and sweeps in the arena** (same stop-the-world protocol as now; the roots do not change). Compaction
   is optional and only worth it once pages are the unit.
5. **Native functions on bytes**: `strSub`, `strFind`, `strJoin`, `+` on strings, array ops, `print`, equality, number
   formatting (Java's `Double.toString` format must be reproduced exactly - PORTING.md 3.4). Check speed against the current
   numbers (`Bl0jv2_*Test` timings, the VM loop benchmark in the SSH work: ~60 M bytecode ops/s).
6. **Delete `Heap.slots`** and the Java-object value classes that were only there for it.

Open questions: how a worker's allocation avoids a global lock (per-core bump regions inside an account's extent); what the
kernel does with a reference a process stored into shared state before it exited; whether strings are interned in the arena.

## 2. Virtual consoles and a real terminal over ssh

One console, one shell today. `sshd` runs lines through the shell between two keys of the console's prompt (`Remote`,
`lib/netfs`-style mailbox in `shell.bl0`), so `edit`, `top`, `wm`, `read`, `su` do not work over ssh and a user cannot have a
shell of their own while someone is at the console.

- A console per session: a shell instance per console with its own `ShellState` (cwd, variables, history, identity), its own
  keyboard ring and screen buffer (the `Tty` frame buffers in `lib/drivers.bl0` are per-display today).
- Alt+F1..F4 to switch on the local display; a pty-like pair for ssh (`sshd` writes the keys into a console's input ring and
  reads its screen/output stream) so full-screen programs work and the line-at-a-time shell goes away.
- A shell inside a `wm` window falls out of the same thing.
- Then: history and completion over ssh, window-change (`SIGWINCH`-like) to the program, `scp`/sftp subsystem.

## 3. Missing utilities

`sed`/`awk`-like, `tar` (archive several files into one), `man`/`help <command>`, `less` with search, `xargs`, `env`/`export`,
`ln`, `du`, `uname`, `cut`, `tr`, `tee`, `which` for programs (built-in only today), `kill` by name, `watch`. And in the shell
language: functions, `case`, here-documents, redirecting a whole loop (`done < file`), `2>`/`2>&1`, background jobs from a
script, `$(...)` that keeps newlines in quotes, `${var:-default}`.

## 4. Known gaps and things to harden

- **sshd**: one connection at a time; no `authorized_keys` options; no rate limiting beyond a one-second delay per failed
  password and six tries per connection; the handshake takes 2-3 s in the interpreter. Constant-time is not claimed anywhere in
  `stdlib/crypto`. A second look at the strict-kex handling and at what an old client that does not offer
  `curve25519-sha256` sees (it is told "no matching algorithms").
- **Collector**: the number of cores a collection has to wait for is the number of cores; a core that loops forever in a
  non-polling native would stop everything. Natives that call back into bl0 code (`equals`/`toString` overrides) hold their
  operands in registers today - keep it that way, or root them.
- **Accounting**: sizes are estimates (fixed cost per object + contents); arrays that grow are corrected by `recount()` every
  few thousand allocations, not exactly. Raw memory (`kalloc`) is a bump allocator with no free; per-core load buffers grow
  but never shrink.
- **Programs** that fail print their failure on stderr now; many still `say` errors on stdout.
- **`reboot`** is a shutdown: the VM has no way to start itself again. A power-control port (halt / reset) and a host that
  restarts the VM would make it real.
- **Tests that are timing-sensitive** and have failed once or twice under a loaded full run: `CronTest` (needs free cores, given
  6 now), `TcpOutboundBridgeTest.aSegmentTheVmSendsAgain...` (waits 300 ms for the bridge). If one shows up again, make it wait
  for the condition instead of for time.
- **Windows**: the tests close their disk images (`DiskCleanup`) and text goes onto the disk with LF; real Windows runs of the
  new SSH/memory tests have not been seen yet.

## 5. Graphics and input

A pixel framebuffer device and a bitmap font; a mouse as a device (`dev/mouse`) and in `wm` (click to focus, drag to move);
colours beyond the 16 of the text display. After consoles (2), since a graphical console is a console.

## 6. Portability (see `docs/PORTING.md`)

Order: Pi under Linux with the JVM now; a C VM for Linux/Pi (which wants steps 1.1-1.5 above done, so the C code has no
managed heap to invent); then ESP32-S3 with PSRAM. Before that: golden tests that run a `.bl0c` and compare output without the
compiler or the JVM; one document for number formatting and Unicode rules; measure the real heap use of the shell and the
usual programs (`free`, `proc/meminfo` give it now).
