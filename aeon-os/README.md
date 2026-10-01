# aeon-os

An operating system written in bl0, run by the bl0jv2 VM. This is a Maven module
(`mvn -pl aeon-os -am test`); the only Java in it is the tests, which boot the OS
and type on its keyboard.

```
aeon-os/
  kernel.bl0        heap, keyboard driver, console (syscall gate)
  boot.bl0          boots, runs a demo scheduler, starts sbin/ programs from the disk
  smp_boot.bl0      the same on several cores
  shell.bl0         the shell: built-ins, the network commands, program launcher
  child_*.bl0       small programs boot starts
  lib/              sys (system page), log, loader, userland (what a program imports), input (stdin),
                    term/keys/lineedit (terminal output, key parsing, the line editor), cmdline (parser)
  bin/              the commands: ls cat write append touch rm mv cp stat wc head tail grep hexdump df mkdir rmdir edit
```

## There is no host filesystem

Nothing the OS runs can read a host file. The VM's only storage is a **block
device** (`--disk image`: 512-byte sectors behind five ports, see the main
README), and everything above it is bl0: the filesystem (`stdlib/fs`), the
loader, the commands.

* `sbin/` on the disk holds the kernel programs boot starts (`shell`,
  `child_hello`, ...); `bin/` holds the commands; `var/log/aeon.log` is the
  system log (boot, every shell command, programs started and how they ended;
  it rotates to `aeon.log.1` at 16 KiB).
* The shell has only what changes its own state built in (`cd`, `pwd`, `echo >`,
  `format`, `exec`, `help`, `exit`, ... and the network commands, whose state
  lives in the shell). Any other word is looked up as `bin/<word>.bl0c` on the
  disk, read with `Fs`, placed in raw memory and run with `execMem` - **in user
  mode**, relocated to wherever there is room, and unloaded when it returns.
* A program gets its command line, the current folder and the disk driver's
  state from the **system page** (`lib/sys.bl0`, a fixed block of raw memory), and
  reaches the machine only through syscalls.

## Running

```
mvn -q package -DskipTests
aeon-os/aeon.sh                 # builds aeon.img from the sources and boots it
```

`aeon.sh` is just the CLI with `--disk aeon.img` and a `--disk-put` per part
(`--disk-put aeon-os/bin:bin` copies a folder, compiling every `.bl0` to a
`.bl0c`). The image keeps its files between runs; `--disk-put` rewrites what it
names.

## The terminal

The console is a serial terminal, modelled as two devices (see the main README): the
guest writes UTF-8 bytes - with ANSI escape sequences for the cursor and the screen -
to a port, and reads the bytes the terminal sends from a FIFO behind another. So the
OS needs nothing from the host but a terminal: `-k` puts the host terminal in raw mode
(Unix: via `stty`; elsewhere it stays in line mode and shows its own echo too) and
passes size and keys through.

* **Line editor** (`lib/lineedit.bl0`): any Unicode, Backspace/Delete, arrows, Home/End,
  Ctrl-A/E/U/K/W, Ctrl-Left/Right and Alt-B/F by word, Ctrl-L, history (Up/Down, kept in
  `var/history`), Tab completion of commands and paths (a second Tab lists), Ctrl-C,
  Ctrl-D. A line longer than the screen wraps and edits correctly.
* **Quoting, pipes, redirection**: `'literal'`, `"with \n \t \" \\"`, `\` before a
  character; `a | b | c`, `< in`, `> out`, `>> out`. A pipe is a temporary file
  (`var/tmp/pipe<N>`): stages run one after another, the output of one is the input of
  the next. Programs write with `say()`, read with `inputText()` (`cat`, `grep`, `wc`,
  `head`, `tail`, `hexdump` are filters; with no file and no redirection they read the
  terminal until Ctrl-D) and report problems with `sayErr()`, which is always the
  terminal.
* **`edit <file>`**: a full-screen editor on the alternate screen: arrows, PgUp/PgDn,
  Home/End, typing, Enter, Backspace/Delete, Ctrl-S save, Ctrl-X exit (twice to discard),
  Ctrl-K/Ctrl-Y cut and paste a line, Ctrl-F find. Works on `host/` paths too.

Limits: one terminal cell per character (no double-width or combining marks), no job
control, stages of a pipeline do not run concurrently.

## Moving files in and out

`aeon.sh` shares `./share` (override with `SHARE=dir`) with `--bridge-fs`. It
shows up as the directory `host/`, part of the same tree as the disk: `ls host`,
`cat host/notes.txt`, `cp host/a.txt a.txt`, `cp report.txt host/`,
`mv a.txt host/b.txt`, `mkdir host/out`, `rm host/old.txt` - the same programs,
the same library calls (`Fs.read('host/x')`), no separate commands. The host
exposes only that one folder.

## Adding a command

```
// aeon-os/bin/hello.bl0
import '../lib/userland.bl0';
def main(words) { consoleWriteLine('hello ' + restOf(words, 1)); }
if (startProgram()) { main(Prog.words); }
```

Put it on the disk (`--disk-put aeon-os/bin/hello.bl0:bin/hello.bl0c`, or inside
the OS: `write`/`echo > file` a compiled program) and type `hello world`.
A program has its own copy of every class it imports, so it cannot share state
with the shell - which is why the network commands stay built in.
