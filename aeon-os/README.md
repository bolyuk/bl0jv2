# aeon-os

An operating system written in bl0, run by the bl0jv2 VM. This is a Maven module
(`mvn -pl aeon-os -am test`); the only Java in it is the tests, which boot the OS
and type on its keyboard.

```
aeon-os/
  kernel.bl0        heap, keyboard driver, console (syscall gate)
  init.bl0          the smallest boot: loads the shared libraries and the shell from the disk
  boot.bl0          the same after a demo scheduler and fault isolation show
  libs.txt          the shared libraries, in load order
  smp_boot.bl0      the same on several cores
  shell.bl0         the shell: built-ins, the network commands, program launcher
  child_*.bl0       small programs boot starts
  lib/              log, loader, drivers (UART, terminal emulator), userland (what a program imports), input (stdin),
                    term/keys/lineedit (terminal output, key parsing, the line editor), cmdline (parser),
                    pipe, proc (processes)
  bin/              the commands: ls cat write append touch rm mv cp stat wc head tail grep hexdump df mkdir rmdir
                    edit sleep yes ps kill wait, and the network commands
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
* A program is linked to the **shared libraries** (below): its command line, working folder and
  redirections are variables of those libraries that the shell sets, and it reaches the machine
  only through syscalls.

## Several programs at once

The machine's cores are its processors: the shell runs on core 0, everything that runs at the same time
as something else runs on a worker core (`-n N` gives N-1 of them; `aeon.sh` starts four). There is no
time-slicing - a core runs one job to its end - so the number of jobs at once is the number of workers.

* `command &` runs it in the background: the shell is back at once, `ps` lists the processes (the last 32
  that ended too), `kill <pid>` asks one to stop, `wait [pid]` sleeps until it ends. When a background job
  ends - or prints something - the line editor clears its line, prints the notice above it and draws the
  line again, so it never lands in the middle of what is being typed.
* A **pipeline** runs its stages at the same time, one per core, joined by real pipes
  (`lib/pipe.bl0`: a bounded queue, a writer that gets ahead sleeps, a reader sleeps until there is
  something): `cat big | grep x | wc` streams, and `yes | head -n 3` ends because `head` stops reading,
  after which the producer's next write fails with 'broken pipe'. The last stage runs on the shell's core.
  With too few cores - or a built-in among the stages, which only the shell can run - the pipeline falls back
  to running the stages one after another through temporary files.
* **Ctrl-C** while a program runs stops it (and every other stage of its pipeline); at the prompt it only
  abandons the line. `kill` and Ctrl-C are *requests*: a program stops at its next checkpoint - when it
  writes, reads, or waits - so one that does none of those cannot be stopped (there is no preemption).
* Programs write with `say()` and read with `openInput()`/`Lines`; where that goes (terminal, file, pipe) is a
  per-core `Ctx` that the shell or the process starter fills in, because programs on several cores must
  not share one set of variables. A background job has no terminal to read.

How it works under the VM: loading a program (`execMem`) is serialised and publishes whole new arrays, the
interpreter keeps the code it started with, a user program is unloaded only if nothing was loaded after it,
and the serial port's interrupt is always taken by core 0.

## Shared libraries

The code every program needs - the filesystem, UTF-8, the line editor, the keyboard driver, the
helpers - is not in the programs. `libs.txt` lists the shared libraries in load order. They are
compiled once (`--shared aeon-os/libs.txt` does it when it builds the disk), stored as
`lib/NAME.bl0c` with `lib/MANIFEST`, and loaded once at boot by `loadLibraries()` (`execMem` mode
2): each runs its top level and **exports** the functions and classes it defines. A program built
against them contains only its own code, and its references to library things are names that the
loader links to the loaded copies when it starts. The compiler still checks calls to them (arity,
members) from the library's source. Consequences: a command is 300 bytes to 1.3 KB instead of 40-60
KB, and a library's state is one for the whole system - `Fs.mounted`, the disk driver, the
keyboard ring, the redirections - not a copy per program.

Rules: a library may import only libraries that come before it in the manifest; a program may not
define a name a library defines; two libraries may not export one name. `init.bl0` and `boot.bl0`
are built with the filesystem and loader inside (they are what reads the libraries in) and so are
not linked to anything.

## Running

```
mvn -q package -DskipTests
aeon-os/aeon.sh                 # builds aeon.img from the sources and boots it
```

`aeon.sh` is just the CLI with `--disk aeon.img` and a `--disk-put` per part
(`--disk-put aeon-os/bin:bin` copies a folder, compiling every `.bl0` to a
`.bl0c`). The image keeps its files between runs; `--disk-put` rewrites what it
names.

### Windows

```
mvn -q -DskipTests compile
aeon-os\aeon.cmd
```

`aeon.cmd` mirrors `aeon.sh` (`CORES`, `SHARE`, `SCREEN`, `BOOT` environment
variables) and runs from `target\classes`. With `-k` the CLI switches the
console into raw VT mode through a short PowerShell helper (kernel32
`SetConsoleMode`, UTF-8 code pages) and restores it on exit. Use Windows
Terminal or a recent conhost. Ctrl-C reaches the guest as a key, so leave with
`exit`. This path has not been exercised on real Windows yet.

## The terminal

Two paths lead to the same programs, which cannot tell them apart: the **serial line**
(a UART; the default) and a **text-mode display** (`--display`). `lib/drivers.bl0` holds the
drivers - the UART (initialised once; the receive interrupt drains the FIFO into the keyboard
ring; transmit goes through a ring in raw memory that the transmit-empty interrupt feeds into the
chip 16 bytes at a time - a writer waits only when the ring is full, and `consoleFlush()` waits for
the line to go idle before the shell exits), and an **ANSI terminal emulator** that turns the text and escape
sequences programs write into cells in the display's frame buffer (text, wrapping with the
xterm deferred wrap, scrolling by the display's command, cursor movement, erase, colours and
attributes, cursor visibility, the alternate screen as a second frame buffer). Programs only
see `kernel.bl0`: `consoleWrite()` (a syscall) and the keyboard ring.

The console is a serial terminal, modelled as two devices (see the main README): the
guest writes UTF-8 bytes - with ANSI escape sequences for the cursor and the screen -
to a port, and reads the bytes the terminal sends from a FIFO behind another. So the
OS needs nothing from the host but a terminal: `-k` puts the host terminal in raw mode
(Unix: via `stty`; Windows: a PowerShell helper sets the console modes, see Running; if neither
works it stays in line mode, warns, and shows its own echo too) and passes size and keys through.

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
* **Variables and scripts**: `name=value` sets a shell variable, `$name` / `${name}` use it (not inside
  `'single quotes'`, nor after a backslash); `set` lists them, `unset name` removes one. Expansion happens
  on the line before it is split, so a value is parsed like typed text. `sh file [args]` runs the lines of a
  text file as commands (empty lines and `#` comments skipped); `$0`, `$1`... are the file's name and the
  arguments while it runs.
* **Users and permissions** (`lib/perm.bl0`, `lib/users.bl0`, the file-system gate in `stdlib/fs/fs.bl0`):
  - Every file and folder has an owner, a group and nine permission bits (`ls -l`, `stat`; `chmod 644 f`, `chmod u+x f`,
    `chown alice f` - only the owner may chmod, only root chown). The bits sit in the 8 spare bytes of the 64-byte
    directory entry; entries from before count as root-owned 0644 files and 0755 folders. A new file belongs to whoever
    makes it, with mode 0666/0777 less the umask 022.
  - A program in user mode never touches the disk: `Fs.run` sends it through a system call, and the kernel checks
    the caller's user against the file (`Perm.check`: read needs `r`, writing `w`, creating or removing needs `w` on the
    folder, every folder above must be searchable, root may do anything) before doing it for them. The shell itself is
    such a program. What it does not stop: the VM has no memory protection between programs, so one that overwrites the
    kernel's own variables is not caught; on hardware those live where only the kernel can reach.
  - `etc/passwd` (`name:uid:gid:home`) and `etc/shadow` (`name:salt:hash`, mode 0600, SHA-256 applied 200 times to
    salt+password). `useradd name` (root) makes the user, a locked password and `home/name` (0700); `passwd [user]` sets one
    (the old one is asked for unless you are root); `userdel`. `id`, `whoami`, `su [user]` (root needs no password, anybody
    else does), and `exit` goes back to who you were before su.
  - On a fresh disk the shell starts as root without asking. Once root has a password it asks `login:` and starts the
    user in their home folder. The prompt shows the user's name when it is not root. History, `tmp/` for pipes and the
    system log are per-user, world-writable and kernel-written respectively.
* **Aliases, PATH, history**:
  - `alias name='text'` makes `name` stand for a simple command (`alias` lists them, `unalias name` removes one,
    `which name` says what a word is). Only the first word of a command is replaced; an alias naming another
    alias is followed, each once.
  - Programs are looked for in the folders of the variable `PATH` (`bin` by default, folders separated by `:`,
    `.` is the current folder). A name with a `/` is a path, with or without the `.bl0c`. Tab completes from all of
    PATH and the aliases.
  - `etc/profile` (put on the disk from `aeon-os/etc`) is run as a script before the first prompt: the place for
    `PATH=...` and `alias ...`.
  - `history` lists the remembered commands (the last 200, kept in `var/history`). `!!` repeats the last, `!N`
    command N, `!-N` the Nth from the end, `!text` the latest starting with text; the expanded line is shown and
    remembered. In the line editor **Ctrl-R** searches the history as you type (Ctrl-R again for older, Enter runs
    the match, Esc puts the line back, any other key takes the match for editing).
* **Folders as a whole**: `cp -r`, `mv` and `rm -r` work on a folder with everything in it (a folder is a name
  prefix, so they act on every name below it); without `-r` they say it is a folder. `tree [folder]` shows the
  folders and files below one with sizes. `cp`/`mv` refuse to put a folder into itself.
* **`more [file]`** pages text a screenful at a time (Space/PgDn next screen, Enter/Down one line, `q`/Esc quit;
  it just copies when its output is a pipe or a file). `diff old new` prints the lines that differ (`- N` old,
  `+ N` new; common start and end skipped, the middle compared with an LCS table, refused when huge). `seq [first] last`
  prints numbers.
* **More filters and search**: `sort [-r] [-n]`, `uniq [-c]` (stable merge sort, neighbouring duplicates) read a
  file or standard input like `grep`; `find <text> [folder]` lists files below a folder whose name contains the text.
* **Colours**: the prompt, `ls` (folders, programs), `ps`, `df`, error messages and the banner use
  ANSI colours (`Term.paint`, `lib/term.bl0`). A program's `paint()` colours only when its output goes to the
  terminal - never into a pipe or a file. The line editor measures the prompt without its escape sequences.
  `clear` clears the screen.
* **`top`**: a live full-screen view (alternate screen, refreshed every second): uptime, what each core runs,
  disk use and the process table. `q`, Esc or Ctrl-C leaves. The shell itself is process 1 in `ps`/`top`.
* **`edit <file>`**: a full-screen editor on the alternate screen: arrows, PgUp/PgDn,
  Home/End, typing, Enter, Backspace/Delete, Ctrl-S save, Ctrl-X exit (twice to discard),
  Ctrl-K/Ctrl-Y cut and paste a line, Ctrl-F find. Works on `host/` paths too.

Limits: one terminal cell per character (no double-width or combining marks); there is no
preemption (see Several programs at once), and a background job cannot read the terminal.

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
def main(words) { say('hello ' + restOf(words, 1)); }
programMain(main);
```

Put it on the disk (`--shared aeon-os/libs.txt --disk-put aeon-os/bin/hello.bl0:bin/hello.bl0c`) and
type `hello world`. The network commands stay in the shell because their state (the NIC, the
connections) is the shell's own and not a library's.
